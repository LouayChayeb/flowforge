package io.github.louaychayeb.flowforge.common.step;

import io.github.louaychayeb.flowforge.common.messaging.StepCommand;
import io.github.louaychayeb.flowforge.common.messaging.StepResult;

import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Turns a {@link StepCommand} into a {@link StepResult} by running the matching {@link StepHandler}.
 * Everything a handler does, including throwing, becomes a result: an unknown step type is a permanent
 * failure, an exception or a missing result is retryable.
 *
 * <p>A command received after its {@link StepCommand#deadline() deadline} is skipped: the orchestrator has
 * already timed that attempt out (or is about to) and will send a new one, so executing it would only add
 * load — and under load, wasted work is what turns a backlog into a retry storm.
 *
 * <p>Transport-agnostic, so the worker's Kafka listener and in-test workers share the same behaviour.
 */
public class StepCommandProcessor {

    private final Map<String, StepHandler> handlers = new HashMap<>();
    private final Clock clock;

    public StepCommandProcessor(Collection<? extends StepHandler> handlers, Clock clock) {
        this.clock = clock;
        for (StepHandler handler : handlers) {
            StepHandler previous = this.handlers.putIfAbsent(handler.stepType(), handler);
            if (previous != null) {
                throw new IllegalStateException("Two handlers registered for step type " + handler.stepType()
                        + ": " + previous.getClass().getName() + " and " + handler.getClass().getName());
            }
        }
    }

    /**
     * @return the result to report, or empty if the command's deadline has passed and it was skipped
     */
    public Optional<StepResult> process(StepCommand command) {
        if (isExpired(command)) {
            return Optional.empty();
        }
        StepHandlerResult result = execute(command);
        return Optional.of(new StepResult(UUID.randomUUID(), command.runId(), command.stepExecutionId(),
                command.attempt(), result.outcome(), result.errorMessage()));
    }

    public boolean isExpired(StepCommand command) {
        return command.deadline() != null && clock.instant().isAfter(command.deadline());
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
                command.attempt(), command.input() == null ? Map.of() : command.input(),
                command.compensatesStepExecutionId());
        try {
            StepHandlerResult result = handler.execute(context);
            return result != null ? result : StepHandlerResult.retryableFailure("Handler returned no result");
        } catch (RuntimeException e) {
            return StepHandlerResult.retryableFailure(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
