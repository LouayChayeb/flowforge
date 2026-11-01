package io.github.louaychayeb.flowforge.orchestrator.run;

/**
 * Outcome of a single {@link StepAttempt}.
 */
public enum AttemptStatus {
    RUNNING,
    SUCCEEDED,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE
}
