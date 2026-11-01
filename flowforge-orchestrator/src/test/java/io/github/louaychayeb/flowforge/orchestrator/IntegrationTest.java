package io.github.louaychayeb.flowforge.orchestrator;

import io.github.louaychayeb.flowforge.orchestrator.engine.StepDispatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

/**
 * Base for tests that run the full application against a real PostgreSQL container.
 * Skipped when Docker is not available.
 *
 * <p>The background dispatcher is disabled and retries have no backoff, so tests drive execution
 * deterministically with {@link #runUntilIdle()}.
 */
@SpringBootTest(properties = {
        "flowforge.engine.dispatcher.enabled=false",
        "flowforge.engine.retry.initial-backoff=0s"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestStepHandlers.class})
@Testcontainers(disabledWithoutDocker = true)
public abstract class IntegrationTest {

    @Autowired
    protected StepDispatcher dispatcher;

    /**
     * Dispatches until no step is eligible. Terminates because every step has bounded attempts.
     */
    protected void runUntilIdle() {
        int claimed;
        do {
            claimed = dispatcher.dispatchOnce();
        } while (claimed > 0);
    }

    /** A workflow name no other test uses, so tests are independent of each other's data. */
    protected static String uniqueWorkflowName() {
        return "wf-" + UUID.randomUUID();
    }
}
