package io.github.louaychayeb.flowforge.orchestrator.outbox;

import io.github.louaychayeb.flowforge.orchestrator.engine.EngineMetrics;
import io.github.louaychayeb.flowforge.orchestrator.engine.EngineProperties;
import io.github.louaychayeb.flowforge.orchestrator.engine.RunTracing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends pending {@link OutboxEvent}s to Kafka and marks them published.
 *
 * <p>Delivery is at-least-once: if the process dies after Kafka acknowledged a message but before this
 * transaction commits, the event is sent again on restart. Consumers must therefore tolerate duplicates —
 * the orchestrator does, by rejecting results for attempts that are no longer active.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository events;
    private final KafkaTemplate<String, Object> kafka;
    private final EngineProperties properties;
    private final EngineMetrics metrics;
    private final RunTracing tracing;
    private final Clock clock;

    public OutboxPublisher(OutboxEventRepository events, KafkaTemplate<String, Object> kafka,
                           EngineProperties properties, EngineMetrics metrics, RunTracing tracing, Clock clock) {
        this.events = events;
        this.kafka = kafka;
        this.properties = properties;
        this.metrics = metrics;
        this.tracing = tracing;
        this.clock = clock;
    }

    /**
     * Publishes one batch of pending events. All sends are issued first and the acknowledgements awaited
     * afterwards, so a batch costs one broker round trip rather than one per event (Phase 5 found the
     * one-at-a-time version capping throughput). Events whose send failed or timed out stay pending and are
     * retried on the next call; the others are marked published.
     *
     * <p>Order is still kept where it matters: events of one run share a key, hence a partition, and the
     * idempotent producer preserves order per partition.
     *
     * @return the number of events published
     */
    @Transactional
    public int publishPending() {
        List<OutboxEvent> pending = events.lockPending(properties.batchSize());
        List<CompletableFuture<?>> sends = new ArrayList<>(pending.size());
        for (OutboxEvent event : pending) {
            try {
                // Inside the attempt's trace, so the producer puts it in the Kafka headers for the worker.
                sends.add(tracing.inTrace(event.getTraceParent(), "outbox publish",
                        () -> kafka.send(event.getTopic(), event.getMessageKey(), event.getPayload())));
            } catch (RuntimeException e) {
                sends.add(CompletableFuture.failedFuture(e));
            }
        }
        long deadline = System.nanoTime() + properties.publishTimeout().toNanos();
        int published = 0;
        for (int i = 0; i < pending.size(); i++) {
            OutboxEvent event = pending.get(i);
            try {
                sends.get(i).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                event.markPublished(clock.instant());
                published++;
            } catch (InterruptedException e) {
                // The remaining events stay pending; Kafka may still deliver them, which consumers tolerate.
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException | TimeoutException e) {
                Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                log.warn("Failed to publish outbox event {} to {}; will retry", event.getId(), event.getTopic(), cause);
                event.recordFailure(cause.getClass().getSimpleName() + ": " + cause.getMessage());
                metrics.outboxFailure();
            }
        }
        metrics.outboxPublished(published);
        return published;
    }
}
