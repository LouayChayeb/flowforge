package io.github.louaychayeb.flowforge.common.step;

import io.github.louaychayeb.flowforge.common.messaging.StepCommand;
import io.github.louaychayeb.flowforge.common.messaging.StepOutcome;
import io.github.louaychayeb.flowforge.common.messaging.StepResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepCommandProcessorTest {

    @Test
    void reportsTheHandlerResultForTheCommandsAttempt() {
        StepCommandProcessor processor = new StepCommandProcessor(List.of(
                handler("OK", context -> StepHandlerResult.succeeded())));
        StepCommand command = command("OK", 2);

        StepResult result = processor.process(command);

        assertThat(result.outcome()).isEqualTo(StepOutcome.SUCCEEDED);
        assertThat(result.runId()).isEqualTo(command.runId());
        assertThat(result.stepExecutionId()).isEqualTo(command.stepExecutionId());
        assertThat(result.attempt()).isEqualTo(2);
        assertThat(result.eventId()).isNotNull().isNotEqualTo(command.eventId());
    }

    @Test
    void unknownStepTypeIsAPermanentFailure() {
        StepResult result = new StepCommandProcessor(List.of()).process(command("MISSING", 1));

        assertThat(result.outcome()).isEqualTo(StepOutcome.PERMANENT_FAILURE);
        assertThat(result.errorMessage()).contains("MISSING");
    }

    @Test
    void handlerExceptionIsARetryableFailure() {
        StepCommandProcessor processor = new StepCommandProcessor(List.of(handler("BOOM", context -> {
            throw new IllegalStateException("kaput");
        })));

        StepResult result = processor.process(command("BOOM", 1));

        assertThat(result.outcome()).isEqualTo(StepOutcome.RETRYABLE_FAILURE);
        assertThat(result.errorMessage()).isEqualTo("IllegalStateException: kaput");
    }

    @Test
    void rejectsTwoHandlersForTheSameType() {
        assertThatThrownBy(() -> new StepCommandProcessor(List.of(
                handler("DUP", context -> StepHandlerResult.succeeded()),
                handler("DUP", context -> StepHandlerResult.succeeded()))))
                .isInstanceOf(IllegalStateException.class);
    }

    private static StepCommand command(String type, int attempt) {
        return new StepCommand(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "step", type, attempt,
                Map.of("orderId", "ORD-1"));
    }

    private static StepHandler handler(String type, Function<StepContext, StepHandlerResult> body) {
        return new StepHandler() {
            @Override
            public String stepType() {
                return type;
            }

            @Override
            public StepHandlerResult execute(StepContext context) {
                return body.apply(context);
            }
        };
    }
}
