package io.github.louaychayeb.flowforge.orchestrator.api;

import io.github.louaychayeb.flowforge.orchestrator.IntegrationTest;
import io.github.louaychayeb.flowforge.orchestrator.TestStepHandlers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The REST API end to end, with steps executed by the test worker over Kafka.
 */
class WorkflowApiTest extends IntegrationTest {

    private static final String STEPS = """
            [
              {"name": "validate", "type": "%1$s"},
              {"name": "reserve",  "type": "%1$s"},
              {"name": "charge",   "type": "%1$s", "maxAttempts": 5}
            ]
            """.formatted(TestStepHandlers.OK);

    @Autowired
    MockMvc mvc;

    @Test
    void registersVersionedDefinitions() throws Exception {
        String name = uniqueWorkflowName();

        register(name, STEPS)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/workflows/" + name)))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.steps.length()").value(3))
                .andExpect(jsonPath("$.steps[0].maxAttempts").value(3))
                .andExpect(jsonPath("$.steps[2].maxAttempts").value(5));
        register(name, STEPS).andExpect(jsonPath("$.version").value(2));

        mvc.perform(get("/api/workflows/{name}", name))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void startsARunAndReportsItsProgress() throws Exception {
        String name = uniqueWorkflowName();
        register(name, STEPS).andExpect(status().isCreated());

        UUID runId = startRun(name, """
                {"input": {"orderId": "ORD-1042", "customerId": "CUS-91", "amount": 249.90}}
                """);
        mvc.perform(get("/api/runs/{id}", runId))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.currentStep").value("validate"))
                .andExpect(jsonPath("$.steps[0].status").value("PENDING"));

        awaitTerminal(runId);

        mvc.perform(get("/api/runs/{id}", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.currentStep").doesNotExist())
                .andExpect(jsonPath("$.input.orderId").value("ORD-1042"))
                .andExpect(jsonPath("$.steps[2].name").value("charge"))
                .andExpect(jsonPath("$.steps[2].status").value("COMPLETED"))
                .andExpect(jsonPath("$.steps[2].attempts[0].status").value("SUCCEEDED"));
    }

    @Test
    void reportsWhereARunFailed() throws Exception {
        String name = uniqueWorkflowName();
        register(name, """
                [
                  {"name": "validate", "type": "%s"},
                  {"name": "charge",   "type": "%s"},
                  {"name": "ship",     "type": "%s"}
                ]
                """.formatted(TestStepHandlers.OK, TestStepHandlers.PERMANENT, TestStepHandlers.OK))
                .andExpect(status().isCreated());

        UUID runId = startRun(name, "{\"input\": {}}");
        awaitTerminal(runId);

        mvc.perform(get("/api/runs/{id}", runId))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.steps[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.steps[1].status").value("FAILED"))
                .andExpect(jsonPath("$.steps[1].attemptCount").value(1))
                .andExpect(jsonPath("$.steps[1].attempts[0].errorMessage").value("rejected"))
                .andExpect(jsonPath("$.steps[2].status").value("PENDING"));
    }

    @Test
    void rejectsDuplicateStepNames() throws Exception {
        register(uniqueWorkflowName(), """
                [{"name": "x", "type": "A"}, {"name": "x", "type": "B"}]
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("Duplicate step name x")));
    }

    @Test
    void rejectsInvalidDefinitions() throws Exception {
        mvc.perform(post("/api/workflows").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Not Kebab", "steps": []}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsNotFoundForUnknownWorkflowsAndRuns() throws Exception {
        mvc.perform(post("/api/workflows/{name}/runs", "does-not-exist")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"input\": {}}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/runs/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    private ResultActions register(String name, String steps) throws Exception {
        return mvc.perform(post("/api/workflows").contentType(MediaType.APPLICATION_JSON).content("""
                {"name": "%s", "steps": %s}
                """.formatted(name, steps)));
    }

    private UUID startRun(String name, String body) throws Exception {
        MvcResult result = mvc.perform(post("/api/workflows/{name}/runs", name)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", containsString("/api/runs/")))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andReturn();
        String location = result.getResponse().getHeader("Location");
        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }
}
