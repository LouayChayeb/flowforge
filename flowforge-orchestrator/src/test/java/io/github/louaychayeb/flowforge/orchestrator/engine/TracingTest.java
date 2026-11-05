package io.github.louaychayeb.flowforge.orchestrator.engine;

import io.github.louaychayeb.flowforge.common.messaging.Topics;
import io.github.louaychayeb.flowforge.orchestrator.IntegrationTest;
import io.github.louaychayeb.flowforge.orchestrator.TestStepHandlers;
import io.github.louaychayeb.flowforge.orchestrator.api.RunDtos.RunResponse;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinitionService;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinitionService.NewStep;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One trace per run, although the run's work keeps crossing threads, the database and Kafka: the run stores its
 * trace context, every outbox row stores the context of its attempt, and the commands on the topic carry it in the
 * W3C {@code traceparent} header — which is what lets the worker continue the same trace.
 */
@AutoConfigureObservability(metrics = false)
@TestPropertySource(properties = {
        "management.tracing.enabled=true",
        "management.tracing.sampling.probability=1.0",
        // Nothing listens here: spans are created and propagated, and the export quietly fails.
        "management.otlp.tracing.endpoint=http://localhost:1/v1/traces"
})
class TracingTest extends IntegrationTest {

    @Autowired
    WorkflowDefinitionService definitions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    KafkaAdmin kafkaAdmin;

    @Test
    void aRunIsOneTraceAcrossTheOutboxAndKafka() {
        String workflow = uniqueWorkflowName();
        definitions.register(workflow, List.of(
                new NewStep("first", TestStepHandlers.OK, 3),
                new NewStep("second", TestStepHandlers.OK, 3)));
        UUID runId = runService.start(workflow, Map.of()).getId();

        RunResponse run = awaitTerminal(runId);
        assertThat(run.traceId()).as("the run's trace").isNotNull().hasSize(32);

        List<String> stored = jdbc.queryForList(
                "SELECT trace_parent FROM outbox_event WHERE message_key = ? ORDER BY created_at", String.class,
                runId.toString());
        assertThat(stored).hasSize(2).allSatisfy(tp -> assertThat(tp).startsWith("00-" + run.traceId() + "-"));

        assertThat(commandHeaders(runId)).hasSize(2)
                .allSatisfy(tp -> assertThat(tp).as("traceparent header").contains(run.traceId()));
    }

    /** The {@code traceparent} header of every command of the run on the step-commands topic. */
    private List<String> commandHeaders(UUID runId) {
        Map<String, Object> config = new HashMap<>(kafkaAdmin.getConfigurationProperties());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "tracing-test-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<String> headers = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(Topics.STEP_COMMANDS));
            long deadline = System.currentTimeMillis() + 20_000;
            while (headers.size() < 2 && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (runId.toString().equals(record.key())) {
                        Header header = record.headers().lastHeader("traceparent");
                        headers.add(header == null ? "<none>" : new String(header.value(), StandardCharsets.UTF_8));
                    }
                }
            }
        }
        return headers;
    }
}
