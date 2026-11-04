package io.github.louaychayeb.flowforge.orchestrator.outbox;

import io.github.louaychayeb.flowforge.orchestrator.engine.EngineMetrics;
import io.github.louaychayeb.flowforge.orchestrator.engine.EngineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
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
    private final Clock clock;

    public OutboxPublisher(OutboxEventRepository events, KafkaTemplate<String, Object> kafka,
                           EngineProperties properties, EngineMetrics metrics, Clock clock) {
        this.events = events;
        this.kafka = kafka;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Publishes one batch of pending events. Stops at the first failure so a broker outage does not
     * produce a burst of timeouts; the failed event stays pending and is retried on the next call.
     *
     * @return the number of events published
     */
    @Transactional
    public int publishPending() {
        List<OutboxEvent> pending = events.lockPending(properties.batchSize());
        int published = 0;
        for (OutboxEvent event : pending) {
            try {
                kafka.send(event.getTopic(), event.getMessageKey(), event.getPayload())
                        .get(properties.publishTimeout().toMillis(), TimeUnit.MILLISECONDS);
                event.markPublished(clock.instant());
                published++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                event.recordFailure("Interrupted while publishing");
                metrics.outboxFailure();
                break;
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                log.warn("Failed to publish outbox event {} to {}; will retry", event.getId(), event.getTopic(), e);
                event.recordFailure(e.getClass().getSimpleName() + ": " + e.getMessage());
                metrics.outboxFailure();
                break;
            }
        }
        metrics.outboxPublished(published);
        return published;
    }
}
