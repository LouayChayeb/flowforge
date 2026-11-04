package io.github.louaychayeb.flowforge.orchestrator.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.louaychayeb.flowforge.common.messaging.StepCommand;
import io.github.louaychayeb.flowforge.common.messaging.Topics;
import io.github.louaychayeb.flowforge.common.step.StepHandlerResult;
import io.github.louaychayeb.flowforge.orchestrator.outbox.OutboxEvent;
import io.github.louaychayeb.flowforge.orchestrator.outbox.OutboxEventRepository;
import io.github.louaychayeb.flowforge.orchestrator.run.StepAttempt;
import io.github.louaychayeb.flowforge.orchestrator.run.StepExecution;
import io.github.louaychayeb.flowforge.orchestrator.run.StepExecutionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The transactions that move steps through their lifecycle: claiming, recording outcomes, and
 * timing out attempts whose lease expired.
 */
@Service
public class StepStateService {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final StepExecutionRepository steps;
    private final OutboxEventRepository outbox;
    private final RetryPolicy retryPolicy;
    private final EngineProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public StepStateService(StepExecutionRepository steps, OutboxEventRepository outbox, RetryPolicy retryPolicy,
                            EngineProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.steps = steps;
        this.outbox = outbox;
        this.retryPolicy = retryPolicy;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Locks up to {@code limit} claimable steps, moves each to {@code RUNNING} with a lease, opens a new
     * attempt, and writes its {@link StepCommand} to the outbox — all in one transaction. Once this commits,
     * no other dispatcher can claim the same steps and every claimed step is guaranteed a command.
     */
    @Transactional
    public List<ClaimedStep> claim(int limit) {
        Instant now = clock.instant();
        List<ClaimedStep> claimed = new ArrayList<>();
        for (StepExecution step : steps.lockClaimable(now, limit)) {
            StepAttempt attempt = step.start(now, properties.leaseDuration());
            ClaimedStep claimedStep = new ClaimedStep(
                    step.getRun().getId(),
                    step.getId(),
                    step.getName(),
                    step.getStepType(),
                    attempt.getAttemptNumber(),
                    step.getRun().getInput());
            outbox.save(new OutboxEvent(Topics.STEP_COMMANDS, claimedStep.runId().toString(),
                    objectMapper.convertValue(claimedStep.toCommand(), JSON_OBJECT), now));
            claimed.add(claimedStep);
        }
        return claimed;
    }

    /**
     * Applies a worker's result to the step and its run: schedules a retry, advances to the next step,
     * starts compensation, or ends the run.
     */
    @Transactional
    public void recordOutcome(UUID stepExecutionId, int attempt, StepHandlerResult result) {
        Instant now = clock.instant();
        StepExecution step = steps.findById(stepExecutionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown step execution " + stepExecutionId));
        switch (result.outcome()) {
            case SUCCEEDED -> step.complete(attempt, now);
            case RETRYABLE_FAILURE ->
                    step.failRetryable(attempt, result.errorMessage(), retryPolicy.backoffAfter(attempt), now);
            case PERMANENT_FAILURE -> step.failPermanently(attempt, result.errorMessage(), now);
        }
    }

    /**
     * Times out every running step whose lease expired before {@code cutoff}, so it is retried (or fails)
     * instead of waiting forever for a result that will not come.
     *
     * @return the number of steps recovered
     */
    @Transactional
    public int recoverExpiredLeases(Instant cutoff) {
        Instant now = clock.instant();
        List<StepExecution> expired = steps.lockExpiredLeases(cutoff, properties.batchSize());
        for (StepExecution step : expired) {
            step.expireLease(retryPolicy.backoffAfter(step.getAttemptCount()), now);
        }
        return expired.size();
    }
}
