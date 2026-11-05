package io.github.louaychayeb.flowforge.orchestrator.insight;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only queries behind the dashboard. They read the same tables the engine writes, with plain SQL and
 * without locks, so watching the system never slows it down or changes what it does.
 */
@Component
public class InsightQueries {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public InsightQueries(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public record RunSummary(UUID runId, String workflow, int version, String status, String currentStep,
                             String currentStepKind, int activeSteps, Map<String, Object> input,
                             Instant createdAt, Instant completedAt, int steps, int attempts, int failedAttempts) {
    }

    /** The most recent runs, newest first, optionally only those in one status. */
    @Transactional(readOnly = true)
    public List<RunSummary> recentRuns(int limit, String status) {
        return jdbc.query("""
                SELECT r.id, d.name, d.version, r.status, r.input::text AS input, r.created_at, r.completed_at,
                       cur.name AS current_step, cur.kind AS current_kind,
                       (SELECT count(*) FROM step_execution s WHERE s.workflow_run_id = r.id
                          AND r.status IN ('RUNNING', 'COMPENSATING')
                          AND (s.status IN ('RUNNING', 'RETRY_WAIT')
                               OR (s.status = 'PENDING' AND s.next_attempt_at IS NOT NULL))) AS active,
                       (SELECT count(*) FROM step_execution s WHERE s.workflow_run_id = r.id) AS steps,
                       (SELECT count(*) FROM step_attempt a JOIN step_execution s ON s.id = a.step_execution_id
                        WHERE s.workflow_run_id = r.id) AS attempts,
                       (SELECT count(*) FROM step_attempt a JOIN step_execution s ON s.id = a.step_execution_id
                        WHERE s.workflow_run_id = r.id AND a.status <> 'SUCCEEDED' AND a.status <> 'RUNNING')
                           AS failed_attempts
                FROM workflow_run r
                JOIN workflow_definition d ON d.id = r.workflow_definition_id
                LEFT JOIN step_execution cur ON cur.workflow_run_id = r.id AND cur.position = r.current_position
                     AND r.status IN ('RUNNING', 'COMPENSATING')
                WHERE (CAST(:status AS varchar) IS NULL OR r.status = :status)
                ORDER BY r.created_at DESC
                LIMIT :limit
                """, new MapSqlParameterSource().addValue("limit", limit).addValue("status", status),
                (rs, i) -> new RunSummary(rs.getObject("id", UUID.class), rs.getString("name"), rs.getInt("version"),
                        rs.getString("status"), rs.getString("current_step"), rs.getString("current_kind"), rs.getInt("active"),
                        json(rs.getString("input")), instant(rs, "created_at"), instant(rs, "completed_at"),
                        rs.getInt("steps"), rs.getInt("attempts"), rs.getInt("failed_attempts")));
    }

    public record CommandMessage(UUID eventId, UUID stepExecutionId, String stepName, String stepType, int attempt,
                                 String deadline, Instant createdAt, Instant publishedAt, int publishAttempts,
                                 String lastError) {
    }

    /**
     * The step commands written to the outbox for one run, oldest first. Results are not stored: workers send
     * them straight to Kafka and the orchestrator applies them, which is visible as the attempts' outcomes.
     */
    @Transactional(readOnly = true)
    public List<CommandMessage> commandsOfRun(UUID runId) {
        return jdbc.query("""
                SELECT id, payload->>'stepExecutionId' AS step_id, payload->>'stepName' AS step_name,
                       payload->>'stepType' AS step_type, (payload->>'attempt')::int AS attempt,
                       payload->>'deadline' AS deadline, created_at, published_at, publish_attempts, last_error
                FROM outbox_event
                WHERE message_key = :runId
                ORDER BY created_at
                """, new MapSqlParameterSource("runId", runId.toString()),
                (rs, i) -> new CommandMessage(rs.getObject("id", UUID.class), UUID.fromString(rs.getString("step_id")),
                        rs.getString("step_name"), rs.getString("step_type"), rs.getInt("attempt"),
                        rs.getString("deadline"), instant(rs, "created_at"), instant(rs, "published_at"),
                        rs.getInt("publish_attempts"), rs.getString("last_error")));
    }

    public record ActiveStep(UUID runId, UUID stepExecutionId, String stepName, String kind, String status,
                             int attempt, int maxAttempts, Instant leaseExpiresAt, Instant nextAttemptAt) {
    }

    public record DatabaseStats(Map<String, Long> runs, Map<String, Long> steps, Map<String, Long> attempts,
                                long outboxPending, long outboxPublished, Instant oldestPendingCommand,
                                List<ActiveStep> leased, List<ActiveStep> waiting) {
    }

    /** Counts by status across the whole database, plus the steps that are running or waiting right now. */
    @Transactional(readOnly = true)
    public DatabaseStats databaseStats() {
        MapSqlParameterSource none = new MapSqlParameterSource();
        Map<String, Long> outbox = counts("""
                SELECT CASE WHEN published_at IS NULL THEN 'pending' ELSE 'published' END, count(*)
                FROM outbox_event GROUP BY 1
                """);
        Timestamp oldest = jdbc.queryForObject(
                "SELECT min(created_at) FROM outbox_event WHERE published_at IS NULL", none, Timestamp.class);
        return new DatabaseStats(
                counts("SELECT status, count(*) FROM workflow_run GROUP BY status"),
                counts("SELECT status, count(*) FROM step_execution GROUP BY status"),
                counts("SELECT status, count(*) FROM step_attempt GROUP BY status"),
                outbox.getOrDefault("pending", 0L), outbox.getOrDefault("published", 0L),
                oldest == null ? null : oldest.toInstant(),
                activeSteps("status = 'RUNNING'", "lease_expires_at"),
                activeSteps("status IN ('PENDING', 'RETRY_WAIT') AND next_attempt_at IS NOT NULL", "next_attempt_at"));
    }

    private List<ActiveStep> activeSteps(String condition, String order) {
        return jdbc.query("""
                SELECT workflow_run_id, id, name, kind, status, attempt_count, max_attempts, lease_expires_at,
                       next_attempt_at
                FROM step_execution WHERE %s ORDER BY %s LIMIT 25
                """.formatted(condition, order), new MapSqlParameterSource(),
                (rs, i) -> new ActiveStep(rs.getObject("workflow_run_id", UUID.class), rs.getObject("id", UUID.class),
                        rs.getString("name"), rs.getString("kind"), rs.getString("status"), rs.getInt("attempt_count"),
                        rs.getInt("max_attempts"), instant(rs, "lease_expires_at"), instant(rs, "next_attempt_at")));
    }

    private Map<String, Long> counts(String sql) {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query(sql, new MapSqlParameterSource(), rs -> {
            counts.put(rs.getString(1), rs.getLong(2));
        });
        return counts;
    }

    private Map<String, Object> json(String text) {
        try {
            return text == null ? Map.of() : objectMapper.readValue(text, JSON_OBJECT);
        } catch (JsonProcessingException e) {
            return Map.of("unreadable", text);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
