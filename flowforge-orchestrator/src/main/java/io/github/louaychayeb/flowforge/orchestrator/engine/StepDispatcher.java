package io.github.louaychayeb.flowforge.orchestrator.engine;

import io.github.louaychayeb.flowforge.common.messaging.StepCommand;
import io.github.louaychayeb.flowforge.common.messaging.Topics;
import io.github.louaychayeb.flowforge.common.step.StepHandlerResult;
import io.github.louaychayeb.flowforge.orchestrator.run.StaleAttemptException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Claims eligible steps and publishes a {@link StepCommand} for each one. Outcomes arrive
 * asynchronously through {@link StepResultListener}.
 *
 * <p>The claim commits before the command is published. If publishing fails, the attempt is
 * recorded as a retryable failure so the step is retried rather than left {@code RUNNING}. A crash
 * between the claim and the publish still strands the step; the transactional outbox and lease
 * recovery in Phase 3 close that gap.
 */
@Component
public class StepDispatcher {

    private static final Logger log = LoggerFactory.getLogger(StepDispatcher.class);

    private final StepStateService stateService;
    private final KafkaTemplate<String, StepCommand> kafka;
    private final EngineProperties properties;

    public StepDispatcher(StepStateService stateService, KafkaTemplate<String, StepCommand> kafka,
                          EngineProperties properties) {
        this.stateService = stateService;
        this.kafka = kafka;
        this.properties = properties;
    }

    /**
     * Claims one batch of eligible steps and publishes their commands.
     *
     * @return the number of steps claimed; {@code 0} means there was nothing to do
     */
    public int dispatchOnce() {
        List<ClaimedStep> claimed = stateService.claim(properties.batchSize());
        for (ClaimedStep step : claimed) {
            publish(step);
        }
        return claimed.size();
    }

    private void publish(ClaimedStep step) {
        try {
            kafka.send(Topics.STEP_COMMANDS, step.runId().toString(), step.toCommand())
                    .get(properties.publishTimeout().toMillis(), TimeUnit.MILLISECONDS);
            log.info("Dispatched step {} ({}) attempt {} of run {}", step.stepName(), step.stepType(),
                    step.attempt(), step.runId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failPublish(step, e);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            failPublish(step, e);
        }
    }

    private void failPublish(ClaimedStep step, Exception cause) {
        log.warn("Failed to publish command for step {} attempt {} of run {}", step.stepName(), step.attempt(),
                step.runId(), cause);
        try {
            stateService.recordOutcome(step.stepExecutionId(), step.attempt(),
                    StepHandlerResult.retryableFailure("Failed to publish step command: " + cause.getMessage()));
        } catch (StaleAttemptException e) {
            log.warn("Discarding publish failure: {}", e.getMessage());
        }
    }
}
