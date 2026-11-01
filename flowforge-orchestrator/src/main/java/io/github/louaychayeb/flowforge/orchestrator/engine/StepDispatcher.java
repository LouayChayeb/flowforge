package io.github.louaychayeb.flowforge.orchestrator.engine;

import io.github.louaychayeb.flowforge.common.step.StepHandlerResult;
import io.github.louaychayeb.flowforge.orchestrator.run.StaleAttemptException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Claims eligible steps, executes them and records their outcomes.
 *
 * <p>Phase 1 executes steps in-process. In Phase 2 the execute step is replaced by publishing a
 * command to Kafka and the outcome arrives later as a result message.
 */
@Component
public class StepDispatcher {

    private static final Logger log = LoggerFactory.getLogger(StepDispatcher.class);

    private final StepStateService stateService;
    private final StepExecutor executor;
    private final EngineProperties properties;

    public StepDispatcher(StepStateService stateService, StepExecutor executor, EngineProperties properties) {
        this.stateService = stateService;
        this.executor = executor;
        this.properties = properties;
    }

    /**
     * Runs one claim–execute–record cycle.
     *
     * @return the number of steps claimed; {@code 0} means there was nothing to do
     */
    public int dispatchOnce() {
        List<ClaimedStep> claimed = stateService.claim(properties.batchSize());
        for (ClaimedStep step : claimed) {
            StepHandlerResult result = executor.execute(step);
            try {
                stateService.recordOutcome(step.stepExecutionId(), step.attempt(), result);
            } catch (StaleAttemptException e) {
                log.warn("Discarding outcome: {}", e.getMessage());
            }
            log.info("Step {} ({}) attempt {} of run {} -> {}", step.stepName(), step.stepType(),
                    step.attempt(), step.runId(), result.outcome());
        }
        return claimed.size();
    }
}
