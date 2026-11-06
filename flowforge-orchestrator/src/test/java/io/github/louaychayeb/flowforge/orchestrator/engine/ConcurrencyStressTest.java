package io.github.louaychayeb.flowforge.orchestrator.engine;

import io.github.louaychayeb.flowforge.common.messaging.StepOutcome;
import io.github.louaychayeb.flowforge.common.messaging.StepResult;
import io.github.louaychayeb.flowforge.common.messaging.Topics;
import io.github.louaychayeb.flowforge.orchestrator.IntegrationTest;
import io.github.louaychayeb.flowforge.orchestrator.TestStepHandlers;
import io.github.louaychayeb.flowforge.orchestrator.TestWorker;
import io.github.louaychayeb.flowforge.orchestrator.consistency.ConsistencyChecker;
import io.github.louaychayeb.flowforge.orchestrator.consistency.ConsistencyReport;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinitionService;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinitionService.NewStep;
import io.github.louaychayeb.flowforge.orchestrator.run.RunStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Runs a workload under deliberately hostile conditions and then checks every invariant of the stored history:
 *
 * <ul>
 *   <li>three "orchestrator instances" run the dispatch, outbox and recovery loops concurrently;</li>
 *   <li>three test-worker consumers execute steps, sometimes reporting late and sometimes reporting twice;</li>
 *   <li>leases last {@value #LEASE} and some reports take up to {@value #MAX_DELAY_MILLIS} ms, so late results
 *       race with lease expiry;</li>
 *   <li>a background thread keeps re-sending random results for attempts that already finished.</li>
 * </ul>
 *
 * Every run must still end in the status its input dictates, and the {@link ConsistencyChecker} must find
 * nothing wrong.
 *
 * <p>The short lease needs its own Spring context (and containers), since every other test uses the default.
 *
 * <p>Retries back off as they would in production. The other integration tests retry immediately, for speed;
 * here that made every burst of timed-out attempts come back as an identical burst, which timed out again,
 * until some runs exhausted their attempts — the test failed about one time in three.
 */
@TestPropertySource(properties = {
        "flowforge.engine.lease-duration=" + ConcurrencyStressTest.LEASE,
        "flowforge.engine.retry.initial-backoff=250ms",
        "flowforge.engine.retry.max-backoff=2s"})
class ConcurrencyStressTest extends IntegrationTest {

    static final String LEASE = "1500ms";
    private static final int RUNS = 40;
    private static final int ENGINE_INSTANCES = 3;
    private static final double DELAY_PROBABILITY = 0.25;
    private static final int MAX_DELAY_MILLIS = 2_000;
    private static final double DUPLICATE_PROBABILITY = 0.2;
    /*
     * Headroom, not leniency: delayed reports sleep on a worker's consumer thread, so bursts queue commands past their
     * 1.5 s lease and steps time out repeatedly. With 20 attempts an unlucky step now and then ran out on a slow CI
     * runner (every invariant intact, but the run FAILED instead of COMPLETED); 40 keeps the final statuses
     * deterministic while the chaos stays as hostile.
     */
    private static final int MAX_ATTEMPTS = 40;

    @Autowired
    WorkflowDefinitionService definitions;

    @Autowired
    StepStateService stateService;

    @Autowired
    EngineMetrics metrics;

    @Autowired
    ConsistencyChecker checker;

    @Autowired
    TestWorker.Chaos chaos;

    @Autowired
    KafkaTemplate<String, Object> kafka;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    Clock clock;

    private final AtomicBoolean stop = new AtomicBoolean();
    private final List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void calmDown() {
        stop.set(true);
        chaos.disable();
    }

    @Test
    void invariantsHoldUnderConcurrencyDuplicatesAndTimeouts() throws Exception {
        String workflow = uniqueWorkflowName();
        // A diamond, so parallel branches race each other as well as the chaos: reserve → (flaky ∥ charge) → finish.
        definitions.register(workflow, List.of(
                new NewStep("reserve", TestStepHandlers.OK, MAX_ATTEMPTS, TestStepHandlers.UNDO, List.of()),
                new NewStep("flaky", TestStepHandlers.SOMETIMES, MAX_ATTEMPTS, TestStepHandlers.UNDO, List.of("reserve")),
                new NewStep("charge", TestStepHandlers.OK, MAX_ATTEMPTS, TestStepHandlers.UNDO, List.of("reserve")),
                new NewStep("finish", TestStepHandlers.FAIL_IF_FLAGGED, MAX_ATTEMPTS, null, List.of("flaky", "charge"))));
        Map<UUID, RunStatus> expected = new HashMap<>();
        for (int i = 0; i < RUNS; i++) {
            boolean fail = i % 4 == 0;
            UUID runId = runService.start(workflow, Map.of("orderId", "ORD-" + i, "fail", fail)).getId();
            expected.put(runId, fail ? RunStatus.COMPENSATED : RunStatus.COMPLETED);
        }
        double staleBefore = metrics.staleResults();
        double expiredBefore = metrics.expiredLeases();
        AtomicInteger injected = new AtomicInteger();

        chaos.enable(DELAY_PROBABILITY, MAX_DELAY_MILLIS, DUPLICATE_PROBABILITY);
        ExecutorService pool = Executors.newFixedThreadPool(ENGINE_INSTANCES + 1);
        try {
            for (int i = 0; i < ENGINE_INSTANCES; i++) {
                pool.submit(this::engineInstance);
            }
            pool.submit(() -> injectStaleResults(workflow, injected));

            await().atMost(Duration.ofMinutes(5)).pollInterval(Duration.ofMillis(500))
                    .until(() -> unfinishedRuns(workflow) == 0 || !unexpected.isEmpty());
        } finally {
            stop.set(true);
            chaos.disable();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(unexpected).as("exceptions thrown by the engine loops").isEmpty();
        ConsistencyReport report = checker.check(workflow);
        assertThat(report.violations()).as("consistency violations").isEmpty();
        assertThat(report.consistent()).isTrue();
        Map<UUID, RunStatus> actual = new HashMap<>();
        for (UUID runId : expected.keySet()) {
            actual.put(runId, load(runId).status());
        }
        assertThat(actual).as("final run statuses").isEqualTo(expected);

        // Prove the hostile paths actually fired, so the clean report means something.
        double stale = metrics.staleResults() - staleBefore;
        double expired = metrics.expiredLeases() - expiredBefore;
        Map<String, Object> attempts = jdbc.queryForMap("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE a.status = 'TIMED_OUT') AS timed_out,
                       count(*) FILTER (WHERE a.status = 'RETRYABLE_FAILURE') AS retried
                FROM step_attempt a
                JOIN step_execution s ON s.id = a.step_execution_id
                JOIN workflow_run r ON r.id = s.workflow_run_id
                JOIN workflow_definition d ON d.id = r.workflow_definition_id
                WHERE d.name = :workflow
                """, Map.of("workflow", workflow));
        System.out.printf("Stress run: %d runs, attempts=%s, timed out=%s, retryable failures=%s, "
                        + "stale results rejected=%.0f, leases expired=%.0f, stale results injected=%d%n",
                RUNS, attempts.get("total"), attempts.get("timed_out"), attempts.get("retried"),
                stale, expired, injected.get());
        assertThat(stale).as("stale results rejected").isPositive();
        assertThat(expired).as("leases expired").isPositive();
        assertThat(((Number) attempts.get("timed_out")).longValue()).as("timed-out attempts").isPositive();
    }

    /** One orchestrator instance: the dispatch, outbox and recovery loops. */
    private void engineInstance() {
        while (!stop.get()) {
            try {
                int work = dispatcher.dispatchOnce()
                        + outboxPublisher.publishPending()
                        + stateService.recoverExpiredLeases(clock.instant());
                if (work == 0) {
                    Thread.sleep(20);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable e) {
                unexpected.add(e);
            }
        }
    }

    /** Re-sends random outcomes for attempts that already finished; every one of them must be rejected. */
    private void injectStaleResults(String workflow, AtomicInteger injected) {
        StepOutcome[] outcomes = StepOutcome.values();
        while (!stop.get()) {
            try {
                List<Map<String, Object>> finished = jdbc.queryForList("""
                        SELECT s.id AS step_id, s.workflow_run_id AS run_id, a.attempt_number AS attempt
                        FROM step_attempt a
                        JOIN step_execution s ON s.id = a.step_execution_id
                        JOIN workflow_run r ON r.id = s.workflow_run_id
                        JOIN workflow_definition d ON d.id = r.workflow_definition_id
                        WHERE d.name = :workflow AND a.status <> 'RUNNING'
                        ORDER BY random() LIMIT 5
                        """, Map.of("workflow", workflow));
                for (Map<String, Object> row : finished) {
                    UUID runId = (UUID) row.get("run_id");
                    StepOutcome outcome = outcomes[ThreadLocalRandom.current().nextInt(outcomes.length)];
                    kafka.send(Topics.STEP_RESULTS, runId.toString(), new StepResult(UUID.randomUUID(), runId,
                            (UUID) row.get("step_id"), ((Number) row.get("attempt")).intValue(), outcome,
                            "injected stale result"));
                    injected.incrementAndGet();
                }
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable e) {
                unexpected.add(e);
            }
        }
    }

    private int unfinishedRuns(String workflow) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM workflow_run r
                JOIN workflow_definition d ON d.id = r.workflow_definition_id
                WHERE d.name = :workflow AND r.status IN ('RUNNING', 'COMPENSATING')
                """, Map.of("workflow", workflow), Integer.class);
        return count == null ? 0 : count;
    }
}
