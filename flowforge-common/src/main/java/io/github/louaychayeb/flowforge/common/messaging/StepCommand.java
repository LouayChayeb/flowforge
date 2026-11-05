package io.github.louaychayeb.flowforge.common.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Instruction from the orchestrator asking a worker to execute one attempt of a step.
 * Published to {@link Topics#STEP_COMMANDS}, keyed by {@code runId}.
 *
 * @param eventId                    unique id of this message, used for de-duplication
 * @param runId                      the workflow run the step belongs to
 * @param stepExecutionId            stable id of the logical step; identical across retries
 * @param stepName                   the step's name within the workflow definition
 * @param stepType                   which handler should execute the step, e.g. {@code CHARGE_PAYMENT}
 * @param attempt                    attempt number, starting at 1; results from older attempts are stale
 * @param input                      the run's input
 * @param deadline                   when the attempt's lease expires; after that the orchestrator times the
 *                                   attempt out, so a worker that receives the command later skips it instead
 *                                   of doing wasted work. {@code null} means no deadline
 * @param compensatesStepExecutionId for a compensation step, the id of the step it undoes — the idempotency
 *                                   key of the original side effect, e.g. the charge to refund. Otherwise
 *                                   {@code null}
 */
public record StepCommand(
        UUID eventId,
        UUID runId,
        UUID stepExecutionId,
        String stepName,
        String stepType,
        int attempt,
        Map<String, Object> input,
        Instant deadline,
        UUID compensatesStepExecutionId
) {

    public StepCommand(UUID eventId, UUID runId, UUID stepExecutionId, String stepName, String stepType, int attempt,
                       Map<String, Object> input, Instant deadline) {
        this(eventId, runId, stepExecutionId, stepName, stepType, attempt, input, deadline, null);
    }
}
