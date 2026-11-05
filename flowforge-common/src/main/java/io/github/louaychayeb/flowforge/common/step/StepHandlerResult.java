package io.github.louaychayeb.flowforge.common.step;

import io.github.louaychayeb.flowforge.common.messaging.StepOutcome;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What a {@link StepHandler} reports after executing a step.
 *
 * @param outcome      how the attempt ended
 * @param errorMessage failure details, {@code null} on success
 * @param output       data the step produced for later steps, e.g. {@code {"chargeId": "ch_42"}}; empty unless the
 *                     step succeeded. Stored with the step and handed to every step that runs after it.
 */
public record StepHandlerResult(StepOutcome outcome, String errorMessage, Map<String, Object> output) {

    public StepHandlerResult {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome != StepOutcome.SUCCEEDED && output != null && !output.isEmpty()) {
            throw new IllegalArgumentException("Only a successful step can produce output");
        }
        // Not Map.copyOf: JSON output may legitimately contain null values.
        output = output == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(output));
    }

    public StepHandlerResult(StepOutcome outcome, String errorMessage) {
        this(outcome, errorMessage, Map.of());
    }

    public static StepHandlerResult succeeded() {
        return new StepHandlerResult(StepOutcome.SUCCEEDED, null, Map.of());
    }

    /** Success with data for later steps. */
    public static StepHandlerResult succeeded(Map<String, Object> output) {
        return new StepHandlerResult(StepOutcome.SUCCEEDED, null, output);
    }

    public static StepHandlerResult retryableFailure(String errorMessage) {
        return new StepHandlerResult(StepOutcome.RETRYABLE_FAILURE, errorMessage);
    }

    public static StepHandlerResult permanentFailure(String errorMessage) {
        return new StepHandlerResult(StepOutcome.PERMANENT_FAILURE, errorMessage);
    }
}
