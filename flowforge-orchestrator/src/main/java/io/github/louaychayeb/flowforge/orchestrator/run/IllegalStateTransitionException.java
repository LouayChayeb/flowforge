package io.github.louaychayeb.flowforge.orchestrator.run;

/**
 * Thrown when code attempts a state change the state machine does not allow,
 * for example moving a {@code COMPLETED} step back to {@code RUNNING}.
 */
public class IllegalStateTransitionException extends RuntimeException {

    public IllegalStateTransitionException(String message) {
        super(message);
    }
}
