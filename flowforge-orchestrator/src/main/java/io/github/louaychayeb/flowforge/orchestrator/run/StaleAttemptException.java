package io.github.louaychayeb.flowforge.orchestrator.run;

import java.util.UUID;

/**
 * Thrown when an outcome is reported for an attempt that is no longer the step's active attempt,
 * for example a late result from an attempt that has since been superseded by a retry.
 */
public class StaleAttemptException extends RuntimeException {

    public StaleAttemptException(UUID stepExecutionId, int reportedAttempt, int currentAttempt, StepStatus status) {
        super("Stale outcome for step %s: reported attempt %d, current attempt %d in status %s"
                .formatted(stepExecutionId, reportedAttempt, currentAttempt, status));
    }
}
