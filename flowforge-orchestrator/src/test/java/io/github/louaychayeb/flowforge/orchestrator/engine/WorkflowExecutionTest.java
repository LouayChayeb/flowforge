package io.github.louaychayeb.flowforge.orchestrator.engine;

import io.github.louaychayeb.flowforge.orchestrator.IntegrationTest;
import io.github.louaychayeb.flowforge.orchestrator.TestStepHandlers;
import io.github.louaychayeb.flowforge.orchestrator.api.RunDtos.RunResponse;
import io.github.louaychayeb.flowforge.orchestrator.api.RunDtos.StepExecutionResponse;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinitionService;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinitionService.NewStep;
import io.github.louaychayeb.flowforge.orchestrator.run.AttemptStatus;
import io.github.louaychayeb.flowforge.orchestrator.run.RunStatus;
import io.github.louaychayeb.flowforge.orchestrator.run.StepStatus;
import io.github.louaychayeb.flowforge.orchestrator.run.WorkflowRunService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end execution through the dispatcher against a real database.
 */
class WorkflowExecutionTest extends IntegrationTest {

    @Autowired
    WorkflowDefinitionService definitions;

    @Autowired
    WorkflowRunService runs;

    @Autowired
    StepStateService stateService;

    @Test
    void runsAllStepsInOrderAndCompletes() {
        UUID runId = startRun(
                new NewStep("first", TestStepHandlers.OK, null),
                new NewStep("second", TestStepHandlers.OK, null),
                new NewStep("third", TestStepHandlers.OK, null));

        runUntilIdle();

        RunResponse run = load(runId);
        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.completedAt()).isNotNull();
        assertThat(run.currentStep()).isNull();
        assertThat(run.steps()).extracting(StepExecutionResponse::status).containsOnly(StepStatus.COMPLETED);
        assertThat(run.steps()).extracting(StepExecutionResponse::attemptCount).containsOnly(1);
    }

    @Test
    void retriesRetryableFailuresAndRecordsEveryAttempt() {
        UUID runId = startRun(
                new NewStep("flaky", TestStepHandlers.FLAKY, 3),
                new NewStep("after", TestStepHandlers.OK, null));

        runUntilIdle();

        RunResponse run = load(runId);
        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        StepExecutionResponse flaky = run.steps().get(0);
        assertThat(flaky.attemptCount()).isEqualTo(3);
        assertThat(flaky.attempts()).extracting(a -> a.status()).containsExactly(
                AttemptStatus.RETRYABLE_FAILURE, AttemptStatus.RETRYABLE_FAILURE, AttemptStatus.SUCCEEDED);
        assertThat(flaky.attempts().get(0).errorMessage()).isEqualTo("flaky failure on attempt 1");
    }

    @Test
    void failsTheRunWhenAttemptsAreExhausted() {
        UUID runId = startRun(
                new NewStep("unavailable", TestStepHandlers.ALWAYS_RETRYABLE, 2),
                new NewStep("never-runs", TestStepHandlers.OK, null));

        runUntilIdle();

        RunResponse run = load(runId);
        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.steps().get(0).status()).isEqualTo(StepStatus.FAILED);
        assertThat(run.steps().get(0).attemptCount()).isEqualTo(2);
        assertThat(run.steps().get(1).status()).isEqualTo(StepStatus.PENDING);
        assertThat(run.steps().get(1).attempts()).isEmpty();
    }

    @Test
    void doesNotRetryPermanentFailures() {
        UUID runId = startRun(new NewStep("rejected", TestStepHandlers.PERMANENT, 5));

        runUntilIdle();

        RunResponse run = load(runId);
        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.steps().get(0).attemptCount()).isEqualTo(1);
        assertThat(run.steps().get(0).attempts().get(0).status()).isEqualTo(AttemptStatus.PERMANENT_FAILURE);
    }

    @Test
    void treatsHandlerExceptionsAsRetryable() {
        UUID runId = startRun(new NewStep("explodes", TestStepHandlers.THROWS, 2));

        runUntilIdle();

        RunResponse run = load(runId);
        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.steps().get(0).attempts()).extracting(a -> a.status())
                .containsOnly(AttemptStatus.RETRYABLE_FAILURE);
        assertThat(run.steps().get(0).attempts().get(0).errorMessage()).contains("boom");
    }

    @Test
    void concurrentDispatchersNeverClaimTheSameStep() throws Exception {
        int runCount = 20;
        for (int i = 0; i < runCount; i++) {
            startRun(new NewStep("only", TestStepHandlers.OK, null));
        }

        // Four "dispatchers" race to claim at the same time; SKIP LOCKED must hand each step to exactly one.
        Callable<List<ClaimedStep>> claimer = () -> stateService.claim(runCount);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<List<ClaimedStep>>> futures = pool.invokeAll(List.of(claimer, claimer, claimer, claimer));
            List<UUID> claimedIds = futures.stream()
                    .flatMap(f -> {
                        try {
                            return f.get().stream();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .map(ClaimedStep::stepExecutionId)
                    .toList();
            // Other tests may leave eligible steps behind, so at least our runCount steps are claimed.
            assertThat(claimedIds).hasSizeGreaterThanOrEqualTo(runCount).doesNotHaveDuplicates();
        } finally {
            pool.shutdownNow();
        }
    }

    private UUID startRun(NewStep... steps) {
        String name = uniqueWorkflowName();
        definitions.register(name, List.of(steps));
        return runs.start(name, Map.of("orderId", "ORD-1")).getId();
    }

    private RunResponse load(UUID runId) {
        return runs.find(runId, RunResponse::from);
    }
}
