package io.github.louaychayeb.flowforge.orchestrator.run;

/**
 * Lifecycle of a {@link WorkflowRun}.
 *
 * <ul>
 *   <li>{@code RUNNING → COMPLETED} — every step completed</li>
 *   <li>{@code RUNNING → FAILED} — a step failed and no completed step needs compensating</li>
 *   <li>{@code RUNNING → COMPENSATING} — a step failed (or the run was cancelled); completed steps are being undone</li>
 *   <li>{@code COMPENSATING → COMPENSATED} — every compensation succeeded</li>
 *   <li>{@code COMPENSATING → COMPENSATION_FAILED} — a compensation failed; needs manual attention</li>
 *   <li>{@code RUNNING / COMPENSATING → CANCELLED} — an operator cancelled the run and everything that needed undoing
 *       was undone</li>
 * </ul>
 *
 * The engine never leaves a terminal state. The single exception is an explicit, audited operator action:
 * retrying a {@code COMPENSATION_FAILED} run moves it back to {@code COMPENSATING} — see
 * {@link #canBeReopenedByOperator()}.
 */
public enum RunStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    COMPENSATING,
    COMPENSATED,
    COMPENSATION_FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this != RUNNING && this != COMPENSATING;
    }

    /** Transitions the engine may make on its own. */
    public boolean canTransitionTo(RunStatus target) {
        return switch (this) {
            case RUNNING -> target == COMPLETED || target == FAILED || target == COMPENSATING || target == CANCELLED;
            case COMPENSATING -> target == COMPENSATED || target == COMPENSATION_FAILED || target == CANCELLED;
            case COMPLETED, FAILED, COMPENSATED, COMPENSATION_FAILED, CANCELLED -> false;
        };
    }

    /** Whether an operator may send a run in this state back to {@code COMPENSATING} to retry what failed. */
    public boolean canBeReopenedByOperator() {
        return this == COMPENSATION_FAILED;
    }
}
