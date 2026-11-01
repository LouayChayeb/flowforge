package io.github.louaychayeb.flowforge.common.messaging;

/**
 * How a step attempt ended, as reported by a worker.
 */
public enum StepOutcome {
    /** The step completed successfully. */
    SUCCEEDED,
    /** The step failed but may succeed if retried (timeout, unavailable dependency). */
    RETRYABLE_FAILURE,
    /** The step failed and retrying will not help (validation error, payment declined). */
    PERMANENT_FAILURE
}
