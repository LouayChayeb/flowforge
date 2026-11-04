package io.github.louaychayeb.flowforge.orchestrator.outbox;

import io.github.louaychayeb.flowforge.orchestrator.engine.EngineMetrics;
import io.github.louaychayeb.flowforge.orchestrator.engine.EngineProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
    private final OutboxPublisher publisher = new OutboxPublisher(repository, kafka,
            new EngineProperties(10, Duration.ofSeconds(1), Duration.ofSeconds(60),
                    new EngineProperties.Retry(Duration.ofSeconds(1), Duration.ofSeconds(10), 2.0)),
            new EngineMetrics(new SimpleMeterRegistry()), Clock.fixed(NOW, ZoneOffset.UTC));

    private final OutboxEvent first = new OutboxEvent("topic", "run-1", Map.of("n", 1), NOW);
    private final OutboxEvent second = new OutboxEvent("topic", "run-2", Map.of("n", 2), NOW);

    @Test
    void marksAcknowledgedEventsPublished() {
        when(repository.lockPending(anyInt())).thenReturn(List.of(first, second));
        when(kafka.send(anyString(), anyString(), any())).thenReturn(acknowledged());

        assertThat(publisher.publishPending()).isEqualTo(2);

        assertThat(first.getPublishedAt()).isEqualTo(NOW);
        assertThat(second.getPublishedAt()).isEqualTo(NOW);
        assertThat(first.getPublishAttempts()).isEqualTo(1);
    }

    @Test
    void keepsFailedEventsPendingAndStopsTheBatch() {
        when(repository.lockPending(anyInt())).thenReturn(List.of(first, second));
        when(kafka.send("topic", "run-1", first.getPayload()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertThat(publisher.publishPending()).isZero();

        assertThat(first.getPublishedAt()).isNull();
        assertThat(first.getPublishAttempts()).isEqualTo(1);
        assertThat(first.getLastError()).contains("broker down");
        verify(kafka, never()).send("topic", "run-2", second.getPayload());
        assertThat(second.getPublishAttempts()).isZero();
    }

    private static CompletableFuture<SendResult<String, Object>> acknowledged() {
        return CompletableFuture.completedFuture(null);
    }
}
