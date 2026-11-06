package io.github.louaychayeb.flowforge.orchestrator.run;

import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinition;
import io.github.louaychayeb.flowforge.orchestrator.error.OperationNotAllowedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SIGNAL step's state machine.
 *
 * <pre>
 *   reserve (undo: UNDO) ──▶ approval (SIGNAL, deadline 1 h) ──▶ ship
 * </pre>
 */
class SignalStepTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration DEADLINE = Duration.ofHours(1);

    private WorkflowRun run;
    private StepExecution reserve;
    private StepExecution approval;
    private StepExecution ship;

    @BeforeEach
    void setUp() {
        WorkflowDefinition definition = new WorkflowDefinition("order", 1, T0);
        definition.addStep("reserve", "RESERVE", 3, "UNDO", List.of());
        definition.addSignal("approval", DEADLINE, List.of(0));
        definition.addStep("ship", "SHIP", 3, null, List.of(1));
        run = WorkflowRun.start(definition, Map.of(), T0);
        reserve = run.getSteps().get(0);
        approval = run.getSteps().get(1);
        ship = run.getSteps().get(2);
    }

    @Test
    void itWaitsUntilItsDependenciesCompleteAndThenUntilTheDeadline() {
        assertThat(approval.isSignal()).isTrue();
        assertThat(approval.isTimer()).isFalse();
        assertThat(approval.isAwaitingSignal()).as("not before reserve completes").isFalse();

        succeed(reserve, T0.plusSeconds(1));

        assertThat(approval.isAwaitingSignal()).isTrue();
        assertThat(approval.getNextAttemptAt()).as("the deadline").isEqualTo(T0.plusSeconds(1).plus(DEADLINE));
    }

    @Test
    void aSignalCompletesTheStepWithItsPayloadAndTheRunGoesOn() {
        succeed(reserve, T0.plusSeconds(1));

        approval.deliverSignal(Map.of("approvedBy", "manager"), T0.plusSeconds(60));

        assertThat(approval.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(approval.getOutput()).containsEntry("approvedBy", "manager");
        assertThat(approval.getAttempts()).singleElement()
                .satisfies(a -> assertThat(a.getStatus()).isEqualTo(AttemptStatus.SUCCEEDED));
        assertThat(ship.getNextAttemptAt()).as("the next step is ready").isEqualTo(T0.plusSeconds(60));
    }

    @Test
    void aSignalBeforeTheStepIsDueIsRefused() {
        assertThatThrownBy(() -> approval.deliverSignal(Map.of(), T0))
                .isInstanceOf(OperationNotAllowedException.class)
                .hasMessageContaining("not yet due");
        assertThat(approval.getAttemptCount()).isZero();
    }

    @Test
    void aSecondSignalIsRefused() {
        succeed(reserve, T0.plusSeconds(1));
        approval.deliverSignal(Map.of(), T0.plusSeconds(2));

        assertThatThrownBy(() -> approval.deliverSignal(Map.of(), T0.plusSeconds(3)))
                .isInstanceOf(OperationNotAllowedException.class);
    }

    @Test
    void aMissedDeadlineFailsTheStepAndCompensatesWhatCompleted() {
        succeed(reserve, T0.plusSeconds(1));
        Instant deadline = approval.getNextAttemptAt();

        StepAttempt attempt = approval.start(deadline, LEASE);
        approval.missSignal(attempt.getAttemptNumber(), deadline);

        assertThat(approval.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(approval.getAttempts().get(0).getErrorMessage()).contains("No signal approval within PT1H");
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPENSATING);
        assertThat(run.getSteps()).hasSize(4);
        assertThat(run.getSteps().get(3).getKind()).isEqualTo(StepKind.COMPENSATION);
    }

    @Test
    void aCancelledRunNoLongerAcceptsTheSignal() {
        succeed(reserve, T0.plusSeconds(1));
        run.cancel(T0.plusSeconds(2));

        assertThat(approval.isAwaitingSignal()).isFalse();
        assertThatThrownBy(() -> approval.deliverSignal(Map.of(), T0.plusSeconds(3)))
                .isInstanceOf(OperationNotAllowedException.class);
    }

    private static void succeed(StepExecution step, Instant at) {
        step.start(at.minusMillis(100), LEASE);
        step.complete(step.getAttemptCount(), at);
    }
}
