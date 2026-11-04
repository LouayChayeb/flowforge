package io.github.louaychayeb.flowforge.orchestrator.engine;

import io.github.louaychayeb.flowforge.common.step.StepHandlerResult;
import io.github.louaychayeb.flowforge.orchestrator.IntegrationTest;
import io.github.louaychayeb.flowforge.orchestrator.TestStepHandlers;
import io.github.louaychayeb.flowforge.orchestrator.api.RunDtos.AttemptResponse;
import io.github.louaychayeb.flowforge.orchestrator.api.RunDtos.StepExecutionResponse;
import io.github.louaychayeb.flowforge.orchestrator.consistency.ConsistencyChecker;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinitionService;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinitionService.NewStep;
import io.github.louaychayeb.flowforge.orchestrator.run.AttemptStatus;
import io.github.louaychayeb.flowforge.orchestrator.run.StaleAttemptException;
import io.github.louaychayeb.flowforge.orchestrator.run.StepStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A worker's result and the lease sweeper race for the same attempts. Exactly one of them may win each step:
 * either the result is applied (attempt SUCCEEDED) or the lease expiry is (attempt TIMED_OUT, and the result
 * is rejected as stale) — never both, and never neither.
 */
class LeaseRaceTest extends IntegrationTest {

    private static final int STEPS = 40;

    @Autowired
    WorkflowDefinitionService definitions;

    @Autowired
    StepStateService stateService;

    @Autowired
    ConsistencyChecker checker;

    @Test
    void resultAndLeaseExpiryNeverBothWin() throws Exception {
        String workflow = uniqueWorkflowName();
        definitions.register(workflow, List.of(new NewStep("only", TestStepHandlers.OK, 3)));
        List<UUID> runIds = new ArrayList<>();
        for (int i = 0; i < STEPS; i++) {
            runIds.add(runService.start(workflow, Map.of()).getId());
        }
        Set<UUID> ours = new HashSet<>(runIds);
        List<ClaimedStep> claimed = new ArrayList<>();
        while (claimed.size() < STEPS) {
            stateService.claim(STEPS).stream().filter(step -> ours.contains(step.runId())).forEach(claimed::add);
        }

        AtomicInteger staleResults = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> results = pool.submit(() -> {
                start.await();
                for (ClaimedStep step : claimed) {
                    try {
                        stateService.recordOutcome(step.stepExecutionId(), 1, StepHandlerResult.succeeded());
                    } catch (StaleAttemptException e) {
                        staleResults.incrementAndGet();
                    }
                }
                return null;
            });
            Future<?> sweeper = pool.submit(() -> {
                start.await();
                Instant farFuture = Instant.now().plus(Duration.ofDays(1));
                while (stateService.recoverExpiredLeases(farFuture) > 0) {
                    // keep sweeping until nothing is left running
                }
                return null;
            });
            start.countDown();
            results.get();
            sweeper.get();
        } finally {
            pool.shutdownNow();
        }

        int succeeded = 0;
        int timedOut = 0;
        for (UUID runId : runIds) {
            StepExecutionResponse step = load(runId).steps().get(0);
            AttemptResponse first = step.attempts().get(0);
            if (first.status() == AttemptStatus.SUCCEEDED) {
                assertThat(step.status()).isEqualTo(StepStatus.COMPLETED);
                succeeded++;
            } else {
                assertThat(first.status()).isEqualTo(AttemptStatus.TIMED_OUT);
                assertThat(step.status()).isEqualTo(StepStatus.RETRY_WAIT);
                timedOut++;
            }
        }
        System.out.printf("Lease race: %d results applied first, %d leases expired first%n", succeeded, timedOut);
        assertThat(succeeded + timedOut).isEqualTo(STEPS);
        assertThat(staleResults.get()).as("every result that lost the race was rejected").isEqualTo(timedOut);
        assertThat(checker.check(workflow).violations()).isEmpty();
    }
}
