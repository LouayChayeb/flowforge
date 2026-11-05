package io.github.louaychayeb.flowforge.orchestrator.definition;

import io.github.louaychayeb.flowforge.orchestrator.error.ErrorCode;
import io.github.louaychayeb.flowforge.orchestrator.error.FlowForgeException;

import java.util.Map;

/**
 * A workflow definition that cannot be registered: duplicate step names, a dependency on an unknown or later step,
 * an invalid wait duration. Reported as {@code 400} with the offending step in the details.
 */
public class InvalidWorkflowDefinitionException extends FlowForgeException {

    public InvalidWorkflowDefinitionException(String message) {
        super(ErrorCode.INVALID_WORKFLOW_DEFINITION, message);
    }

    public InvalidWorkflowDefinitionException(String message, String step) {
        super(ErrorCode.INVALID_WORKFLOW_DEFINITION, message, Map.of("step", step));
    }
}
