package io.github.louaychayeb.flowforge.orchestrator.consistency;

import io.github.louaychayeb.flowforge.orchestrator.IntegrationTest;
import io.github.louaychayeb.flowforge.orchestrator.TestStepHandlers;
import io.github.louaychayeb.flowforge.orchestrator.api.RunDtos.RunResponse;
import io.github.louaychayeb.flowforge.orchestrator.consistency.ConsistencyReport.Violation;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinitionService;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinitionService.NewStep;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The checker must pass healthy histories and catch corrupted ones; otherwise a clean report proves nothing.
 */
class ConsistencyCheckerTest extends IntegrationTest {

    @Autowired
    ConsistencyChecker checker;

    @Autowired
    WorkflowDefinitionService definitions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvc mvc;

    @Test
    void passesHealthyHistoriesIncludingRetriesAndCompensation() throws Exception {
        String workflow = uniqueWorkflowName();
        definitions.register(workflow, List.of(
                new NewStep("reserve", TestStepHandlers.OK, null, TestStepHandlers.UNDO),
                new NewStep("flaky", TestStepHandlers.FLAKY, 3),
                new NewStep("finish", TestStepHandlers.FAIL_IF_FLAGGED, null)));
        awaitTerminal(runService.start(workflow, Map.of("fail", false)).getId());
        awaitTerminal(runService.start(workflow, Map.of("fail", true)).getId());

        assertThat(checker.check(workflow).violations()).isEmpty();
        mvc.perform(get("/api/admin/consistency").param("workflow", workflow))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(true))
                .andExpect(jsonPath("$.invariantsChecked").value(ConsistencyChecker.INVARIANTS.size()));
    }

    @Test
    void detectsASecondSuccessfulAttempt() {
        String workflow = uniqueWorkflowName();
        RunResponse run = completedRun(workflow);
        UUID stepId = run.steps().get(0).stepExecutionId();

        jdbc.update("""
                INSERT INTO step_attempt (id, step_execution_id, attempt_number, status, started_at, finished_at,
                                          lock_version)
                VALUES (?, ?, 2, 'SUCCEEDED', ?, ?, 0)
                """, UUID.randomUUID(), stepId, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));

        assertThat(violatedInvariants(workflow)).contains("attempt-history-complete", "exactly-one-success",
                "command-for-every-attempt");
    }

    @Test
    void detectsAStepThatRanAheadOfTheRun() {
        String workflow = uniqueWorkflowName();
        definitions.register(workflow, List.of(
                new NewStep("first", TestStepHandlers.OK, null),
                new NewStep("second", TestStepHandlers.OK, null)));
        UUID runId = runService.start(workflow, Map.of()).getId();
        UUID second = load(runId).steps().get(1).stepExecutionId();

        // Far in the future, so the engine running other tests never actually claims this corrupted step.
        jdbc.update("UPDATE step_execution SET next_attempt_at = now() + interval '100 years' WHERE id = ?", second);

        assertThat(violatedInvariants(workflow)).containsExactly("no-step-ahead-of-run");
    }

    @Test
    void detectsACompletedRunWithUnfinishedSteps() {
        String workflow = uniqueWorkflowName();
        RunResponse run = completedRun(workflow);

        jdbc.update("UPDATE step_execution SET status = 'FAILED' WHERE id = ?", run.steps().get(1).stepExecutionId());

        assertThat(violatedInvariants(workflow)).contains("completed-run-is-complete", "exactly-one-success");
    }

    private RunResponse completedRun(String workflow) {
        definitions.register(workflow, List.of(
                new NewStep("first", TestStepHandlers.OK, null),
                new NewStep("second", TestStepHandlers.OK, null)));
        RunResponse run = awaitTerminal(runService.start(workflow, Map.of()).getId());
        assertThat(checker.check(workflow).violations()).isEmpty();
        return run;
    }

    private List<String> violatedInvariants(String workflow) {
        return checker.check(workflow).violations().stream().map(Violation::invariant).toList();
    }
}
