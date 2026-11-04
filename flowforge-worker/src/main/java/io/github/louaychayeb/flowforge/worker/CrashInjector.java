package io.github.louaychayeb.flowforge.worker;

import io.github.louaychayeb.flowforge.common.messaging.StepCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Failure injection for the recovery demo. When {@code flowforge.worker.crash-after-step-type} names a step
 * type, the worker process dies abruptly right after executing such a step and before reporting its result:
 * the side effect happened, but neither Kafka nor the orchestrator knows. Disabled unless set.
 */
@Component
public class CrashInjector {

    private static final Logger log = LoggerFactory.getLogger(CrashInjector.class);

    private final String crashAfterStepType;

    public CrashInjector(@Value("${flowforge.worker.crash-after-step-type:}") String crashAfterStepType) {
        this.crashAfterStepType = crashAfterStepType;
        if (!crashAfterStepType.isBlank()) {
            log.warn("Crash injection enabled: this worker will die after executing a {} step", crashAfterStepType);
        }
    }

    void afterExecute(StepCommand command) {
        if (command.stepType().equals(crashAfterStepType)) {
            log.error("CRASH INJECTED after executing step {} ({}) attempt {} of run {} — result not reported",
                    command.stepName(), command.stepType(), command.attempt(), command.runId());
            // halt, not exit: no shutdown hooks, no graceful consumer close, no offset commit — like kill -9.
            Runtime.getRuntime().halt(137);
        }
    }
}
