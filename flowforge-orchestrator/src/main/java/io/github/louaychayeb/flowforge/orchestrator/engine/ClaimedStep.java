package io.github.louaychayeb.flowforge.orchestrator.engine;

import io.github.louaychayeb.flowforge.common.messaging.StepCommand;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A step attempt that has been claimed (moved to {@code RUNNING}) and is ready to be sent to a worker.
 * Detached from the persistence context so it can be published outside a transaction.
 *
 * @param compensatesStepExecutionId for a compensation step, the step it undoes; otherwise {@code null}
 * @param outputs                    what the run's completed steps produced, by step name
 */
public record ClaimedStep(
        UUID runId,
        UUID stepExecutionId,
        String stepName,
        String stepType,
        int attempt,
        Map<String, Object> input,
        Instant leaseExpiresAt,
        UUID compensatesStepExecutionId,
        Map<String, Map<String, Object>> outputs
) {

    public StepCommand toCommand() {
        return new StepCommand(UUID.randomUUID(), runId, stepExecutionId, stepName, stepType, attempt, input,
                leaseExpiresAt, compensatesStepExecutionId, outputs);
    }
}
