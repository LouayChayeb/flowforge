package io.github.louaychayeb.flowforge.worker;

import io.github.louaychayeb.flowforge.common.messaging.StepCommand;
import io.github.louaychayeb.flowforge.common.messaging.StepOutcome;
import io.github.louaychayeb.flowforge.common.messaging.StepResult;
import io.github.louaychayeb.flowforge.common.messaging.Topics;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The worker against a real Kafka broker: commands in, results out. Skipped when Docker is not available.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class WorkerApplicationTests {

    @Container
    @ServiceConnection
    // Testcontainers 1.21's KafkaContainer cannot configure the 3.9 image's listeners; 3.8 works.
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.1");

    @Autowired
    KafkaTemplate<String, Object> template;

    private KafkaConsumer<String, StepResult> results;

    @BeforeEach
    void subscribeToResults() {
        JsonDeserializer<StepResult> deserializer = new JsonDeserializer<>(StepResult.class, false);
        results = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), deserializer);
        results.subscribe(List.of(Topics.STEP_RESULTS));
    }

    @AfterEach
    void close() {
        results.close();
    }

    @Test
    void executesCommandsAndPublishesResults() throws Exception {
        StepCommand ok = command("VALIDATE_ORDER", Map.of("orderId", "ORD-1", "customerId", "CUS-1", "amount", 10));
        StepCommand declined = command("CHARGE_PAYMENT", Map.of("orderId", "ORD-2", "amount", 50_000));
        StepCommand unknown = command("LAUNCH_ROCKET", Map.of());
        for (StepCommand command : List.of(ok, declined, unknown)) {
            template.send(Topics.STEP_COMMANDS, command.runId().toString(), command).get();
        }

        assertThat(resultFor(ok).outcome()).isEqualTo(StepOutcome.SUCCEEDED);
        assertThat(resultFor(declined).outcome()).isEqualTo(StepOutcome.PERMANENT_FAILURE);
        assertThat(resultFor(declined).errorMessage()).startsWith("Payment declined");
        assertThat(resultFor(unknown).outcome()).isEqualTo(StepOutcome.PERMANENT_FAILURE);
        assertThat(resultFor(ok).attempt()).isEqualTo(ok.attempt());
    }

    private final List<StepResult> received = new ArrayList<>();

    private StepResult resultFor(StepCommand command) {
        return await().atMost(Duration.ofSeconds(60)).until(() -> {
            for (ConsumerRecord<String, StepResult> record : results.poll(Duration.ofMillis(200))) {
                received.add(record.value());
            }
            return received.stream()
                    .filter(r -> r.stepExecutionId().equals(command.stepExecutionId()))
                    .findFirst();
        }, found -> found.isPresent()).orElseThrow();
    }

    private static StepCommand command(String type, Map<String, Object> input) {
        return new StepCommand(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), type.toLowerCase(), type, 1,
                input, Instant.now().plusSeconds(60));
    }
}
