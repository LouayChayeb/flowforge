package io.github.louaychayeb.flowforge.orchestrator.run;

import io.github.louaychayeb.flowforge.orchestrator.error.ErrorCode;
import io.github.louaychayeb.flowforge.orchestrator.error.FlowForgeException;

import java.util.Map;
import java.util.UUID;

/**
 * Thrown when an outcome is reported for an attempt that is no longer the step's active attempt, for example a
 * late result from an attempt that has since timed out, or a duplicate of a result already applied. Expected under
 * at-least-once delivery: the result listener counts it and drops the message, it is not an error.
 */
public class StaleAttemptException extends FlowForgeException {

    private final UUID stepExecutionId;
    private final int reportedAttempt;
    private final int currentAttempt;
    private final StepStatus status;

    public StaleAttemptException(UUID stepExecutionId, int reportedAttempt, int currentAttempt, StepStatus status) {
        super(ErrorCode.STALE_ATTEMPT,
                "Stale outcome for step %s: reported attempt %d, current attempt %d in status %s"
                        .formatted(stepExecutionId, reportedAttempt, currentAttempt, status),
                Map.of("stepExecutionId", stepExecutionId, "reportedAttempt", reportedAttempt,
                        "currentAttempt", currentAttempt, "stepStatus", status));
        this.stepExecutionId = stepExecutionId;
        this.reportedAttempt = reportedAttempt;
        this.currentAttempt = currentAttempt;
        this.status = status;
    }

    public UUID stepExecutionId() {
        return stepExecutionId;
    }

    public int reportedAttempt() {
        return reportedAttempt;
    }

    public int currentAttempt() {
        return currentAttempt;
    }

    public StepStatus status() {
        return status;
    }
}
