package io.github.louaychayeb.flowforge.orchestrator.engine;

import io.github.louaychayeb.flowforge.common.step.StepContext;

import java.util.Map;
import java.util.UUID;

/**
 * A step attempt that has been claimed (moved to {@code RUNNING}) and is ready to execute.
 * Detached from the persistence context so it can be executed outside a transaction.
 */
public record ClaimedStep(
        UUID runId,
        UUID stepExecutionId,
        String stepName,
        String stepType,
        int attempt,
        Map<String, Object> input
) {

    public StepContext toContext() {
        return new StepContext(runId, stepExecutionId, stepName, attempt, input);
    }
}
