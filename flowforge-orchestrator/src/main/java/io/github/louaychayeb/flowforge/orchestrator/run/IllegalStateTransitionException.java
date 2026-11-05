package io.github.louaychayeb.flowforge.orchestrator.run;

import io.github.louaychayeb.flowforge.orchestrator.error.ErrorCode;
import io.github.louaychayeb.flowforge.orchestrator.error.FlowForgeException;

import java.util.Map;

/**
 * Thrown when code attempts a state change the state machine does not allow, for example moving a
 * {@code COMPLETED} step back to {@code RUNNING}. Inside the engine this signals a bug or a message that should
 * have been rejected earlier; the surrounding transaction rolls back and nothing is changed.
 */
public class IllegalStateTransitionException extends FlowForgeException {

    public IllegalStateTransitionException(String message) {
        super(ErrorCode.ILLEGAL_STATE_TRANSITION, message);
    }

    public IllegalStateTransitionException(String message, Map<String, ?> details) {
        super(ErrorCode.ILLEGAL_STATE_TRANSITION, message, details);
    }
}
