package io.github.louaychayeb.flowforge.orchestrator;

import org.junit.jupiter.api.Test;

/**
 * Starts the full application against a real PostgreSQL container: Flyway migrations apply and
 * Hibernate validates the entity mappings against the resulting schema.
 */
class OrchestratorApplicationTests extends IntegrationTest {

    @Test
    void contextLoads() {
    }
}
