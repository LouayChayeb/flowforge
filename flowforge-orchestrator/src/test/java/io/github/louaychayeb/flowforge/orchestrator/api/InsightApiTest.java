package io.github.louaychayeb.flowforge.orchestrator.api;

import io.github.louaychayeb.flowforge.orchestrator.IntegrationTest;
import io.github.louaychayeb.flowforge.orchestrator.TestStepHandlers;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinitionService;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinitionService.NewStep;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The read-only endpoints behind the dashboard.
 */
class InsightApiTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    WorkflowDefinitionService definitions;

    @Test
    void listsRecentRunsWithTheirProgress() throws Exception {
        String workflow = uniqueWorkflowName();
        definitions.register(workflow, List.of(
                new NewStep("first", TestStepHandlers.OK, 3),
                new NewStep("second", TestStepHandlers.FLAKY, 3)));
        UUID runId = runService.start(workflow, Map.of("orderId", "ORD-7")).getId();
        awaitTerminal(runId);

        mvc.perform(get("/api/runs").param("limit", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.runId == '%s')].status", runId).value("COMPLETED"))
                .andExpect(jsonPath("$[?(@.runId == '%s')].workflow", runId).value(workflow))
                .andExpect(jsonPath("$[?(@.runId == '%s')].input.orderId", runId).value("ORD-7"))
                .andExpect(jsonPath("$[?(@.runId == '%s')].steps", runId).value(2))
                // FLAKY fails twice before succeeding.
                .andExpect(jsonPath("$[?(@.runId == '%s')].attempts", runId).value(4))
                .andExpect(jsonPath("$[?(@.runId == '%s')].failedAttempts", runId).value(2));
        mvc.perform(get("/api/runs").param("status", "COMPLETED").param("limit", "5"))
                .andExpect(jsonPath("$[*].status").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("COMPLETED"))));
    }

    @Test
    void showsTheCommandsARunSentThroughTheOutbox() throws Exception {
        String workflow = uniqueWorkflowName();
        definitions.register(workflow, List.of(
                new NewStep("first", TestStepHandlers.OK, 3),
                new NewStep("second", TestStepHandlers.FLAKY, 3)));
        UUID runId = runService.start(workflow, Map.of()).getId();
        awaitTerminal(runId);

        // One command per attempt: first once, second three times (it fails twice).
        mvc.perform(get("/api/runs/{id}/commands", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].stepName").value("first"))
                .andExpect(jsonPath("$[1].stepName").value("second"))
                .andExpect(jsonPath("$[1].attempt").value(1))
                .andExpect(jsonPath("$[3].attempt").value(3))
                .andExpect(jsonPath("$[3].publishedAt").value(notNullValue()))
                .andExpect(jsonPath("$[3].deadline").value(notNullValue()));
        mvc.perform(get("/api/runs/{id}/commands", UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void describesTheEngine() throws Exception {
        String workflow = uniqueWorkflowName();
        definitions.register(workflow, List.of(new NewStep("only", TestStepHandlers.OK, 3)));
        awaitTerminal(runService.start(workflow, Map.of()).getId());

        mvc.perform(get("/api/workflows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(hasItem(workflow)));
        mvc.perform(get("/api/admin/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counters.stepsClaimed").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.database.runs.COMPLETED").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.database.outboxPublished").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.instance").value(notNullValue()));
        mvc.perform(get("/api/admin/config"))
                .andExpect(jsonPath("$.batchSize").value(10))
                .andExpect(jsonPath("$.leaseDuration").value(notNullValue()))
                .andExpect(jsonPath("$.schedulerEnabled").value(false));
        mvc.perform(get("/api/admin/consistency/invariants"))
                .andExpect(jsonPath("$.length()").value(10))
                .andExpect(jsonPath("$[0].name").value("attempt-history-complete"));
    }

    @Test
    void showsWhatKafkaSees() throws Exception {
        mvc.perform(get("/api/admin/kafka"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reachable").value(true))
                .andExpect(jsonPath("$.topics[*].name").value(hasItem("flowforge.step-commands")))
                .andExpect(jsonPath("$.groups[*].groupId").value(hasItem("flowforge-orchestrator")));
    }
}
