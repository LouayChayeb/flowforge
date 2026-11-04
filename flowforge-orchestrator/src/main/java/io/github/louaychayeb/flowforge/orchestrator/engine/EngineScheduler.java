package io.github.louaychayeb.flowforge.orchestrator.engine;

import io.github.louaychayeb.flowforge.orchestrator.outbox.OutboxPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.function.IntSupplier;

/**
 * The engine's background loops. Each is safe to run on several orchestrator instances at once, because
 * every one of them locks its rows with {@code SKIP LOCKED}.
 *
 * <p>Disable with {@code flowforge.engine.scheduler.enabled=false}; the integration tests do, and drive
 * the same methods directly.
 */
@Component
@ConditionalOnProperty(name = "flowforge.engine.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class EngineScheduler {

    private final StepDispatcher dispatcher;
    private final OutboxPublisher outboxPublisher;
    private final StepStateService stateService;
    private final Clock clock;

    public EngineScheduler(StepDispatcher dispatcher, OutboxPublisher outboxPublisher, StepStateService stateService,
                           Clock clock) {
        this.dispatcher = dispatcher;
        this.outboxPublisher = outboxPublisher;
        this.stateService = stateService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${flowforge.engine.scheduler.dispatch-interval-ms:500}")
    public void dispatch() {
        drain(dispatcher::dispatchOnce);
    }

    @Scheduled(fixedDelayString = "${flowforge.engine.scheduler.outbox-interval-ms:200}")
    public void publishOutbox() {
        drain(outboxPublisher::publishPending);
    }

    @Scheduled(fixedDelayString = "${flowforge.engine.scheduler.recovery-interval-ms:5000}")
    public void recoverExpiredLeases() {
        drain(() -> stateService.recoverExpiredLeases(clock.instant()));
    }

    /** Keeps working while there is work, then waits for the next tick. */
    private static void drain(IntSupplier batch) {
        int handled;
        do {
            handled = batch.getAsInt();
        } while (handled > 0);
    }
}
