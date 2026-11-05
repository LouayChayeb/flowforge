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
 * @param outputs                    the output of every step of the run that has completed so far, by step name
 */
public record StepContext(
        UUID runId,
        UUID stepExecutionId,
        String stepName,
        int attempt,
        Map<String, Object> input,
        UUID compensatesStepExecutionId,
        Map<String, Map<String, Object>> outputs
) {

    public StepContext {
        // Not Map.copyOf: JSON may legitimately contain null values.
        input = Collections.unmodifiableMap(new LinkedHashMap<>(input == null ? Map.of() : input));
        outputs = Collections.unmodifiableMap(new LinkedHashMap<>(outputs == null ? Map.of() : outputs));
    }

    public StepContext(UUID runId, UUID stepExecutionId, String stepName, int attempt, Map<String, Object> input) {
        this(runId, stepExecutionId, stepName, attempt, input, null, Map.of());
    }

    public StepContext(UUID runId, UUID stepExecutionId, String stepName, int attempt, Map<String, Object> input,
                       UUID compensatesStepExecutionId) {
        this(runId, stepExecutionId, stepName, attempt, input, compensatesStepExecutionId, Map.of());
    }

    /** The output of an earlier step, or an empty map if it produced none or has not completed. */
    public Map<String, Object> outputOf(String step) {
        return outputs.getOrDefault(step, Map.of());
    }

    /**
     * A value an earlier step produced, required by this one.
     *
     * @throws StepFailureException.Permanent if it is missing: the workflow is wired wrongly, retrying cannot help
     */
    public Object requireOutput(String step, String key) {
        Object value = outputOf(step).get(key);
        if (value == null) {
            throw new StepFailureException.Permanent("Step " + stepName + " needs '" + key + "' from step " + step
                    + ", which has not produced it");
        }
        return value;
    }
}
