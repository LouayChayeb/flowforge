package io.github.louaychayeb.flowforge.orchestrator;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Real PostgreSQL and Kafka for integration tests. Declared as beans so one pair of containers is shared
 * by every test class that uses the same cached Spring context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** Testcontainers 1.21's KafkaContainer cannot configure the 3.9 image's listeners; 3.8 works. */
    public static final String KAFKA_IMAGE = "apache/kafka:3.8.1";

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16-alpine");
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        // An explicit hostname keeps the broker's internal advertised listener routable on every Docker version.
        return new KafkaContainer(KAFKA_IMAGE);
    }
}
