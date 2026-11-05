package io.github.louaychayeb.flowforge.orchestrator.engine;

import io.github.louaychayeb.flowforge.orchestrator.TestStepHandlers;
import io.github.louaychayeb.flowforge.orchestrator.TestWorker;
import io.github.louaychayeb.flowforge.orchestrator.TestcontainersConfiguration;
import io.github.louaychayeb.flowforge.orchestrator.api.RunDtos.RunResponse;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinitionService;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinitionService.NewStep;
import io.github.louaychayeb.flowforge.orchestrator.run.RunStatus;
import io.github.louaychayeb.flowforge.orchestrator.run.WorkflowRunService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * With the periodic loops slowed to once a minute, a run can only finish quickly if the engine wakes itself
 * up whenever work appears: on run start, after each result, and when a retry backoff ends.
 */
@SpringBootTest(properties = {
        "flowforge.engine.scheduler.enabled=true",
        "flowforge.engine.scheduler.dispatch-interval-ms=60000",
        "flowforge.engine.scheduler.outbox-interval-ms=60000",
        "flowforge.engine.scheduler.recovery-interval-ms=60000",
        "flowforge.engine.retry.initial-backoff=300ms"
})
@Import({TestcontainersConfiguration.class, TestWorker.class})
@Testcontainers(disabledWithoutDocker = true)
class EngineWakeupTest {

    @Autowired
    WorkflowDefinitionService definitions;

    @Autowired
    WorkflowRunService runs;

    @Test
    void runsFinishWithoutWaitingForTheLoopsToTick() {
        String workflow = "wakeup-" + UUID.randomUUID();
        definitions.register(workflow, List.of(
                new NewStep("one", TestStepHandlers.OK, 3),
                new NewStep("two", TestStepHandlers.FLAKY, 3), // fails twice: two backoffs of 300 ms, 600 ms
                new NewStep("three", TestStepHandlers.OK, 3)));

        // The first tick of each loop fires at startup; let it pass so it cannot do this run's work.
        await().pollDelay(Duration.ofSeconds(1)).until(() -> true);
        UUID runId = runs.start(workflow, Map.of()).getId();

        RunResponse run = await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                .until(() -> runs.find(runId, RunResponse::from), r -> r.status().isTerminal());
        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        // Five attempts and two backoffs, all well inside a single 60 s tick.
        assertThat(Duration.between(run.createdAt(), run.completedAt())).isLessThan(Duration.ofSeconds(10));
        assertThat(run.steps().get(1).attemptCount()).isEqualTo(3);
    }
}
