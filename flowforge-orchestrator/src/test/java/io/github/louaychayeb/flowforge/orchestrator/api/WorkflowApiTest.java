package io.github.louaychayeb.flowforge.orchestrator.api;

import io.github.louaychayeb.flowforge.orchestrator.IntegrationTest;
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
 * The REST API end to end, using the sample order-processing handlers.
 */
class WorkflowApiTest extends IntegrationTest {

    private static final String ORDER_STEPS = """
            [
              {"name": "validate-order",    "type": "VALIDATE_ORDER"},
              {"name": "reserve-inventory", "type": "RESERVE_INVENTORY"},
              {"name": "charge-payment",    "type": "CHARGE_PAYMENT", "maxAttempts": 5},
              {"name": "create-shipment",   "type": "CREATE_SHIPMENT"},
              {"name": "send-confirmation", "type": "SEND_CONFIRMATION"}
            ]
            """;

    @Autowired
    MockMvc mvc;

    @Test
    void registersVersionedDefinitions() throws Exception {
        String name = uniqueWorkflowName();

        register(name)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/workflows/" + name)))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.steps.length()").value(5))
                .andExpect(jsonPath("$.steps[0].maxAttempts").value(3))
                .andExpect(jsonPath("$.steps[2].maxAttempts").value(5));
        register(name).andExpect(jsonPath("$.version").value(2));

        mvc.perform(get("/api/workflows/{name}", name))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void executesTheOrderProcessingWorkflow() throws Exception {
        String name = uniqueWorkflowName();
        register(name).andExpect(status().isCreated());

        String runId = startRun(name, """
                {"input": {"orderId": "ORD-1042", "customerId": "CUS-91", "amount": 249.90}}
                """);
        mvc.perform(get("/api/runs/{id}", runId))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.currentStep").value("validate-order"))
                .andExpect(jsonPath("$.steps[0].status").value("PENDING"));

        runUntilIdle();

        mvc.perform(get("/api/runs/{id}", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.currentStep").doesNotExist())
                .andExpect(jsonPath("$.input.orderId").value("ORD-1042"))
                .andExpect(jsonPath("$.steps[4].name").value("send-confirmation"))
                .andExpect(jsonPath("$.steps[4].status").value("COMPLETED"));
    }

    @Test
    void stopsAtADeclinedPayment() throws Exception {
        String name = uniqueWorkflowName();
        register(name).andExpect(status().isCreated());

        String runId = startRun(name, """
                {"input": {"orderId": "ORD-9", "customerId": "CUS-1", "amount": 50000}}
                """);
        runUntilIdle();

        mvc.perform(get("/api/runs/{id}", runId))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.steps[1].status").value("COMPLETED"))
                .andExpect(jsonPath("$.steps[2].status").value("FAILED"))
                .andExpect(jsonPath("$.steps[2].attemptCount").value(1))
                .andExpect(jsonPath("$.steps[2].attempts[0].errorMessage", containsString("declined")))
                .andExpect(jsonPath("$.steps[3].status").value("PENDING"));
    }

    @Test
    void rejectsUnknownStepTypes() throws Exception {
        mvc.perform(post("/api/workflows").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "%s", "steps": [{"name": "x", "type": "LAUNCH_ROCKET"}]}
                        """.formatted(uniqueWorkflowName())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("Unknown step type LAUNCH_ROCKET")));
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

    private ResultActions register(String name) throws Exception {
        return mvc.perform(post("/api/workflows").contentType(MediaType.APPLICATION_JSON).content("""
                {"name": "%s", "steps": %s}
                """.formatted(name, ORDER_STEPS)));
    }

    private String startRun(String name, String body) throws Exception {
        MvcResult result = mvc.perform(post("/api/workflows/{name}/runs", name)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", containsString("/api/runs/")))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andReturn();
        String location = result.getResponse().getHeader("Location");
        return location.substring(location.lastIndexOf('/') + 1);
    }
}
