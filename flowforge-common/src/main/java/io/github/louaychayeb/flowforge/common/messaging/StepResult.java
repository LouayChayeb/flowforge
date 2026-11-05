package io.github.louaychayeb.flowforge.common.messaging;

import java.util.Map;
import java.util.UUID;

/**
 * Outcome of one step attempt, reported by a worker back to the orchestrator.
 *
 * @param eventId          unique id of this message, used for de-duplication
 * @param runId            the workflow run the step belongs to
 * @param stepExecutionId  stable id of the logical step
 * @param attempt          the attempt this result answers
 * @param outcome          what happened
 * @param errorMessage     failure details, {@code null} on success
 * @param output           data the step produced for later steps; empty unless it succeeded
 */
public record StepResult(
        UUID eventId,
        UUID runId,
        UUID stepExecutionId,
        int attempt,
        StepOutcome outcome,
        String errorMessage,
        Map<String, Object> output
) {

    public StepResult {
        output = output == null ? Map.of() : output;
    }

    public StepResult(UUID eventId, UUID runId, UUID stepExecutionId, int attempt, StepOutcome outcome,
                      String errorMessage) {
        this(eventId, runId, stepExecutionId, attempt, outcome, errorMessage, Map.of());
    }
}
