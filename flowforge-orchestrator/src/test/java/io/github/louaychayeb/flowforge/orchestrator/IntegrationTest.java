package io.github.louaychayeb.flowforge.orchestrator;

import io.github.louaychayeb.flowforge.orchestrator.api.RunDtos.RunResponse;
import io.github.louaychayeb.flowforge.orchestrator.engine.StepDispatcher;
import io.github.louaychayeb.flowforge.orchestrator.outbox.OutboxPublisher;
import io.github.louaychayeb.flowforge.orchestrator.run.WorkflowRunService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;

/**
 * Base for tests that run the full orchestrator against real PostgreSQL and Kafka containers, with
 * {@link TestWorker} executing steps on the other side of Kafka. Skipped when Docker is not available.
 *
 * <p>The background scheduler is disabled and retries have no backoff; tests drive the engine's loops
 * themselves, which lets them stop between any two of them to simulate a crash.
 */
@SpringBootTest(properties = {
        "flowforge.engine.scheduler.enabled=false",
        "flowforge.engine.retry.initial-backoff=0s"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestWorker.class})
@Testcontainers(disabledWithoutDocker = true)
public abstract class IntegrationTest {

    @Autowired
    protected StepDispatcher dispatcher;

    @Autowired
    protected OutboxPublisher outboxPublisher;

    @Autowired
    protected WorkflowRunService runService;

    /**
     * Keeps claiming steps and publishing their commands until the run reaches a terminal state.
     */
    protected RunResponse awaitTerminal(UUID runId) {
        return await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(50))
                .until(() -> {
                    dispatcher.dispatchOnce();
                    outboxPublisher.publishPending();
                    return load(runId);
                }, run -> run.status().isTerminal());
    }

    protected RunResponse load(UUID runId) {
        return runService.find(runId, RunResponse::from);
    }

    /** A workflow name no other test uses, so tests are independent of each other's data. */
    protected static String uniqueWorkflowName() {
        return "wf-" + UUID.randomUUID();
    }
}
