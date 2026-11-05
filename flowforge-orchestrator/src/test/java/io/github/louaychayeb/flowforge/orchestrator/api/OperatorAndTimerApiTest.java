package io.github.louaychayeb.flowforge.orchestrator.api;

import io.github.louaychayeb.flowforge.orchestrator.IntegrationTest;
import io.github.louaychayeb.flowforge.orchestrator.TestStepHandlers;
import io.github.louaychayeb.flowforge.orchestrator.api.RunDtos.RunResponse;
import io.github.louaychayeb.flowforge.orchestrator.api.RunDtos.StepExecutionResponse;
import io.github.louaychayeb.flowforge.orchestrator.consistency.ConsistencyChecker;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinitionService;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinitionService.NewStep;
import io.github.louaychayeb.flowforge.orchestrator.run.AttemptStatus;
import io.github.louaychayeb.flowforge.orchestrator.run.RunStatus;
import io.github.louaychayeb.flowforge.orchestrator.run.StepKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Step outputs, durable timers and operator actions end to end — through the API, Kafka and the database — plus the
 * shape of the error responses.
 */
class OperatorAndTimerApiTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    WorkflowDefinitionService definitions;

    @Autowired
    ConsistencyChecker checker;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void closeTheUndoGate() {
        TestStepHandlers.UNDO_ALLOWED.set(false);
    }

    @Test
    void outputsFlowFromOneStepToTheNext() {
        String workflow = uniqueWorkflowName();
        definitions.register(workflow, List.of(
                new NewStep("producer", TestStepHandlers.PRODUCE, 3),
                new NewStep("consumer", TestStepHandlers.CONSUME, 3)));

        RunResponse run = awaitTerminal(runService.start(workflow, Map.of()).getId());

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.steps().get(0).output()).containsEntry("producedBy", "producer");
        assertThat(run.steps().get(1).output()).containsEntry("echo", "producer");
    }

    @Test
    void aTimerWaitsThenFiresWithoutAWorker() {
        String workflow = uniqueWorkflowName();
        definitions.register(workflow, List.of(
                new NewStep("before", TestStepHandlers.OK, 3),
                NewStep.timer("pause", Duration.ofMillis(800), List.of("before")),
                new NewStep("after", TestStepHandlers.OK, 3, null, List.of("pause"))));

        RunResponse run = awaitTerminal(runService.start(workflow, Map.of()).getId());

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        StepExecutionResponse before = run.steps().get(0);
        StepExecutionResponse pause = run.steps().get(1);
        StepExecutionResponse after = run.steps().get(2);
        assertThat(pause.waitFor()).isEqualTo(Duration.ofMillis(800));
        Instant fired = pause.attempts().get(0).startedAt();
        assertThat(fired).isAfterOrEqualTo(before.attempts().get(0).finishedAt().plusMillis(800));
        assertThat(after.attempts().get(0).startedAt()).isAfterOrEqualTo(fired);
        Integer commands = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE payload ->> 'stepExecutionId' = ?", Integer.class,
                pause.stepExecutionId().toString());
        assertThat(commands).as("a timer sends no command").isZero();
        assertThat(checker.check(workflow).violations()).isEmpty();
    }

    @Test
    void cancellingDuringATimerUndoesWhatCompletedAndEndsCancelled() throws Exception {
        String workflow = uniqueWorkflowName();
        definitions.register(workflow, List.of(
                new NewStep("reserve", TestStepHandlers.OK, 3, TestStepHandlers.UNDO),
                NewStep.timer("cooling-off", Duration.ofHours(1), List.of("reserve")),
                new NewStep("ship", TestStepHandlers.OK, 3, null, List.of("cooling-off"))));
        UUID runId = runService.start(workflow, Map.of()).getId();
        awaitTimerScheduled(runId);

        mvc.perform(post("/api/runs/{id}/cancel", runId).header("X-Actor", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"customer changed their mind\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.cancelRequestedAt").exists())
                .andExpect(jsonPath("$.operatorActions[0].action").value("CANCEL"))
                .andExpect(jsonPath("$.operatorActions[0].actor").value("alice"))
                .andExpect(jsonPath("$.operatorActions[0].reason").value("customer changed their mind"));

        RunResponse run = awaitTerminal(runId);
        assertThat(run.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(run.steps()).filteredOn(s -> s.kind() == StepKind.COMPENSATION)
                .extracting(StepExecutionResponse::name).containsExactly("reserve");
        assertThat(run.steps().get(1).attemptCount()).as("the timer never fired").isZero();
        assertThat(checker.check(workflow).violations()).isEmpty();

        // A finished run cannot be cancelled again: a clear 409 with a stable code.
        mvc.perform(post("/api/runs/{id}/cancel", runId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPERATION_NOT_ALLOWED"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.runStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.type").value("urn:flowforge:problem:operation-not-allowed"));
    }

    @Test
    void anOperatorRetriesACompensationThatFailed() throws Exception {
        String workflow = uniqueWorkflowName();
        definitions.register(workflow, List.of(
                new NewStep("charge", TestStepHandlers.OK, 3, TestStepHandlers.UNDO_WHEN_ALLOWED),
                new NewStep("ship", TestStepHandlers.PERMANENT, 3)));
        UUID runId = runService.start(workflow, Map.of()).getId();
        assertThat(awaitTerminal(runId).status()).isEqualTo(RunStatus.COMPENSATION_FAILED);

        // The cause is fixed (say, the payment provider is back); an operator retries.
        TestStepHandlers.UNDO_ALLOWED.set(true);
        mvc.perform(post("/api/runs/{id}/retry", runId).header("X-Actor", "bob"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("COMPENSATING"))
                .andExpect(jsonPath("$.operatorActions[0].action").value("RETRY_COMPENSATION"))
                .andExpect(jsonPath("$.operatorActions[0].runStatusBefore").value("COMPENSATION_FAILED"));

        RunResponse run = awaitTerminal(runId);
        assertThat(run.status()).isEqualTo(RunStatus.COMPENSATED);
        StepExecutionResponse undo = run.steps().get(2);
        assertThat(undo.attempts()).extracting(RunDtos.AttemptResponse::status)
                .containsExactly(AttemptStatus.PERMANENT_FAILURE, AttemptStatus.SUCCEEDED);
        assertThat(checker.check(workflow).violations()).isEmpty();
    }

    @Test
    void errorsAreProblemDetailsWithStableCodes() throws Exception {
        UUID unknown = UUID.randomUUID();
        mvc.perform(get("/api/runs/{id}", unknown))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"))
                .andExpect(jsonPath("$.runId").value(unknown.toString()));
        mvc.perform(post("/api/runs/{id}/retry", unknown))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/runs").param("status", "SLEEPING"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mvc.perform(post("/api/workflows").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mvc.perform(post("/api/workflows").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Bad Name", "steps": [{"name": "", "type": "lower"}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(3))
                .andExpect(jsonPath("$.detail").value(startsWith("3 fields")));
        mvc.perform(post("/api/workflows").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "%s", "steps": [{"name": "wait", "type": "WAIT"}]}""".formatted(uniqueWorkflowName())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_WORKFLOW_DEFINITION"))
                .andExpect(jsonPath("$.step").value("wait"));
    }

    /** Drives the engine until the run's timer is scheduled (its dependency completed). */
    private void awaitTimerScheduled(UUID runId) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50)).until(() -> {
            dispatcher.dispatchOnce();
            outboxPublisher.publishPending();
            return load(runId).steps().get(1).nextAttemptAt() != null;
        });
    }
}
