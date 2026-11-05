package io.github.louaychayeb.flowforge.common.step;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Everything a {@link StepHandler} gets to know about the step it executes.
 *
 * @param runId                      the workflow run
 * @param stepExecutionId            stable id of the logical step; use it as an idempotency key for side effects
 * @param stepName                   the step's name within the workflow definition
 * @param attempt                    attempt number, starting at 1
 * @param input                      the run's input
 * @param compensatesStepExecutionId for a compensation step, the id of the step it undoes (so the idempotency
 *                                   key of the side effect to reverse); otherwise {@code null}
 */
public record StepContext(
        UUID runId,
        UUID stepExecutionId,
        String stepName,
        int attempt,
        Map<String, Object> input,
        UUID compensatesStepExecutionId
) {

    public StepContext {
        // Not Map.copyOf: JSON input may legitimately contain null values.
        input = Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }

    public StepContext(UUID runId, UUID stepExecutionId, String stepName, int attempt, Map<String, Object> input) {
        this(runId, stepExecutionId, stepName, attempt, input, null);
    }
}
