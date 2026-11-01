package io.github.louaychayeb.flowforge.orchestrator.engine;

import io.github.louaychayeb.flowforge.common.step.StepHandler;
import io.github.louaychayeb.flowforge.common.step.StepHandlerResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Runs the handler for a claimed step and turns anything it does — including throwing — into a result.
 */
@Component
public class StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(StepExecutor.class);

    private final StepHandlerRegistry registry;

    public StepExecutor(StepHandlerRegistry registry) {
        this.registry = registry;
    }

    public StepHandlerResult execute(ClaimedStep step) {
        Optional<StepHandler> handler = registry.find(step.stepType());
        if (handler.isEmpty()) {
            return StepHandlerResult.permanentFailure("No handler registered for step type " + step.stepType());
        }
        try {
            StepHandlerResult result = handler.get().execute(step.toContext());
            if (result == null) {
                return StepHandlerResult.retryableFailure("Handler returned no result");
            }
            return result;
        } catch (RuntimeException e) {
            log.warn("Step {} ({}) attempt {} of run {} threw", step.stepName(), step.stepType(),
                    step.attempt(), step.runId(), e);
            return StepHandlerResult.retryableFailure(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
