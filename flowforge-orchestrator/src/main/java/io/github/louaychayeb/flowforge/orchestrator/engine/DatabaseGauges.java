package io.github.louaychayeb.flowforge.orchestrator.engine;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Gauges read from the database when metrics are scraped, so they are the same on every orchestrator instance:
 * how much work is waiting or in flight right now. A growing outbox or an ever larger number of leased steps is
 * the first sign of a stage falling behind.
 */
@Component
public class DatabaseGauges {

    public DatabaseGauges(MeterRegistry registry, JdbcTemplate jdbc) {
        gauge(registry, jdbc, "flowforge.outbox.pending", "Step commands written but not yet sent to Kafka",
                "SELECT count(*) FROM outbox_event WHERE published_at IS NULL");
        gauge(registry, jdbc, "flowforge.runs.active", "Runs still running or compensating",
                "SELECT count(*) FROM workflow_run WHERE status IN ('RUNNING', 'COMPENSATING')");
        gauge(registry, jdbc, "flowforge.steps.leased", "Step attempts holding a lease (with a worker)",
                "SELECT count(*) FROM step_execution WHERE status = 'RUNNING'");
        gauge(registry, jdbc, "flowforge.steps.waiting", "Steps eligible or backing off, not yet claimed",
                "SELECT count(*) FROM step_execution WHERE status IN ('PENDING', 'RETRY_WAIT') AND next_attempt_at IS NOT NULL");
    }

    private static void gauge(MeterRegistry registry, JdbcTemplate jdbc, String name, String description, String sql) {
        Gauge.builder(name, () -> {
            try {
                Long value = jdbc.queryForObject(sql, Long.class);
                return value == null ? 0 : value;
            } catch (RuntimeException e) {
                return Double.NaN; // database unreachable: show a gap, not a misleading zero
            }
        }).description(description).register(registry);
    }
}
