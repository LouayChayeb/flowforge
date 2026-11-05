package io.github.louaychayeb.flowforge.common.step;

import io.github.louaychayeb.flowforge.common.messaging.StepCommand;
import io.github.louaychayeb.flowforge.common.messaging.StepOutcome;
import io.github.louaychayeb.flowforge.common.messaging.StepResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepCommandProcessorTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void reportsTheHandlerResultForTheCommandsAttempt() {
        StepCommandProcessor processor = processor(handler("OK", context -> StepHandlerResult.succeeded()));
        StepCommand command = command("OK", 2, null);

        StepResult result = processor.process(command).orElseThrow();

        assertThat(result.outcome()).isEqualTo(StepOutcome.SUCCEEDED);
        assertThat(result.runId()).isEqualTo(command.runId());
        assertThat(result.stepExecutionId()).isEqualTo(command.stepExecutionId());
        assertThat(result.attempt()).isEqualTo(2);
        assertThat(result.eventId()).isNotNull().isNotEqualTo(command.eventId());
    }

    @Test
    void unknownStepTypeIsAPermanentFailure() {
        StepResult result = processor().process(command("MISSING", 1, null)).orElseThrow();

        assertThat(result.outcome()).isEqualTo(StepOutcome.PERMANENT_FAILURE);
        assertThat(result.errorMessage()).contains("MISSING");
    }

    @Test
    void handlerExceptionIsARetryableFailure() {
        StepCommandProcessor processor = processor(handler("BOOM", context -> {
            throw new IllegalStateException("kaput");
        }));

        StepResult result = processor.process(command("BOOM", 1, null)).orElseThrow();

        assertThat(result.outcome()).isEqualTo(StepOutcome.RETRYABLE_FAILURE);
        assertThat(result.errorMessage()).isEqualTo("IllegalStateException: kaput");
    }

    @Test
    void typedFailuresKeepTheirClassification() {
        StepCommandProcessor processor = processor(
                handler("PERM", context -> {
                    throw new StepFailureException.Permanent("card declined");
                }),
                handler("INVALID", context -> {
                    throw new StepFailureException.InvalidInput("orderId is required");
                }),
                handler("RETRY", context -> {
                    throw new StepFailureException.Retryable("timeout");
                }));

        StepResult permanent = processor.process(command("PERM", 1, null)).orElseThrow();
        assertThat(permanent.outcome()).isEqualTo(StepOutcome.PERMANENT_FAILURE);
        assertThat(permanent.errorMessage()).isEqualTo("card declined");
        assertThat(processor.process(command("INVALID", 1, null)).orElseThrow().outcome())
                .isEqualTo(StepOutcome.PERMANENT_FAILURE);
        assertThat(processor.process(command("RETRY", 1, null)).orElseThrow().outcome())
                .isEqualTo(StepOutcome.RETRYABLE_FAILURE);
    }

    @Test
    void outputsFlowFromEarlierStepsToTheHandlerAndBackInTheResult() {
        StepCommandProcessor processor = processor(handler("SHIP", context ->
                StepHandlerResult.succeeded(Map.of("trackingNumber",
                        "TRK-" + context.requireOutput("charge-payment", "chargeId")))));
        StepCommand command = new StepCommand(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "ship", "SHIP",
                1, Map.of(), null, null, Map.of("charge-payment", Map.of("chargeId", "ch_42")));

        StepResult result = processor.process(command).orElseThrow();

        assertThat(result.output()).containsEntry("trackingNumber", "TRK-ch_42");
    }

    @Test
    void aMissingRequiredOutputIsAPermanentFailure() {
        StepCommandProcessor processor = processor(handler("SHIP", context -> {
            context.requireOutput("charge-payment", "chargeId");
            return StepHandlerResult.succeeded();
        }));

        StepResult result = processor.process(command("SHIP", 1, null)).orElseThrow();

        assertThat(result.outcome()).isEqualTo(StepOutcome.PERMANENT_FAILURE);
        assertThat(result.errorMessage()).contains("chargeId");
    }

    @Test
    void onlyASuccessCanCarryOutput() {
        assertThatThrownBy(() -> new StepHandlerResult(StepOutcome.PERMANENT_FAILURE, "no", Map.of("x", 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void skipsCommandsPastTheirDeadlineWithoutRunningTheHandler() {
        AtomicInteger executions = new AtomicInteger();
        StepCommandProcessor processor = processor(handler("OK", context -> {
            executions.incrementAndGet();
            return StepHandlerResult.succeeded();
        }));

        assertThat(processor.process(command("OK", 1, NOW.minusMillis(1)))).isEmpty();
        assertThat(processor.process(command("OK", 1, NOW.plusSeconds(5)))).isPresent();
        assertThat(executions).hasValue(1);
    }

    @Test
    void rejectsTwoHandlersForTheSameType() {
        assertThatThrownBy(() -> processor(
                handler("DUP", context -> StepHandlerResult.succeeded()),
                handler("DUP", context -> StepHandlerResult.succeeded())))
                .isInstanceOf(IllegalStateException.class);
    }

    private static StepCommandProcessor processor(StepHandler... handlers) {
        return new StepCommandProcessor(List.of(handlers), CLOCK);
    }

    private static StepCommand command(String type, int attempt, Instant deadline) {
        return new StepCommand(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "step", type, attempt,
                Map.of("orderId", "ORD-1"), deadline);
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
