package io.github.louaychayeb.flowforge.orchestrator.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.louaychayeb.flowforge.common.messaging.StepCommand;
import io.github.louaychayeb.flowforge.common.messaging.Topics;
import io.github.louaychayeb.flowforge.common.step.StepHandlerResult;
import io.github.louaychayeb.flowforge.orchestrator.error.NotFoundException;
import io.github.louaychayeb.flowforge.orchestrator.outbox.OutboxEvent;
import io.github.louaychayeb.flowforge.orchestrator.outbox.OutboxEventRepository;
import io.github.louaychayeb.flowforge.orchestrator.run.StepAttempt;
import io.github.louaychayeb.flowforge.orchestrator.run.StepExecution;
import io.github.louaychayeb.flowforge.orchestrator.run.StepExecutionRepository;
import io.github.louaychayeb.flowforge.orchestrator.run.StepKind;
import io.github.louaychayeb.flowforge.orchestrator.run.StepStatus;
import io.github.louaychayeb.flowforge.orchestrator.run.WorkflowRun;
import io.github.louaychayeb.flowforge.orchestrator.run.WorkflowRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The transactions that move steps through their lifecycle: claiming, recording outcomes, and
 * timing out attempts whose lease expired.
 */
@Service
public class StepStateService {

    private static final Logger log = LoggerFactory.getLogger(StepStateService.class);

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    /** Largest step output accepted, serialised. Outputs are repeated in every later command of the run. */
    static final int MAX_OUTPUT_BYTES = 64 * 1024;

    private final StepExecutionRepository steps;
    private final WorkflowRunRepository runs;
    private final OutboxEventRepository outbox;
    private final RetryPolicy retryPolicy;
    private final EngineProperties properties;
    private final ObjectMapper objectMapper;
    private final EngineMetrics metrics;
    private final TransactionTemplate transactions;
    private final ApplicationEventPublisher events;
    private final RunTracing tracing;
    private final Clock clock;

    public StepStateService(StepExecutionRepository steps, WorkflowRunRepository runs, OutboxEventRepository outbox,
                            RetryPolicy retryPolicy,
                            EngineProperties properties, ObjectMapper objectMapper, EngineMetrics metrics,
                            TransactionTemplate transactions, ApplicationEventPublisher events, RunTracing tracing,
                            Clock clock) {
        this.events = events;
        this.tracing = tracing;
        this.steps = steps;
        this.runs = runs;
        this.outbox = outbox;
        this.retryPolicy = retryPolicy;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.transactions = transactions;
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
            if (step.isTimer()) {
                fireTimer(step, attempt, now);
                continue;
            }
            ClaimedStep claimedStep = new ClaimedStep(
                    step.getRun().getId(),
                    step.getId(),
                    step.getName(),
                    step.getStepType(),
                    attempt.getAttemptNumber(),
                    step.getRun().getInput(),
                    step.getLeaseExpiresAt(),
                    step.getCompensates() == null ? null : step.getCompensates().getId(),
                    outputsOf(step.getRun()));
            String traceParent = tracing.stepClaimed(step.getRun().getTraceParent(), claimedStep.runId().toString(),
                    step.getName(), step.getStepType(), attempt.getAttemptNumber());
            outbox.save(new OutboxEvent(Topics.STEP_COMMANDS, claimedStep.runId().toString(),
                    objectMapper.convertValue(claimedStep.toCommand(), JSON_OBJECT), now, traceParent));
            claimed.add(claimedStep);
        }
        metrics.stepsClaimed(claimed.size());
        if (!claimed.isEmpty()) {
            events.publishEvent(new EngineEvents.CommandsWritten(claimed.size()));
        }
        return claimed;
    }

    /**
     * A due timer fires inside the claim: the orchestrator completes it itself, no worker and no message involved.
     * Completing it may make other steps ready — a run-level decision — so the run is locked first, after the step,
     * in the same order as everywhere else.
     */
    private void fireTimer(StepExecution step, StepAttempt attempt, Instant now) {
        lockRun(step);
        step.complete(attempt.getAttemptNumber(), Map.of("firedAt", now.toString()), now);
        log.info("Timer {} of run {} fired", step.getName(), step.getRun().getId());
        recordTimings(step);
        announceReadySteps(step.getRun(), now);
    }

    /** What every completed forward step of the run produced, by step name, for the next commands. */
    private static Map<String, Map<String, Object>> outputsOf(WorkflowRun run) {
        Map<String, Map<String, Object>> outputs = new LinkedHashMap<>();
        for (StepExecution s : run.getSteps()) {
            if (s.getKind() == StepKind.FORWARD && s.getStatus() == StepStatus.COMPLETED && !s.getOutput().isEmpty()) {
                outputs.put(s.getName(), s.getOutput());
            }
        }
        return outputs;
    }

    /**
     * Applies a worker's result to the step and its run: schedules a retry, advances to the next step,
     * starts compensation, or ends the run. The step row is locked first, so a result and a lease expiry
     * for the same attempt can never both be applied.
     *
     * @throws io.github.louaychayeb.flowforge.orchestrator.run.StaleAttemptException if the result is for an
     *         attempt that is no longer active
     */
    @Transactional
    public void recordOutcome(UUID stepExecutionId, int attempt, StepHandlerResult result) {
        Instant now = clock.instant();
        StepExecution step = steps.findByIdForUpdate(stepExecutionId)
                .orElseThrow(() -> new NotFoundException.StepNotFound(stepExecutionId));
        lockRun(step);
        switch (result.outcome()) {
            case SUCCEEDED -> {
                String problem = outputProblem(result.output());
                if (problem == null) {
                    step.complete(attempt, result.output(), now);
                } else {
                    // Outputs travel in every later command; an oversized one would bloat them all.
                    step.failPermanently(attempt, problem, now);
                }
            }
            case RETRYABLE_FAILURE ->
                    step.failRetryable(attempt, result.errorMessage(), retryPolicy.backoffAfter(attempt), now);
            case PERMANENT_FAILURE -> step.failPermanently(attempt, result.errorMessage(), now);
        }
        recordTimings(step);
        announceReadySteps(step.getRun(), now);
    }

    /** Why a step's output cannot be stored, or {@code null} if it can. */
    private String outputProblem(Map<String, Object> output) {
        if (output.isEmpty()) {
            return null;
        }
        try {
            int size = objectMapper.writeValueAsBytes(output).length;
            return size <= MAX_OUTPUT_BYTES ? null
                    : "Step output is %d bytes; the limit is %d".formatted(size, MAX_OUTPUT_BYTES);
        } catch (JsonProcessingException e) {
            return "Step output is not serialisable: " + e.getOriginalMessage();
        }
    }

    /**
     * Attempt and run durations for Prometheus. Recorded just before the transaction commits; in the rare case it
     * then rolls back, the outcome is applied again on redelivery and counted twice — fine for a latency histogram.
     */
    private void recordTimings(StepExecution step) {
        StepAttempt last = step.getAttempts().get(step.getAttempts().size() - 1);
        if (last.getFinishedAt() != null) {
            metrics.attemptFinished(step.getStepType(), last.getStatus().name(),
                    Duration.between(last.getStartedAt(), last.getFinishedAt()));
        }
        WorkflowRun run = step.getRun();
        if (run.getStatus().isTerminal()) {
            metrics.runFinished(run.getDefinition().getName(), run.getStatus().name(),
                    Duration.between(run.getCreatedAt(), run.getCompletedAt()));
        }
    }

    /** Step row first, then run row — the same order everywhere, so the two locks cannot deadlock. */
    private void lockRun(StepExecution step) {
        runs.findByIdForUpdate(step.getRun().getId())
                .orElseThrow(() -> new NotFoundException.RunNotFound(step.getRun().getId()));
    }

    /**
     * After the run changed, some of its steps may be claimable — now, after a retry backoff, or when a timer is due:
     * tell {@link EngineWakeup} each time, which acts once this transaction has committed.
     */
    private void announceReadySteps(WorkflowRun run, Instant now) {
        run.activeSteps().stream()
                .filter(s -> s.getStatus() != StepStatus.RUNNING && s.getNextAttemptAt() != null)
                .map(s -> s.getNextAttemptAt().isAfter(now) ? s.getNextAttemptAt() : now)
                .distinct()
                .forEach(at -> events.publishEvent(new EngineEvents.StepsMayBeReady(at)));
    }

    /**
     * Times out running steps whose lease expired before {@code cutoff}, so they are retried (or fail)
     * instead of waiting forever for a result that will not come.
     *
     * <p>Each step is recovered in its own transaction under a row lock, so a step that cannot be recovered
     * is logged and skipped instead of rolling back — and endlessly blocking — the recovery of every other
     * step in the batch.
     *
     * @return the number of steps recovered
     */
    public int recoverExpiredLeases(Instant cutoff) {
        int recovered = 0;
        for (UUID stepId : steps.findExpiredLeaseIds(cutoff, properties.batchSize())) {
            try {
                if (Boolean.TRUE.equals(transactions.execute(status -> expireIfStillExpired(stepId, cutoff)))) {
                    recovered++;
                }
            } catch (RuntimeException e) {
                log.error("Could not recover step {} after its lease expired", stepId, e);
            }
        }
        metrics.leasesExpired(recovered);
        return recovered;
    }

    private boolean expireIfStillExpired(UUID stepId, Instant cutoff) {
        StepExecution step = steps.findByIdForUpdate(stepId).orElse(null);
        // Another sweeper, or the step's result, may have got there first while this one waited for the lock.
        if (step == null || step.getStatus() != StepStatus.RUNNING || !step.getLeaseExpiresAt().isBefore(cutoff)) {
            return false;
        }
        lockRun(step);
        Instant now = clock.instant();
        step.expireLease(retryPolicy.backoffAfter(step.getAttemptCount()), now);
        recordTimings(step);
        announceReadySteps(step.getRun(), now);
        return true;
    }
}
