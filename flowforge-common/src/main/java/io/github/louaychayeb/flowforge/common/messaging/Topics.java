package io.github.louaychayeb.flowforge.common.messaging;

/**
 * Kafka topics shared by the orchestrator and the workers. Both are keyed by run id, so all
 * messages of one run go to the same partition and keep their order.
 */
public final class Topics {

    /** Orchestrator → workers: {@link StepCommand}. */
    public static final String STEP_COMMANDS = "flowforge.step-commands";

    /** Workers → orchestrator: {@link StepResult}. */
    public static final String STEP_RESULTS = "flowforge.step-results";

    private Topics() {
    }
}
