package io.github.louaychayeb.flowforge.orchestrator.run;

/**
 * Lifecycle of a {@link WorkflowRun}.
 *
 * <ul>
 *   <li>{@code RUNNING → COMPLETED} — every step completed</li>
 *   <li>{@code RUNNING → FAILED} — a step failed and no completed step needs compensating</li>
 *   <li>{@code RUNNING → COMPENSATING} — a step failed; completed steps are being undone in reverse order</li>
 *   <li>{@code COMPENSATING → COMPENSATED} — every compensation succeeded</li>
 *   <li>{@code COMPENSATING → COMPENSATION_FAILED} — a compensation failed; needs manual attention</li>
 * </ul>
 */
public enum RunStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    COMPENSATING,
    COMPENSATED,
    COMPENSATION_FAILED;

    public boolean isTerminal() {
        return this != RUNNING && this != COMPENSATING;
    }

    public boolean canTransitionTo(RunStatus target) {
        return switch (this) {
            case RUNNING -> target == COMPLETED || target == FAILED || target == COMPENSATING;
            case COMPENSATING -> target == COMPENSATED || target == COMPENSATION_FAILED;
            case COMPLETED, FAILED, COMPENSATED, COMPENSATION_FAILED -> false;
        };
    }
}
