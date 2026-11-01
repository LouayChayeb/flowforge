package io.github.louaychayeb.flowforge.orchestrator;

import io.github.louaychayeb.flowforge.orchestrator.api.RunDtos.RunResponse;
import io.github.louaychayeb.flowforge.orchestrator.engine.StepDispatcher;
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
 * <p>The background dispatcher is disabled and retries have no backoff; tests drive dispatching
 * themselves with {@link #awaitTerminal(UUID)}.
 */
@SpringBootTest(properties = {
        "flowforge.engine.dispatcher.enabled=false",
        "flowforge.engine.retry.initial-backoff=0s"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestWorker.class})
@Testcontainers(disabledWithoutDocker = true)
public abstract class IntegrationTest {

    @Autowired
    protected StepDispatcher dispatcher;

    @Autowired
    protected WorkflowRunService runService;

    /**
     * Keeps dispatching until the run reaches a terminal state, then returns it.
     */
    protected RunResponse awaitTerminal(UUID runId) {
        return await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(50))
                .until(() -> {
                    dispatcher.dispatchOnce();
                    return runService.find(runId, RunResponse::from);
                }, run -> run.status().isTerminal());
    }

    /** A workflow name no other test uses, so tests are independent of each other's data. */
    protected static String uniqueWorkflowName() {
        return "wf-" + UUID.randomUUID();
    }
}
