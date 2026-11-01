package io.github.louaychayeb.flowforge.common.step;

import io.github.louaychayeb.flowforge.common.messaging.StepCommand;
import io.github.louaychayeb.flowforge.common.messaging.StepResult;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Turns a {@link StepCommand} into a {@link StepResult} by running the matching {@link StepHandler}.
 * Everything a handler does, including throwing, becomes a result: an unknown step type is a permanent
 * failure, an exception or a missing result is retryable.
 *
 * <p>Transport-agnostic, so the worker's Kafka listener and in-test workers share the same behaviour.
 */
public class StepCommandProcessor {

    private final Map<String, StepHandler> handlers = new HashMap<>();

    public StepCommandProcessor(Collection<? extends StepHandler> handlers) {
        for (StepHandler handler : handlers) {
            StepHandler previous = this.handlers.putIfAbsent(handler.stepType(), handler);
            if (previous != null) {
                throw new IllegalStateException("Two handlers registered for step type " + handler.stepType()
                        + ": " + previous.getClass().getName() + " and " + handler.getClass().getName());
            }
        }
    }

    public StepResult process(StepCommand command) {
        StepHandlerResult result = execute(command);
        return new StepResult(UUID.randomUUID(), command.runId(), command.stepExecutionId(), command.attempt(),
                result.outcome(), result.errorMessage());
    }

    public Set<String> stepTypes() {
        return new TreeSet<>(handlers.keySet());
    }

    private StepHandlerResult execute(StepCommand command) {
        StepHandler handler = handlers.get(command.stepType());
        if (handler == null) {
            return StepHandlerResult.permanentFailure("No handler registered for step type " + command.stepType());
        }
        StepContext context = new StepContext(command.runId(), command.stepExecutionId(), command.stepName(),
                command.attempt(), command.input() == null ? Map.of() : command.input());
        try {
            StepHandlerResult result = handler.execute(context);
            return result != null ? result : StepHandlerResult.retryableFailure("Handler returned no result");
        } catch (RuntimeException e) {
            return StepHandlerResult.retryableFailure(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
