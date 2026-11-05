package io.github.louaychayeb.flowforge.orchestrator.engine;

import io.github.louaychayeb.flowforge.orchestrator.outbox.OutboxPublisher;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;

/**
 * The engine's three loops — dispatch, outbox, recovery — each with exactly one runner per orchestrator instance.
 * Timer ticks ({@link EngineScheduler}) and wake-ups ({@link EngineWakeup}) only <em>request</em> a pass: if a pass
 * is already running, the request just makes it go round once more instead of starting a second one.
 *
 * <p>With wake-ups and ticks each running their own passes, an instance under load had two dispatchers and two
 * publishers competing for the same rows, each taking small batches. With one runner per loop a request during a
 * running pass costs nothing, and a busy loop leaves wake-ups to its ticks so it keeps taking full batches; the
 * measurements behind this design are in docs/performance.md ("Instant dispatch").
 */
@Component
@ConditionalOnProperty(name = "flowforge.engine.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class EngineLoops {

    private static final Logger log = LoggerFactory.getLogger(EngineLoops.class);

    private final Loop dispatch;
    private final Loop publish;
    private final Loop recover;

    public EngineLoops(StepDispatcher dispatcher, OutboxPublisher outboxPublisher, StepStateService stateService,
                       EngineProperties properties, Clock clock) {
        int busy = properties.batchSize();
        this.dispatch = new Loop("flowforge-dispatch", dispatcher::dispatchOnce, busy);
        this.publish = new Loop("flowforge-outbox", outboxPublisher::publishPending, busy);
        this.recover = new Loop("flowforge-recovery", () -> stateService.recoverExpiredLeases(clock.instant()), busy);
    }

    public Loop dispatch() {
        return dispatch;
    }

    public Loop publish() {
        return publish;
    }

    public Loop recover() {
        return recover;
    }

    @PreDestroy
    void stop() {
        dispatch.stop();
        publish.stop();
        recover.stop();
    }

    /**
     * One loop: a single thread, at most one pass running and at most one more owed.
     *
     * <p>It also decides whether a wake-up is worth it. After a pass that handled at least a full batch the loop is
     * <em>busy</em>: work is arriving faster than one transaction at a time, and the best thing for throughput is to
     * let it pile up for the next tick and take it in full batches — every transaction costs a commit, and on a node
     * with slow disk syncs that dominates. So a busy loop ignores wake-ups and runs on its ticks; as soon as a pass
     * finds less than a batch, it is quiet again and wake-ups run at once. Light load gets the latency of immediate
     * dispatch, heavy load the throughput of batching, and the switch needs no tuning.
     */
    public static final class Loop {

        private final ScheduledExecutorService executor;
        private final IntSupplier batch;
        private final int busyThreshold;
        private final AtomicBoolean scheduled = new AtomicBoolean();
        private final AtomicBoolean again = new AtomicBoolean();
        private volatile boolean busy;

        Loop(String name, IntSupplier batch, int busyThreshold) {
            this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, name);
                thread.setDaemon(true);
                return thread;
            });
            this.batch = batch;
            this.busyThreshold = Math.max(1, busyThreshold);
        }

        /** A wake-up: runs after {@code delayMillis}, unless the loop is busy and its next tick will batch better. */
        public void wakeUp(long delayMillis) {
            if (!busy) {
                request(delayMillis);
            }
        }

        /** Whether the last pass handled at least a full batch, so wake-ups are being left to the ticks. */
        public boolean isBusy() {
            return busy;
        }

        /**
         * Asks for a pass after {@code delayMillis}. If a pass is already scheduled or running, it is told to go round
         * once more instead, so requests never pile up and two passes never run at once.
         */
        public void request(long delayMillis) {
            again.set(true);
            if (scheduled.compareAndSet(false, true)) {
                try {
                    executor.schedule(this::run, delayMillis, TimeUnit.MILLISECONDS);
                } catch (RuntimeException e) {
                    scheduled.set(false); // shutting down
                }
            }
        }

        private void run() {
            int total = 0;
            try {
                while (again.getAndSet(false)) {
                    int handled;
                    do {
                        handled = batch.getAsInt();
                        total += handled;
                    } while (handled > 0);
                }
                busy = total >= busyThreshold;
            } catch (RuntimeException e) {
                log.warn("Engine pass failed; the next tick retries", e);
            } finally {
                scheduled.set(false);
                // A request that arrived between the last check and clearing the flag must not be lost.
                if (again.get()) {
                    request(0);
                }
            }
        }

        void stop() {
            executor.shutdownNow();
        }
    }
}
