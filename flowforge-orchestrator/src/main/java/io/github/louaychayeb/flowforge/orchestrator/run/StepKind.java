package io.github.louaychayeb.flowforge.orchestrator.run;

/**
 * Whether a {@link StepExecution} moves the workflow forward or undoes an earlier step.
 */
public enum StepKind {
    FORWARD,
    COMPENSATION
}
