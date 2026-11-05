package io.github.louaychayeb.flowforge.orchestrator.engine;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The engine's timer ticks. Each asks the loop's single runner ({@link EngineLoops}) for a pass; if one is already
 * running — started by a wake-up or an earlier tick — the tick costs nothing. With wake-ups on, the ticks are the
 * safety net; with them off, the ticks drive everything.
 *
 * <p>Each loop is safe to run on several orchestrator instances at once, because every one of them locks its rows
 * with {@code SKIP LOCKED}.
 *
 * <p>Disable with {@code flowforge.engine.scheduler.enabled=false}; the integration tests do, and drive the same
 * methods directly.
 */
@Component
@ConditionalOnProperty(name = "flowforge.engine.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class EngineScheduler {

    private final EngineLoops loops;

    public EngineScheduler(EngineLoops loops) {
        this.loops = loops;
    }

    @Scheduled(fixedDelayString = "${flowforge.engine.scheduler.dispatch-interval-ms:500}")
    public void dispatch() {
        loops.dispatch().request(0);
    }

    @Scheduled(fixedDelayString = "${flowforge.engine.scheduler.outbox-interval-ms:200}")
    public void publishOutbox() {
        loops.publish().request(0);
    }

    @Scheduled(fixedDelayString = "${flowforge.engine.scheduler.recovery-interval-ms:5000}")
    public void recoverExpiredLeases() {
        loops.recover().request(0);
    }
}
