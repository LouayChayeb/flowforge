package io.github.louaychayeb.flowforge.orchestrator.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.louaychayeb.flowforge.orchestrator.IntegrationTest;
import io.github.louaychayeb.flowforge.orchestrator.TestStepHandlers;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinitionService;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinitionService.NewStep;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The same order submitted twice starts one run: an Idempotency-Key makes a start safe to repeat. */
class IdempotentStartApiTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    WorkflowDefinitionService definitions;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aRepeatedKeyReturnsTheRunItAlreadyStarted() throws Exception {
        String workflow = registered();
        String key = "order-" + UUID.randomUUID();

        String first = start(workflow, key, "{\"orderId\": \"A-1\", \"amount\": 120}")
                .andExpect(status().isAccepted())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andReturn().getResponse().getContentAsString();
        UUID runId = UUID.fromString(objectMapper.readTree(first).get("runId").asText());

        // The same request again, with its fields in another order: still the same request.
        start(workflow, key, "{\"amount\": 120, \"orderId\": \"A-1\"}")
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(header().string("Location", containsString(runId.toString())))
                .andExpect(jsonPath("$.runId").value(runId.toString()));

        assertThat(runsWithKey(key)).isEqualTo(1);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/runs/{id}", runId))
                .andExpect(jsonPath("$.idempotencyKey").value(key));
    }

    @Test
    void aKeyReusedForADifferentOrderIsRefused() throws Exception {
        String workflow = registered();
        String key = "order-" + UUID.randomUUID();
        start(workflow, key, "{\"orderId\": \"B-1\", \"amount\": 120}").andExpect(status().isAccepted());

        start(workflow, key, "{\"orderId\": \"B-1\", \"amount\": 999}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
                .andExpect(jsonPath("$.idempotencyKey").value(key))
                .andExpect(jsonPath("$.runId").exists());
        assertThat(runsWithKey(key)).isEqualTo(1);
    }

    @Test
    void concurrentRequestsWithOneKeyStartOneRun() throws Exception {
        String workflow = registered();
        String key = "order-" + UUID.randomUUID();
        int requests = 8;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        try {
            List<Callable<MvcResult>> calls = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                calls.add(() -> start(workflow, key, "{\"orderId\": \"C-1\"}").andReturn());
            }
            Set<String> runIds = new HashSet<>();
            int created = 0;
            for (Future<MvcResult> result : pool.invokeAll(calls)) {
                MvcResult response = result.get();
                assertThat(response.getResponse().getStatus()).isIn(200, 202);
                if (response.getResponse().getStatus() == 202) {
                    created++;
                }
                JsonNode body = objectMapper.readTree(response.getResponse().getContentAsString());
                runIds.add(body.get("runId").asText());
            }
            assertThat(created).as("exactly one request created the run").isEqualTo(1);
            assertThat(runIds).as("every request got the same run").hasSize(1);
            assertThat(runsWithKey(key)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void withoutAKeyEveryRequestStartsANewRun() throws Exception {
        String workflow = registered();
        mvc.perform(post("/api/workflows/{name}/runs", workflow).contentType(MediaType.APPLICATION_JSON)
                .content("{\"input\": {\"orderId\": \"D-1\"}}")).andExpect(status().isAccepted());
        mvc.perform(post("/api/workflows/{name}/runs", workflow).contentType(MediaType.APPLICATION_JSON)
                .content("{\"input\": {\"orderId\": \"D-1\"}}")).andExpect(status().isAccepted());
    }

    @Test
    void anEmptyOrOverlongKeyIsMalformed() throws Exception {
        String workflow = registered();
        start(workflow, " ", "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        start(workflow, "k".repeat(201), "{}").andExpect(status().isBadRequest());
    }

    private String registered() {
        String workflow = uniqueWorkflowName();
        definitions.register(workflow, List.of(new NewStep("only", TestStepHandlers.OK, 3)));
        return workflow;
    }

    private org.springframework.test.web.servlet.ResultActions start(String workflow, String key, String input)
            throws Exception {
        return mvc.perform(post("/api/workflows/{name}/runs", workflow).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"input\": " + input + "}"));
    }

    private int runsWithKey(String key) {
        return jdbc.queryForObject("SELECT count(*) FROM workflow_run WHERE idempotency_key = ?", Integer.class, key);
    }
}
