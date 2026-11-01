package io.github.louaychayeb.flowforge.orchestrator.engine;

import io.github.louaychayeb.flowforge.common.step.StepHandlerResult;
import io.github.louaychayeb.flowforge.orchestrator.run.StepAttempt;
import io.github.louaychayeb.flowforge.orchestrator.run.StepExecution;
import io.github.louaychayeb.flowforge.orchestrator.run.StepExecutionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The two short transactions around every step attempt: claiming the step, and recording its outcome.
 * Handler code runs between them, outside any database transaction, so a slow step never holds locks.
 */
@Service
public class StepStateService {

    private final StepExecutionRepository steps;
    private final RetryPolicy retryPolicy;
    private final Clock clock;

    public StepStateService(StepExecutionRepository steps, RetryPolicy retryPolicy, Clock clock) {
        this.steps = steps;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
    }

    /**
     * Locks up to {@code limit} claimable steps, moves each to {@code RUNNING} and opens a new attempt.
     * Once this transaction commits, no other dispatcher can claim the same steps.
     */
    @Transactional
    public List<ClaimedStep> claim(int limit) {
        Instant now = clock.instant();
        List<ClaimedStep> claimed = new ArrayList<>();
        for (StepExecution step : steps.lockClaimable(now, limit)) {
            StepAttempt attempt = step.start(now);
            claimed.add(new ClaimedStep(
                    step.getRun().getId(),
                    step.getId(),
                    step.getName(),
                    step.getStepType(),
                    attempt.getAttemptNumber(),
                    step.getRun().getInput()));
        }
        return claimed;
    }

    /**
     * Applies a handler's result to the step and its run, scheduling a retry, advancing to the next step,
     * or ending the run.
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
}
