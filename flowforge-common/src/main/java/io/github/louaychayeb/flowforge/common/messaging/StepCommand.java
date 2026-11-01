package io.github.louaychayeb.flowforge.common.messaging;

import java.util.UUID;

/**
 * Instruction from the orchestrator asking a worker to execute one attempt of a step.
 *
 * @param eventId          unique id of this message, used for de-duplication
 * @param runId            the workflow run the step belongs to
 * @param stepExecutionId  stable id of the logical step; identical across retries
 * @param stepType         which handler should execute the step, e.g. {@code CHARGE_PAYMENT}
 * @param attempt          attempt number, starting at 1; results from older attempts are stale
 */
public record StepCommand(
        UUID eventId,
        UUID runId,
        UUID stepExecutionId,
        String stepType,
        int attempt
) {
}
