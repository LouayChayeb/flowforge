package io.github.louaychayeb.flowforge.orchestrator.run;

import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinition;
import io.github.louaychayeb.flowforge.orchestrator.error.ErrorCode;
import io.github.louaychayeb.flowforge.orchestrator.error.OperationNotAllowedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Durable timers, step outputs and operator actions, at the level of the state machine.
 *
 * <pre>
 *   reserve (undo: UNDO) ──▶ cooling-off (WAIT 10 min) ──▶ ship
 * </pre>
 */
class TimersAndOperatorActionsTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration COOLING_OFF = Duration.ofMinutes(10);

    private WorkflowRun run;
    private StepExecution reserve;
    private StepExecution coolingOff;
    private StepExecution ship;

    @BeforeEach
    void setUp() {
        WorkflowDefinition definition = new WorkflowDefinition("order", 1, T0);
        definition.addStep("reserve", "RESERVE", 3, "UNDO", List.of());
        definition.addTimer("cooling-off", COOLING_OFF, List.of(0));
        definition.addStep("ship", "SHIP", 3, null, List.of(1));
        run = WorkflowRun.start(definition, Map.of(), T0);
        reserve = run.getSteps().get(0);
        coolingOff = run.getSteps().get(1);
        ship = run.getSteps().get(2);
    }

    @Nested
    class Timers {

        @Test
        void aTimerIsDueItsWaitAfterItsDependenciesCompleted() {
            succeed(reserve, T0.plusSeconds(1));

            assertThat(coolingOff.isTimer()).isTrue();
            assertThat(coolingOff.getNextAttemptAt()).isEqualTo(T0.plusSeconds(1).plus(COOLING_OFF));
            assertThat(ship.getNextAttemptAt()).isNull();
        }

        @Test
        void whenItFiresTheStepsAfterItBecomeEligible() {
            succeed(reserve, T0.plusSeconds(1));
            Instant due = coolingOff.getNextAttemptAt();

            succeed(coolingOff, due);

            assertThat(ship.getNextAttemptAt()).isEqualTo(due);
        }
    }

    @Nested
    class Outputs {

        @Test
        void aCompletedStepKeepsWhatItProduced() {
            reserve.start(T0, LEASE);
            reserve.complete(1, Map.of("reservationId", "res_1"), T0.plusSeconds(1));

            assertThat(reserve.getOutput()).containsEntry("reservationId", "res_1");
        }

        @Test
        void aStepWithoutOutputHasAnEmptyOne() {
            succeed(reserve, T0.plusSeconds(1));

            assertThat(reserve.getOutput()).isEmpty();
        }
    }

    @Nested
    class Cancel {

        @Test
        void cancellingDuringATimerCancelsTheTimerAndUndoesWhatCompleted() {
            succeed(reserve, T0.plusSeconds(1));

            run.cancel(T0.plusSeconds(2));

            assertThat(coolingOff.getNextAttemptAt()).as("the timer is cancelled").isNull();
            assertThat(run.getStatus()).isEqualTo(RunStatus.COMPENSATING);
            StepExecution undoReserve = run.getSteps().get(3);
            assertThat(undoReserve.getKind()).isEqualTo(StepKind.COMPENSATION);

            succeed(undoReserve, T0.plusSeconds(3));
            assertThat(run.getStatus()).isEqualTo(RunStatus.CANCELLED);
            assertThat(run.getCompletedAt()).isEqualTo(T0.plusSeconds(3));
        }

        @Test
        void aStepAlreadyRunningIsAllowedToFinishFirst() {
            reserve.start(T0, LEASE);

            run.cancel(T0.plusSeconds(1));
            assertThat(run.getStatus()).as("reserve may still succeed").isEqualTo(RunStatus.RUNNING);
            assertThat(run.isCancelling()).isTrue();

            reserve.complete(1, T0.plusSeconds(2));
            assertThat(run.getStatus()).isEqualTo(RunStatus.COMPENSATING);
            assertThat(coolingOff.getNextAttemptAt()).as("nothing new starts").isNull();
        }

        @Test
        void withNothingToUndoTheRunIsCancelledAtOnce() {
            run.cancel(T0);

            assertThat(reserve.getNextAttemptAt()).isNull();
            assertThat(run.getStatus()).isEqualTo(RunStatus.CANCELLED);
        }

        @Test
        void cancellingTwiceIsHarmless() {
            reserve.start(T0, LEASE);
            run.cancel(T0.plusSeconds(1));

            run.cancel(T0.plusSeconds(2));

            assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
        }

        @Test
        void aFinishedRunCannotBeCancelled() {
            run.cancel(T0);

            assertThatThrownBy(() -> run.cancel(T0.plusSeconds(1)))
                    .isInstanceOf(OperationNotAllowedException.class)
                    .satisfies(e -> assertThat(((OperationNotAllowedException) e).code())
                            .isEqualTo(ErrorCode.OPERATION_NOT_ALLOWED))
                    .satisfies(e -> assertThat(((OperationNotAllowedException) e).details())
                            .containsEntry("runStatus", RunStatus.CANCELLED));
        }
    }

    @Nested
    class RetryCompensation {

        @Test
        void anOperatorCanRetryAFailedCompensation() {
            succeed(reserve, T0.plusSeconds(1));
            run.cancel(T0.plusSeconds(2));
            StepExecution undoReserve = run.getSteps().get(3);
            undoReserve.start(T0.plusSeconds(3), LEASE);
            undoReserve.failPermanently(1, "provider down", T0.plusSeconds(4));
            assertThat(run.getStatus()).isEqualTo(RunStatus.COMPENSATION_FAILED);

            run.retryCompensation(3, T0.plusSeconds(60));

            assertThat(run.getStatus()).isEqualTo(RunStatus.COMPENSATING);
            assertThat(run.getCompletedAt()).isNull();
            assertThat(undoReserve.getStatus()).isEqualTo(StepStatus.RETRY_WAIT);
            assertThat(undoReserve.getMaxAttempts()).isEqualTo(4);
            assertThat(undoReserve.getAttempts()).as("history kept").hasSize(1);

            undoReserve.start(T0.plusSeconds(61), LEASE);
            undoReserve.complete(2, T0.plusSeconds(62));
            assertThat(run.getStatus()).isEqualTo(RunStatus.CANCELLED);
        }

        @Test
        void onlyAStuckRunCanBeRetried() {
            assertThatThrownBy(() -> run.retryCompensation(3, T0))
                    .isInstanceOf(OperationNotAllowedException.class)
                    .hasMessageContaining("COMPENSATION_FAILED");
        }
    }

    private static void succeed(StepExecution step, Instant at) {
        step.start(at.minusMillis(100), LEASE);
        step.complete(step.getAttemptCount(), at);
    }
}
