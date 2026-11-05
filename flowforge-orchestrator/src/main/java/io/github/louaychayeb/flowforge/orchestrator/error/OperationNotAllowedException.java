package io.github.louaychayeb.flowforge.orchestrator.error;

import java.util.Map;

/**
 * A valid request that the target's current state does not allow — cancelling a run that already finished,
 * retrying a run that is not stuck. Maps to {@code 409 Conflict}: the request may succeed later, in another state.
 */
public class OperationNotAllowedException extends FlowForgeException {

    public OperationNotAllowedException(String message, Map<String, ?> details) {
        super(ErrorCode.OPERATION_NOT_ALLOWED, message, details);
    }
}
