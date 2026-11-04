package io.github.louaychayeb.flowforge.orchestrator.engine;

import io.github.louaychayeb.flowforge.orchestrator.IntegrationTest;
import io.github.louaychayeb.flowforge.orchestrator.TestStepHandlers;
import io.github.louaychayeb.flowforge.orchestrator.api.RunDtos.AttemptResponse;
import io.github.louaychayeb.flowforge.orchestrator.api.RunDtos.RunResponse;
import io.github.louaychayeb.flowforge.orchestrator.api.RunDtos.StepExecutionResponse;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinitionService;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinitionService.NewStep;
import io.github.louaychayeb.flowforge.orchestrator.outbox.OutboxEvent;
import io.github.louaychayeb.flowforge.orchestrator.outbox.OutboxEventRepository;
import io.github.louaychayeb.flowforge.orchestrator.run.AttemptStatus;
import io.github.louaychayeb.flowforge.orchestrator.run.RunStatus;
import io.github.louaychayeb.flowforge.orchestrator.run.StepKind;
import io.github.louaychayeb.flowforge.orchestrator.run.StepStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Failure scenarios: each test stops the engine between two of its loops, the way a crash would, and
 * checks that the run still ends correctly once the engine resumes.
 */
class RecoveryTest extends IntegrationTest {

    @Autowired
    WorkflowDefinitionService definitions;

    @Autowired
    StepStateService stateService;

    @Autowired
    OutboxEventRepository outbox;

    @Test
    void commandsSurviveACrashBetweenClaimAndPublish() {
        UUID runId = startRun(new NewStep("only", TestStepHandlers.OK, null));

        // The claim commits, then the orchestrator "dies" before publishing anything.
        dispatcher.dispatchOnce();
        assertThat(load(runId).steps().get(0).status()).isEqualTo(StepStatus.RUNNING);
        assertThat(outbox.findByMessageKeyAndPublishedAtIsNull(runId.toString()))
                .as("the command is durable in the outbox").hasSize(1);

        // After a restart, the outbox publisher sends the command that was committed with the claim.
        RunResponse run = awaitTerminal(runId);

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.steps().get(0).attemptCount()).isEqualTo(1);
        assertThat(outbox.findByMessageKeyAndPublishedAtIsNull(runId.toString())).isEmpty();
    }

    @Test
    void retriesStepsWhoseResultNeverArrives() {
        UUID runId = startRun(new NewStep("lost", TestStepHandlers.OK, 3));
        dispatcher.dispatchOnce();

        // The command is lost: no worker will ever answer attempt 1.
        List<OutboxEvent> pending = outbox.findByMessageKeyAndPublishedAtIsNull(runId.toString());
        outbox.deleteAll(pending);

        // Once the lease has expired, recovery times the attempt out and schedules a retry.
        int recovered = stateService.recoverExpiredLeases(Instant.now().plus(Duration.ofHours(1)));
        assertThat(recovered).isGreaterThanOrEqualTo(1);
        StepExecutionResponse step = load(runId).steps().get(0);
        assertThat(step.status()).isEqualTo(StepStatus.RETRY_WAIT);
        assertThat(step.attempts().get(0).status()).isEqualTo(AttemptStatus.TIMED_OUT);

        RunResponse run = awaitTerminal(runId);

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.steps().get(0).attempts()).extracting(AttemptResponse::status)
                .containsExactly(AttemptStatus.TIMED_OUT, AttemptStatus.SUCCEEDED);
    }

    @Test
    void compensatesCompletedStepsInReverseOrderWhenALaterStepFails() {
        UUID runId = startRun(
                new NewStep("reserve", TestStepHandlers.OK, null, TestStepHandlers.UNDO),
                new NewStep("notify", TestStepHandlers.OK, null),
                new NewStep("charge", TestStepHandlers.OK, null, TestStepHandlers.UNDO),
                new NewStep("ship", TestStepHandlers.PERMANENT, null));

        RunResponse run = awaitTerminal(runId);

        assertThat(run.status()).isEqualTo(RunStatus.COMPENSATED);
        assertThat(run.steps()).extracting(StepExecutionResponse::kind).containsExactly(
                StepKind.FORWARD, StepKind.FORWARD, StepKind.FORWARD, StepKind.FORWARD,
                StepKind.COMPENSATION, StepKind.COMPENSATION);
        List<StepExecutionResponse> compensations = run.steps().subList(4, 6);
        assertThat(compensations).extracting(StepExecutionResponse::name).containsExactly("charge", "reserve");
        assertThat(compensations).extracting(StepExecutionResponse::status).containsOnly(StepStatus.COMPLETED);
        assertThat(run.steps().get(3).status()).isEqualTo(StepStatus.FAILED);
    }

    @Test
    void stopsWhenACompensationFails() {
        UUID runId = startRun(
                new NewStep("reserve", TestStepHandlers.OK, 2, TestStepHandlers.PERMANENT),
                new NewStep("charge", TestStepHandlers.PERMANENT, null));

        RunResponse run = awaitTerminal(runId);

        assertThat(run.status()).isEqualTo(RunStatus.COMPENSATION_FAILED);
        StepExecutionResponse compensation = run.steps().get(2);
        assertThat(compensation.kind()).isEqualTo(StepKind.COMPENSATION);
        assertThat(compensation.status()).isEqualTo(StepStatus.FAILED);
    }

    private UUID startRun(NewStep... steps) {
        String name = uniqueWorkflowName();
        definitions.register(name, List.of(steps));
        return runService.start(name, Map.of("orderId", "ORD-1")).getId();
    }
}
