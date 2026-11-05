package io.github.louaychayeb.flowforge.common.step;

/**
 * Thrown by a {@link StepHandler} to fail the current attempt with a clear classification, instead of returning a
 * failure result. Handlers usually discover a failure deep inside a call stack — a client, a parser, a validator —
 * and an exception carries it out with its cause attached; {@link StepCommandProcessor} turns it into the right
 * result.
 *
 * <ul>
 *   <li>{@link Retryable} — the same attempt might succeed later: a timeout, an unavailable service;</li>
 *   <li>{@link Permanent} — retrying cannot help: a declined card, invalid data, a refused shipment;</li>
 *   <li>{@link InvalidInput} — a permanent failure caused by the run's input.</li>
 * </ul>
 *
 * Any other exception escaping a handler is treated as retryable, since it most likely means a transient fault
 * or a bug that a redeployment will fix — but it is logged as unexpected.
 */
public abstract sealed class StepFailureException extends RuntimeException
        permits StepFailureException.Retryable, StepFailureException.Permanent {

    protected StepFailureException(String message, Throwable cause) {
        super(message, cause);
    }

    public abstract boolean isRetryable();

    /** The attempt failed, but a later attempt may succeed. */
    public static non-sealed class Retryable extends StepFailureException {

        public Retryable(String message) {
            this(message, null);
        }

        public Retryable(String message, Throwable cause) {
            super(message, cause);
        }

        @Override
        public boolean isRetryable() {
            return true;
        }
    }

    /** The step can never succeed with this input; the run fails or compensates. */
    public static non-sealed class Permanent extends StepFailureException {

        public Permanent(String message) {
            this(message, null);
        }

        public Permanent(String message, Throwable cause) {
            super(message, cause);
        }

        @Override
        public boolean isRetryable() {
            return false;
        }
    }

    /** The run's input is missing or malformed for this step. */
    public static final class InvalidInput extends Permanent {

        public InvalidInput(String message) {
            super(message);
        }
    }
}
