package io.github.louaychayeb.flowforge.common.step;

import io.github.louaychayeb.flowforge.common.messaging.StepOutcome;

import java.util.Objects;

/**
 * What a {@link StepHandler} reports after executing a step.
 *
 * @param outcome      how the attempt ended
 * @param errorMessage failure details, {@code null} on success
 */
public record StepHandlerResult(StepOutcome outcome, String errorMessage) {

    public StepHandlerResult {
        Objects.requireNonNull(outcome, "outcome");
    }

    public static StepHandlerResult succeeded() {
        return new StepHandlerResult(StepOutcome.SUCCEEDED, null);
    }

    public static StepHandlerResult retryableFailure(String errorMessage) {
        return new StepHandlerResult(StepOutcome.RETRYABLE_FAILURE, errorMessage);
    }

    public static StepHandlerResult permanentFailure(String errorMessage) {
        return new StepHandlerResult(StepOutcome.PERMANENT_FAILURE, errorMessage);
    }
}
