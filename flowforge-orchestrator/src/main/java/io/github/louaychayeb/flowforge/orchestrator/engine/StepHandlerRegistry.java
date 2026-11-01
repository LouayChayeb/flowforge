package io.github.louaychayeb.flowforge.orchestrator.engine;

import io.github.louaychayeb.flowforge.common.step.StepHandler;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Looks up the {@link StepHandler} bean responsible for a step type.
 */
@Component
public class StepHandlerRegistry {

    private final Map<String, StepHandler> handlers = new HashMap<>();

    public StepHandlerRegistry(List<StepHandler> handlers) {
        for (StepHandler handler : handlers) {
            StepHandler previous = this.handlers.putIfAbsent(handler.stepType(), handler);
            if (previous != null) {
                throw new IllegalStateException("Two handlers registered for step type " + handler.stepType()
                        + ": " + previous.getClass().getName() + " and " + handler.getClass().getName());
            }
        }
    }

    public Optional<StepHandler> find(String stepType) {
        return Optional.ofNullable(handlers.get(stepType));
    }

    public boolean supports(String stepType) {
        return handlers.containsKey(stepType);
    }

    public Set<String> stepTypes() {
        return new TreeSet<>(handlers.keySet());
    }
}
