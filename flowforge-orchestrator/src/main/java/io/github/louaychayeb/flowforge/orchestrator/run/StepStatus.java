package io.github.louaychayeb.flowforge.orchestrator.run;

/**
 * Lifecycle of a {@link StepExecution}.
 *
 * <ul>
 *   <li>{@code PENDING → RUNNING} — the step is claimed for its first attempt</li>
 *   <li>{@code RUNNING → COMPLETED} — the attempt succeeded</li>
 *   <li>{@code RUNNING → RETRY_WAIT} — the attempt failed and attempts remain</li>
 *   <li>{@code RETRY_WAIT → RUNNING} — the backoff elapsed and the step is claimed again</li>
 *   <li>{@code RUNNING → FAILED} — permanent failure, or no attempts remain</li>
 * </ul>
 *
 * {@code COMPLETED} and {@code FAILED} are terminal: no message, however late or duplicated,
 * can move a step out of them.
 */
public enum StepStatus {
    PENDING,
    RUNNING,
    RETRY_WAIT,
    COMPLETED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }

    public boolean canTransitionTo(StepStatus target) {
        return switch (this) {
            case PENDING, RETRY_WAIT -> target == RUNNING;
            case RUNNING -> target == COMPLETED || target == RETRY_WAIT || target == FAILED;
            case COMPLETED, FAILED -> false;
        };
    }
}
