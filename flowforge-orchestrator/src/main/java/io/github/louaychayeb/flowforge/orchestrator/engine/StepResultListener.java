package io.github.louaychayeb.flowforge.orchestrator.engine;

import io.github.louaychayeb.flowforge.common.messaging.StepResult;
import io.github.louaychayeb.flowforge.common.messaging.Topics;
import io.github.louaychayeb.flowforge.common.step.StepHandlerResult;
import io.github.louaychayeb.flowforge.orchestrator.run.StaleAttemptException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Applies step results reported by workers.
 *
 * <p>Results can arrive more than once (a worker redelivers after a crash, Kafka redelivers after a
 * rebalance) or after the attempt was timed out. Such a result names an attempt that is no longer active,
 * so the state machine rejects it as stale and it is acknowledged and dropped without changing any state.
 *
 * <p>Any other failure is rethrown, so Kafka redelivers the result (the default error handler retries ten
 * times, then skips it). A result lost that way is not a correctness problem: the step's lease expires and
 * the attempt is retried.
 */
@Component
public class StepResultListener {

    private static final Logger log = LoggerFactory.getLogger(StepResultListener.class);

    private final StepStateService stateService;
    private final EngineMetrics metrics;

    public StepResultListener(StepStateService stateService, EngineMetrics metrics) {
        this.stateService = stateService;
        this.metrics = metrics;
    }

    @KafkaListener(topics = Topics.STEP_RESULTS)
    public void onResult(StepResult result) {
        try {
            stateService.recordOutcome(result.stepExecutionId(), result.attempt(),
                    new StepHandlerResult(result.outcome(), result.errorMessage()));
            metrics.resultApplied();
            log.info("Recorded {} for step {} attempt {} of run {}", result.outcome(), result.stepExecutionId(),
                    result.attempt(), result.runId());
        } catch (StaleAttemptException e) {
            metrics.resultStale();
            log.info("Ignoring duplicate or stale result {}: {}", result.eventId(), e.getMessage());
        }
    }
}
