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
 * rebalance). A duplicate names an attempt that is no longer active, so the state machine rejects it
 * as stale and it is acknowledged and dropped without changing any state.
 */
@Component
public class StepResultListener {

    private static final Logger log = LoggerFactory.getLogger(StepResultListener.class);

    private final StepStateService stateService;

    public StepResultListener(StepStateService stateService) {
        this.stateService = stateService;
    }

    @KafkaListener(topics = Topics.STEP_RESULTS)
    public void onResult(StepResult result) {
        try {
            stateService.recordOutcome(result.stepExecutionId(), result.attempt(),
                    new StepHandlerResult(result.outcome(), result.errorMessage()));
            log.info("Recorded {} for step {} attempt {} of run {}", result.outcome(), result.stepExecutionId(),
                    result.attempt(), result.runId());
        } catch (StaleAttemptException e) {
            log.info("Ignoring duplicate or stale result {}: {}", result.eventId(), e.getMessage());
        }
    }
}
