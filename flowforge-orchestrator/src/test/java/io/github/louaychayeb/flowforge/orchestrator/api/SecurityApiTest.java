package io.github.louaychayeb.flowforge.orchestrator.api;

import io.github.louaychayeb.flowforge.orchestrator.IntegrationTest;
import io.github.louaychayeb.flowforge.orchestrator.TestStepHandlers;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinitionService;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinitionService.NewStep;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Reading and starting are open; changing someone's run needs the OPERATOR role, and the audit names who did it. */
class SecurityApiTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    WorkflowDefinitionService definitions;

    @Test
    void operatorActionsNeedCredentials() throws Exception {
        UUID runId = waitingRun();

        mvc.perform(post("/api/runs/{id}/cancel", runId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.type").value("urn:flowforge:problem:authentication-required"))
                // no challenge: a browser must not pop its own login dialog over the dashboard's
                .andExpect(header().doesNotExist("WWW-Authenticate"));
        mvc.perform(post("/api/runs/{id}/cancel", runId).with(httpBasic("operator", "wrong")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/runs/{id}/signals/approval", runId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aUserWithoutTheOperatorRoleIsRefused() throws Exception {
        UUID runId = waitingRun();

        mvc.perform(post("/api/runs/{id}/cancel", runId).with(user("viewer").roles("VIEWER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void theAuditTrailRecordsTheSignedInOperator() throws Exception {
        UUID runId = waitingRun();

        mvc.perform(post("/api/runs/{id}/cancel", runId).with(httpBasic("operator", "flowforge")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operatorActions[0].actor").value("operator"));
    }

    @Test
    void readingAndStartingStayOpen() throws Exception {
        UUID runId = waitingRun();
        mvc.perform(get("/api/runs/{id}", runId)).andExpect(status().isOk());
        mvc.perform(get("/api/runs")).andExpect(status().isOk());
        mvc.perform(get("/api/admin/stats")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void meNamesTheSignedInUserAndTheirRoles() throws Exception {
        mvc.perform(get("/api/auth/me").with(httpBasic("operator", "flowforge")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("operator"))
                .andExpect(jsonPath("$.roles[0]").value("OPERATOR"));
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    /** A run parked on a long timer, so it stays RUNNING for as long as a test needs. */
    private UUID waitingRun() {
        String workflow = uniqueWorkflowName();
        definitions.register(workflow, List.of(NewStep.timer("hold", Duration.ofHours(1), List.of())));
        return runService.start(workflow, Map.of()).getId();
    }
}
