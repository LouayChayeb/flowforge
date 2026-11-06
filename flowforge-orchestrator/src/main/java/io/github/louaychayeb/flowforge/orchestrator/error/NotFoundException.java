package io.github.louaychayeb.flowforge.orchestrator.error;

import java.util.Map;
import java.util.UUID;

/**
 * Something the request refers to does not exist. Subclasses say what, so callers and logs can tell a missing
 * workflow from a missing run without reading messages.
 */
public abstract class NotFoundException extends FlowForgeException {

    protected NotFoundException(ErrorCode code, String message, Map<String, ?> details) {
        super(code, message, details);
    }

    public static final class WorkflowNotFound extends NotFoundException {
        public WorkflowNotFound(String name) {
            super(ErrorCode.WORKFLOW_NOT_FOUND, "Workflow " + name + " not found", Map.of("workflow", name));
        }
    }

    public static final class RunNotFound extends NotFoundException {
        public RunNotFound(UUID runId) {
            super(ErrorCode.RUN_NOT_FOUND, "Run " + runId + " not found", Map.of("runId", runId));
        }
    }

    public static final class StepNotFound extends NotFoundException {
        public StepNotFound(UUID stepExecutionId) {
            super(ErrorCode.STEP_NOT_FOUND, "Step execution " + stepExecutionId + " not found",
                    Map.of("stepExecutionId", stepExecutionId));
        }

        /** A run has no step of that name (of the kind asked for, e.g. no signal step). */
        public StepNotFound(UUID runId, String stepName) {
            super(ErrorCode.STEP_NOT_FOUND, "Run " + runId + " has no signal step " + stepName,
                    Map.of("runId", runId, "step", stepName));
        }
    }
}
