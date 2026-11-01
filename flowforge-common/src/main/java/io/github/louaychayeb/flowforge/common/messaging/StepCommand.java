package io.github.louaychayeb.flowforge.common.messaging;

import java.util.Map;
import java.util.UUID;

/**
 * Instruction from the orchestrator asking a worker to execute one attempt of a step.
 * Published to {@link Topics#STEP_COMMANDS}, keyed by {@code runId}.
 *
 * @param eventId          unique id of this message, used for de-duplication
 * @param runId            the workflow run the step belongs to
 * @param stepExecutionId  stable id of the logical step; identical across retries
 * @param stepName         the step's name within the workflow definition
 * @param stepType         which handler should execute the step, e.g. {@code CHARGE_PAYMENT}
 * @param attempt          attempt number, starting at 1; results from older attempts are stale
 * @param input            the run's input
 */
public record StepCommand(
        UUID eventId,
        UUID runId,
        UUID stepExecutionId,
        String stepName,
        String stepType,
        int attempt,
        Map<String, Object> input
) {
}
