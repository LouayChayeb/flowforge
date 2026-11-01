package io.github.louaychayeb.flowforge.orchestrator.engine;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls for eligible steps in the background. Disable with {@code flowforge.engine.dispatcher.enabled=false}
 * (the integration tests do, and drive {@link StepDispatcher} directly).
 */
@Component
@ConditionalOnProperty(name = "flowforge.engine.dispatcher.enabled", havingValue = "true", matchIfMissing = true)
public class DispatcherScheduler {

    private final StepDispatcher dispatcher;

    public DispatcherScheduler(StepDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${flowforge.engine.dispatcher.poll-interval-ms:500}")
    public void poll() {
        // Keep draining while there is work, then wait for the next poll.
        int claimed;
        do {
            claimed = dispatcher.dispatchOnce();
        } while (claimed > 0);
    }
}
