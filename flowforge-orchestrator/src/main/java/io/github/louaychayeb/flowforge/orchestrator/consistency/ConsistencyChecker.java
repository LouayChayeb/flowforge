package io.github.louaychayeb.flowforge.orchestrator.consistency;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks the stored execution history against invariants that must hold no matter which processes crashed,
 * which messages were duplicated or delayed, and how many dispatchers, publishers and workers ran at once.
 *
 * <p>Each invariant is a SQL query that returns the ids of the rows breaking it, so an empty result means
 * the invariant holds. The concurrency stress test runs every check after a chaotic workload; the admin
 * endpoint runs them against a live database.
 */
@Component
public class ConsistencyChecker {

    /** Joins a query's {@code workflow_run r} to its definition and optionally filters by workflow name. */
    private static final String SCOPE = """
            JOIN workflow_definition d ON d.id = r.workflow_definition_id
            WHERE (CAST(:workflow AS varchar) IS NULL OR d.name = :workflow)
            """;

    static final List<Invariant> INVARIANTS = List.of(
            new Invariant("attempt-history-complete",
                    "Every step's attempts are numbered 1..attemptCount with no gaps or extras",
                    """
                    SELECT s.id::text FROM step_execution s
                    JOIN workflow_run r ON r.id = s.workflow_run_id
                    """ + SCOPE + """
                      AND ((SELECT count(*) FROM step_attempt a WHERE a.step_execution_id = s.id) <> s.attempt_count
                        OR (SELECT coalesce(max(a.attempt_number), 0) FROM step_attempt a
                            WHERE a.step_execution_id = s.id) <> s.attempt_count)
                    """),
            new Invariant("single-open-attempt",
                    "Only a RUNNING step has an open attempt, and that attempt is its latest",
                    """
                    SELECT a.id::text FROM step_attempt a
                    JOIN step_execution s ON s.id = a.step_execution_id
                    JOIN workflow_run r ON r.id = s.workflow_run_id
                    """ + SCOPE + """
                      AND ((a.status = 'RUNNING' AND (s.status <> 'RUNNING' OR a.attempt_number <> s.attempt_count))
                        OR (s.status = 'RUNNING' AND a.attempt_number = s.attempt_count AND a.status <> 'RUNNING'))
                    """),
            new Invariant("exactly-one-success",
                    "A COMPLETED step has exactly one successful attempt, its last; any other step has none",
                    """
                    SELECT s.id::text FROM step_execution s
                    JOIN workflow_run r ON r.id = s.workflow_run_id
                    """ + SCOPE + """
                      AND ((s.status = 'COMPLETED' AND (
                              (SELECT count(*) FROM step_attempt a
                               WHERE a.step_execution_id = s.id AND a.status = 'SUCCEEDED') <> 1
                           OR NOT EXISTS (SELECT 1 FROM step_attempt a WHERE a.step_execution_id = s.id
                                          AND a.status = 'SUCCEEDED' AND a.attempt_number = s.attempt_count)))
                        OR (s.status <> 'COMPLETED' AND EXISTS (
                              SELECT 1 FROM step_attempt a WHERE a.step_execution_id = s.id AND a.status = 'SUCCEEDED')))
                    """),
            new Invariant("dependencies-respected",
                    "No step was attempted or made eligible before every step it depends on completed, and no "
                            + "compensation ahead of the run's compensation cursor",
                    """
                    SELECT s.id::text FROM step_execution s
                    JOIN workflow_run r ON r.id = s.workflow_run_id
                    """ + SCOPE + """
                      AND (s.attempt_count > 0 OR s.next_attempt_at IS NOT NULL)
                      AND ((s.kind = 'FORWARD' AND EXISTS (
                                SELECT 1 FROM jsonb_array_elements_text(s.depends_on) AS dep(pos)
                                JOIN step_execution dep_step ON dep_step.workflow_run_id = s.workflow_run_id
                                                            AND dep_step.position = dep.pos::int
                                WHERE dep_step.status <> 'COMPLETED'))
                        OR (s.kind = 'COMPENSATION' AND s.position > r.current_position))
                    """),
            new Invariant("ordered-in-time",
                    "Each step's first attempt started only after what it waits for finished: its dependencies' "
                            + "successes for a forward step; every forward attempt and the previous compensation for "
                            + "a compensation step",
                    """
                    SELECT s.id::text FROM step_execution s
                    JOIN step_attempt first_try ON first_try.step_execution_id = s.id AND first_try.attempt_number = 1
                    JOIN workflow_run r ON r.id = s.workflow_run_id
                    """ + SCOPE + """
                      AND s.kind = 'FORWARD'
                      AND EXISTS (
                          SELECT 1 FROM jsonb_array_elements_text(s.depends_on) AS dep(pos)
                          JOIN step_execution dep_step ON dep_step.workflow_run_id = s.workflow_run_id
                                                      AND dep_step.position = dep.pos::int
                          WHERE NOT EXISTS (SELECT 1 FROM step_attempt ok
                                            WHERE ok.step_execution_id = dep_step.id AND ok.status = 'SUCCEEDED'
                                              AND ok.finished_at <= first_try.started_at))
                    UNION ALL
                    SELECT s.id::text FROM step_execution s
                    JOIN step_attempt first_try ON first_try.step_execution_id = s.id AND first_try.attempt_number = 1
                    JOIN workflow_run r ON r.id = s.workflow_run_id
                    """ + SCOPE + """
                      AND s.kind = 'COMPENSATION'
                      AND (EXISTS (SELECT 1 FROM step_execution f
                                   JOIN step_attempt fa ON fa.step_execution_id = f.id
                                   WHERE f.workflow_run_id = s.workflow_run_id AND f.kind = 'FORWARD'
                                     AND (fa.finished_at IS NULL OR fa.finished_at > first_try.started_at))
                        OR EXISTS (SELECT 1 FROM step_execution prev
                                   WHERE prev.workflow_run_id = s.workflow_run_id AND prev.position = s.position - 1
                                     AND prev.kind = 'COMPENSATION'
                                     AND NOT EXISTS (SELECT 1 FROM step_attempt ok
                                                     WHERE ok.step_execution_id = prev.id AND ok.status = 'SUCCEEDED'
                                                       AND ok.finished_at <= first_try.started_at)))
                    """),
            new Invariant("completed-run-is-complete",
                    "A COMPLETED run has every step completed and nothing compensated",
                    """
                    SELECT DISTINCT r.id::text FROM workflow_run r
                    JOIN step_execution s ON s.workflow_run_id = r.id
                    """ + SCOPE + """
                      AND r.status = 'COMPLETED' AND (s.status <> 'COMPLETED' OR s.kind = 'COMPENSATION')
                    """),
            new Invariant("finished-run-is-quiet",
                    "A finished run has no step running, waiting to retry, or eligible",
                    """
                    SELECT DISTINCT r.id::text FROM workflow_run r
                    JOIN step_execution s ON s.workflow_run_id = r.id
                    """ + SCOPE + """
                      AND r.status NOT IN ('RUNNING', 'COMPENSATING')
                      AND (s.status IN ('RUNNING', 'RETRY_WAIT')
                           OR (s.status = 'PENDING' AND s.next_attempt_at IS NOT NULL))
                    """),
            new Invariant("compensation-targets",
                    "Each compensation undoes a distinct COMPLETED step that declares that compensation",
                    """
                    SELECT c.id::text FROM step_execution c
                    JOIN step_execution f ON f.id = c.compensates_step_execution_id
                    JOIN workflow_run r ON r.id = c.workflow_run_id
                    """ + SCOPE + """
                      AND c.kind = 'COMPENSATION'
                      AND (f.status <> 'COMPLETED' OR f.compensation_type IS DISTINCT FROM c.step_type
                           OR EXISTS (SELECT 1 FROM step_execution c2
                                      WHERE c2.compensates_step_execution_id = f.id AND c2.id <> c.id))
                    """),
            new Invariant("compensated-run-is-compensated",
                    "A COMPENSATED run has at least one compensation, and all of them completed",
                    """
                    SELECT r.id::text FROM workflow_run r
                    """ + SCOPE + """
                      AND r.status = 'COMPENSATED'
                      AND (NOT EXISTS (SELECT 1 FROM step_execution c
                                       WHERE c.workflow_run_id = r.id AND c.kind = 'COMPENSATION')
                           OR EXISTS (SELECT 1 FROM step_execution c
                                      WHERE c.workflow_run_id = r.id AND c.kind = 'COMPENSATION'
                                        AND c.status <> 'COMPLETED'))
                    """),
            new Invariant("command-for-every-attempt",
                    "Every attempt has its step command in the outbox (the claim and the command commit together)",
                    """
                    SELECT a.id::text FROM step_attempt a
                    JOIN step_execution s ON s.id = a.step_execution_id
                    JOIN workflow_run r ON r.id = s.workflow_run_id
                    """ + SCOPE + """
                      AND NOT EXISTS (SELECT 1 FROM outbox_event o
                                      WHERE o.payload ->> 'stepExecutionId' = s.id::text
                                        AND (o.payload ->> 'attempt')::int = a.attempt_number)
                    """));

    private static final int SAMPLE_SIZE = 20;

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public ConsistencyChecker(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Runs every invariant in one read-only, repeatable-read snapshot.
     *
     * @param workflow restrict the check to runs of this workflow name, or {@code null} for all runs
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ConsistencyReport check(String workflow) {
        MapSqlParameterSource params = new MapSqlParameterSource("workflow", workflow);
        List<ConsistencyReport.Violation> violations = new ArrayList<>();
        for (Invariant invariant : INVARIANTS) {
            List<String> offenders = jdbc.queryForList(invariant.sql(), params, String.class);
            if (!offenders.isEmpty()) {
                violations.add(new ConsistencyReport.Violation(invariant.name(), invariant.description(),
                        offenders.size(), offenders.subList(0, Math.min(SAMPLE_SIZE, offenders.size()))));
            }
        }
        return new ConsistencyReport(clock.instant(), workflow, INVARIANTS.size(), violations.isEmpty(), violations);
    }

    /** The name and meaning of every invariant, whether or not it currently holds. */
    public List<InvariantInfo> invariants() {
        return INVARIANTS.stream().map(i -> new InvariantInfo(i.name(), i.description())).toList();
    }

    public record InvariantInfo(String name, String description) {
    }

    record Invariant(String name, String description, String sql) {
    }
}
