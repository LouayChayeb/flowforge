package io.github.louaychayeb.flowforge.orchestrator.run;

/**
 * Lifecycle of a {@link WorkflowRun}. {@code COMPLETED} and {@code FAILED} are terminal.
 */
public enum RunStatus {
    RUNNING,
    COMPLETED,
    FAILED;

    public boolean isTerminal() {
        return this != RUNNING;
    }

    public boolean canTransitionTo(RunStatus target) {
        return this == RUNNING && target.isTerminal();
    }
}
