package io.github.louaychayeb.flowforge.orchestrator.engine;

import io.github.louaychayeb.flowforge.common.messaging.StepCommand;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A step attempt that has been claimed (moved to {@code RUNNING}) and is ready to be sent to a worker.
 * Detached from the persistence context so it can be published outside a transaction.
 */
public record ClaimedStep(
        UUID runId,
        UUID stepExecutionId,
        String stepName,
        String stepType,
        int attempt,
        Map<String, Object> input,
        Instant leaseExpiresAt
) {

    public StepCommand toCommand() {
        return new StepCommand(UUID.randomUUID(), runId, stepExecutionId, stepName, stepType, attempt, input,
                leaseExpiresAt);
    }
}
