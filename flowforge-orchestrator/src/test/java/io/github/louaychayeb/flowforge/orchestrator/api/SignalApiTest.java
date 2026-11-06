package io.github.louaychayeb.flowforge.orchestrator.api;

import io.github.louaychayeb.flowforge.orchestrator.IntegrationTest;
import io.github.louaychayeb.flowforge.orchestrator.TestStepHandlers;
import io.github.louaychayeb.flowforge.orchestrator.api.RunDtos.RunResponse;
import io.github.louaychayeb.flowforge.orchestrator.api.RunDtos.StepExecutionResponse;
import io.github.louaychayeb.flowforge.orchestrator.consistency.ConsistencyChecker;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinitionService;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinitionService.NewStep;
import io.github.louaychayeb.flowforge.orchestrator.run.RunStatus;
import io.github.louaychayeb.flowforge.orchestrator.run.StepKind;
import io.github.louaychayeb.flowforge.orchestrator.run.StepStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Signals end to end: a run waits at a SIGNAL step until an operator (or a webhook) delivers it, carries its payload
 * on to the next steps, and compensates when the deadline passes first.
 *
 * <pre>
 *   reserve (undo: UNDO) ──▶ approval (SIGNAL) ──▶ consume (reads the approval's output)
 * </pre>
 */
class SignalApiTest extends IntegrationTest {

    static final RequestPostProcessor OPERATOR = httpBasic("operator", "flowforge");

    @Autowired
    MockMvc mvc;

    @Autowired
    WorkflowDefinitionService definitions;

    @Autowired
    ConsistencyChecker checker;

    @Test
    void aDeliveredSignalCompletesTheStepAndHandsOnItsPayload() throws Exception {
        String workflow = approvalWorkflow(Duration.ofHours(1));
        UUID runId = runService.start(workflow, Map.of()).getId();
        awaitWaitingForSignal(runId);

        mvc.perform(post("/api/runs/{id}/signals/{step}", runId, "approval").with(OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\": {\"producedBy\": \"manager\", \"approved\": true}, \"reason\": \"looks fine\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operatorActions[0].action").value("SIGNAL"))
                .andExpect(jsonPath("$.operatorActions[0].actor").value("operator"))
                .andExpect(jsonPath("$.operatorActions[0].reason").value("looks fine"));

        RunResponse run = awaitTerminal(runId);
        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        StepExecutionResponse approval = run.steps().get(1);
        assertThat(approval.output()).containsEntry("approved", true);
        // The step after it read the signal's payload, like any step output.
        assertThat(run.steps().get(2).output()).containsEntry("echo", "manager");
        assertThat(checker.check(workflow).violations()).isEmpty();
    }

    @Test
    void aMissedDeadlineFailsTheStepAndTheRunCompensates() {
        String workflow = approvalWorkflow(Duration.ofMillis(600));
        UUID runId = runService.start(workflow, Map.of()).getId();

        RunResponse run = awaitTerminal(runId);

        assertThat(run.status()).isEqualTo(RunStatus.COMPENSATED);
        StepExecutionResponse approval = run.steps().get(1);
        assertThat(approval.status()).isEqualTo(StepStatus.FAILED);
        assertThat(approval.attempts().get(0).errorMessage()).contains("No signal approval");
        assertThat(run.steps()).filteredOn(s -> s.kind() == StepKind.COMPENSATION)
                .extracting(StepExecutionResponse::name).containsExactly("reserve");
        assertThat(checker.check(workflow).violations()).isEmpty();
    }

    @Test
    void aSignalIsRefusedUnlessTheStepIsWaitingForIt() throws Exception {
        String workflow = uniqueWorkflowName();
        definitions.register(workflow, List.of(
                NewStep.timer("first", Duration.ofHours(1), List.of()),
                NewStep.signal("approval", Duration.ofHours(1), List.of("first"))));
        UUID runId = runService.start(workflow, Map.of()).getId();

        // Too early: its dependency has not completed.
        mvc.perform(post("/api/runs/{id}/signals/{step}", runId, "approval").with(OPERATOR))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPERATION_NOT_ALLOWED"))
                .andExpect(jsonPath("$.stepStatus").value("PENDING"));
        // No such signal step: a timer is not one.
        mvc.perform(post("/api/runs/{id}/signals/{step}", runId, "first").with(OPERATOR))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STEP_NOT_FOUND"));
    }

    @Test
    void aSecondSignalForTheSameStepIsRefused() throws Exception {
        String workflow = approvalWorkflow(Duration.ofHours(1));
        UUID runId = runService.start(workflow, Map.of()).getId();
        awaitWaitingForSignal(runId);
        mvc.perform(post("/api/runs/{id}/signals/{step}", runId, "approval").with(OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"payload\": {\"producedBy\": \"a\"}}"))
                .andExpect(status().isAccepted());

        mvc.perform(post("/api/runs/{id}/signals/{step}", runId, "approval").with(OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"payload\": {\"producedBy\": \"b\"}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.stepStatus").value("COMPLETED"));
    }

    @Test
    void aSignalStepNeedsADeadline() throws Exception {
        mvc.perform(post("/api/workflows").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "%s", "steps": [{"name": "approval", "type": "SIGNAL"}]}""".formatted(uniqueWorkflowName())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_WORKFLOW_DEFINITION"))
                .andExpect(jsonPath("$.step").value("approval"));
    }

    private String approvalWorkflow(Duration deadline) {
        String workflow = uniqueWorkflowName();
        definitions.register(workflow, List.of(
                new NewStep("reserve", TestStepHandlers.OK, 3, TestStepHandlers.UNDO, List.of()),
                NewStep.signal("approval", deadline, List.of("reserve")),
                new NewStep("consume", TestStepHandlers.CONSUME_APPROVAL, 3, null, List.of("approval"))));
        return workflow;
    }

    /** Drives the engine until the signal step is due (its dependency completed): only then is a signal accepted. */
    private void awaitWaitingForSignal(UUID runId) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50)).until(() -> {
            dispatcher.dispatchOnce();
            outboxPublisher.publishPending();
            return load(runId).steps().get(1).nextAttemptAt() != null;
        });
    }
}
