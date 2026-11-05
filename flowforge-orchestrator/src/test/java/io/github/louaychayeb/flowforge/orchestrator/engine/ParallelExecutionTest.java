package io.github.louaychayeb.flowforge.orchestrator.engine;

import io.github.louaychayeb.flowforge.orchestrator.IntegrationTest;
import io.github.louaychayeb.flowforge.orchestrator.TestStepHandlers;
import io.github.louaychayeb.flowforge.orchestrator.api.RunDtos.RunResponse;
import io.github.louaychayeb.flowforge.orchestrator.api.RunDtos.StepExecutionResponse;
import io.github.louaychayeb.flowforge.orchestrator.consistency.ConsistencyChecker;
import io.github.louaychayeb.flowforge.orchestrator.definition.InvalidWorkflowDefinitionException;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinitionService;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinitionService.NewStep;
import io.github.louaychayeb.flowforge.orchestrator.run.RunStatus;
import io.github.louaychayeb.flowforge.orchestrator.run.StepKind;
import io.github.louaychayeb.flowforge.orchestrator.run.StepStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parallel steps end to end, through Kafka and the real database.
 */
class ParallelExecutionTest extends IntegrationTest {

    @Autowired
    WorkflowDefinitionService definitions;

    @Autowired
    ConsistencyChecker checker;

    @Test
    void independentStepsRunInParallelAndTheJoinWaitsForBoth() {
        String workflow = uniqueWorkflowName();
        definitions.register(workflow, List.of(
                new NewStep("start", TestStepHandlers.OK, 3, null, List.of()),
                new NewStep("left", TestStepHandlers.OK, 3, null, List.of("start")),
                new NewStep("right", TestStepHandlers.FLAKY, 3, null, List.of("start")),
                new NewStep("join", TestStepHandlers.OK, 3, null, List.of("left", "right"))));
        UUID runId = runService.start(workflow, Map.of()).getId();

        RunResponse run = awaitTerminal(runId);
        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        // Both branches became eligible together and were claimed in the same transaction: same start instant.
        assertThat(run.steps().get(1).attempts().get(0).startedAt())
                .isEqualTo(run.steps().get(2).attempts().get(0).startedAt());
        StepExecutionResponse join = run.steps().get(3);
        assertThat(join.dependsOn()).containsExactly("left", "right");
        assertThat(join.attempts().get(0).startedAt())
                .isAfterOrEqualTo(run.steps().get(2).attempts().get(2).finishedAt())
                .isAfterOrEqualTo(run.steps().get(1).attempts().get(0).finishedAt());
        assertThat(checker.check(workflow).violations()).isEmpty();
    }

    @Test
    void aParallelFailureUndoesTheSiblingThatSucceeded() {
        String workflow = uniqueWorkflowName();
        definitions.register(workflow, List.of(
                new NewStep("start", TestStepHandlers.OK, 3, TestStepHandlers.UNDO, List.of()),
                new NewStep("reserve", TestStepHandlers.OK, 3, TestStepHandlers.UNDO, List.of("start")),
                new NewStep("fraud", TestStepHandlers.PERMANENT, 3, null, List.of("start")),
                new NewStep("charge", TestStepHandlers.OK, 3, TestStepHandlers.UNDO, List.of("reserve", "fraud"))));
        UUID runId = runService.start(workflow, Map.of()).getId();

        RunResponse run = awaitTerminal(runId);

        assertThat(run.status()).isEqualTo(RunStatus.COMPENSATED);
        assertThat(run.steps().get(3).status()).as("charge never started").isEqualTo(StepStatus.PENDING);
        List<StepExecutionResponse> undo = run.steps().stream().filter(s -> s.kind() == StepKind.COMPENSATION).toList();
        // reserve completed after start, so it is undone first.
        assertThat(undo).extracting(StepExecutionResponse::name).containsExactly("reserve", "start");
        assertThat(checker.check(workflow).violations()).isEmpty();
    }

    @Test
    void definitionsMayOnlyDependOnEarlierSteps() {
        assertThatThrownBy(() -> definitions.register(uniqueWorkflowName(), List.of(
                new NewStep("a", TestStepHandlers.OK, 1, null, List.of("b")),
                new NewStep("b", TestStepHandlers.OK, 1, null, List.of()))))
                .isInstanceOf(InvalidWorkflowDefinitionException.class)
                .hasMessageContaining("not a step declared before it");
        assertThatThrownBy(() -> definitions.register(uniqueWorkflowName(), List.of(
                new NewStep("a", TestStepHandlers.OK, 1, null, List.of("a")))))
                .isInstanceOf(InvalidWorkflowDefinitionException.class);
    }
}
