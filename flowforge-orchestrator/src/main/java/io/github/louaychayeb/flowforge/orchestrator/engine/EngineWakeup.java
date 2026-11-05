package io.github.louaychayeb.flowforge.orchestrator.engine;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Asks the dispatch and outbox loops for a pass as soon as there is work for them, instead of leaving it to their
 * next tick.
 *
 * <p>Without it a step waits on average half a dispatch interval to be claimed and half an outbox interval to be
 * sent, at every step: a five-step run took ~2.5 s at light load, almost all of it waiting. Polling faster cut that
 * but cost a third of the peak throughput, because every tick is a transaction whether or not there is work
 * (see docs/performance.md).
 *
 * <p>Requests are made only after the transaction that made the work visible has committed, and go to the same
 * single runner per loop that the ticks use ({@link EngineLoops}), so under load they merge into the pass that is
 * already draining. A request waits {@code wakeup-linger} first, so that work appearing close together starts one
 * pass. The ticks in {@link EngineScheduler} stay as the safety net. Disabled together with the scheduler, so tests
 * that drive the loops themselves see no background activity.
 */
@Component
@ConditionalOnProperty(name = "flowforge.engine.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class EngineWakeup {

    private final EngineLoops loops;
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "flowforge-wakeup-timer");
        thread.setDaemon(true);
        return thread;
    });
    private final Clock clock;
    private final boolean enabled;
    private final long lingerMillis;

    public EngineWakeup(EngineLoops loops, Clock clock, EngineProperties properties) {
        this.loops = loops;
        this.clock = clock;
        this.enabled = properties.wakeup();
        this.lingerMillis = properties.wakeupLinger().toMillis();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onStepsMayBeReady(EngineEvents.StepsMayBeReady event) {
        if (!enabled) {
            return;
        }
        long delayMillis = Duration.between(clock.instant(), event.at()).toMillis();
        if (delayMillis <= 0) {
            loops.dispatch().wakeUp(lingerMillis);
        } else {
            // A retry backoff: ask for a pass the moment it ends rather than at the next tick after it.
            timers.schedule(() -> loops.dispatch().wakeUp(lingerMillis), delayMillis, TimeUnit.MILLISECONDS);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCommandsWritten(EngineEvents.CommandsWritten event) {
        if (enabled) {
            loops.publish().wakeUp(lingerMillis);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    @PreDestroy
    void stop() {
        timers.shutdownNow();
    }
}
