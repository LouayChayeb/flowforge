package io.github.louaychayeb.flowforge.orchestrator.run;

import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * State machine rules of runs, steps and attempts, without a database.
 */
class WorkflowRunTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
    private static final Duration BACKOFF = Duration.ofSeconds(5);
    private static final Duration LEASE = Duration.ofSeconds(30);

    @Nested
    class ForwardExecution {

        private WorkflowRun run;
        private StepExecution first;
        private StepExecution second;

        @BeforeEach
        void setUp() {
            WorkflowDefinition definition = new WorkflowDefinition("order-processing", 1, T0);
            definition.addStep("validate", "VALIDATE_ORDER", 1);
            definition.addStep("charge", "CHARGE_PAYMENT", 3);
            run = WorkflowRun.start(definition, Map.of("orderId", "ORD-1"), T0);
            first = run.getSteps().get(0);
            second = run.getSteps().get(1);
        }

        @Test
        void startMakesOnlyTheFirstStepEligible() {
            assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
            assertThat(run.currentStep()).contains(first);
            assertThat(first.getStatus()).isEqualTo(StepStatus.PENDING);
            assertThat(first.getNextAttemptAt()).isEqualTo(T0);
            assertThat(second.getStatus()).isEqualTo(StepStatus.PENDING);
            assertThat(second.getNextAttemptAt()).isNull();
        }

        @Test
        void claimingTakesALease() {
            first.start(T0, LEASE);

            assertThat(first.getStatus()).isEqualTo(StepStatus.RUNNING);
            assertThat(first.getLeaseExpiresAt()).isEqualTo(T0.plus(LEASE));
            assertThat(first.getNextAttemptAt()).isNull();
        }

        @Test
        void completingAStepAdvancesTheRunAndReleasesTheLease() {
            first.start(T0, LEASE);
            first.complete(1, T0.plusSeconds(1));

            assertThat(first.getStatus()).isEqualTo(StepStatus.COMPLETED);
            assertThat(first.getLeaseExpiresAt()).isNull();
            assertThat(first.getAttempts()).singleElement()
                    .extracting(StepAttempt::getStatus).isEqualTo(AttemptStatus.SUCCEEDED);
            assertThat(run.currentStep()).contains(second);
            assertThat(second.getNextAttemptAt()).isEqualTo(T0.plusSeconds(1));
        }

        @Test
        void completingTheLastStepCompletesTheRun() {
            first.start(T0, LEASE);
            first.complete(1, T0);
            second.start(T0, LEASE);
            second.complete(1, T0.plusSeconds(2));

            assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
            assertThat(run.getCompletedAt()).isEqualTo(T0.plusSeconds(2));
            assertThat(run.currentStep()).isEmpty();
        }

        @Test
        void retryableFailureSchedulesARetryWhileAttemptsRemain() {
            first.start(T0, LEASE);
            first.complete(1, T0);
            second.start(T0, LEASE);
            second.failRetryable(1, "timeout", BACKOFF, T0);

            assertThat(second.getStatus()).isEqualTo(StepStatus.RETRY_WAIT);
            assertThat(second.getNextAttemptAt()).isEqualTo(T0.plus(BACKOFF));
            assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);

            second.start(T0.plus(BACKOFF), LEASE);
            second.complete(2, T0.plus(BACKOFF));

            assertThat(second.getAttempts()).extracting(StepAttempt::getStatus)
                    .containsExactly(AttemptStatus.RETRYABLE_FAILURE, AttemptStatus.SUCCEEDED);
            assertThat(second.getAttempts().get(0).getErrorMessage()).isEqualTo("timeout");
            assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        }

        @Test
        void exhaustingAttemptsFailsTheStepAndTheRun() {
            first.start(T0, LEASE);
            first.failRetryable(1, "timeout", BACKOFF, T0);

            assertThat(first.getStatus()).isEqualTo(StepStatus.FAILED);
            assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
            assertThat(second.getStatus()).isEqualTo(StepStatus.PENDING);
            assertThat(second.getNextAttemptAt()).isNull();
        }

        @Test
        void permanentFailureDoesNotRetry() {
            first.start(T0, LEASE);
            first.complete(1, T0);
            second.start(T0, LEASE);
            second.failPermanently(1, "declined", T0);

            assertThat(second.getStatus()).isEqualTo(StepStatus.FAILED);
            assertThat(second.getAttemptCount()).isEqualTo(1);
            assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        }

        @Test
        void expiredLeaseTimesOutTheAttemptAndRetries() {
            first.start(T0, LEASE);
            first.complete(1, T0);
            second.start(T0, LEASE);

            second.expireLease(BACKOFF, T0.plus(LEASE));

            assertThat(second.getStatus()).isEqualTo(StepStatus.RETRY_WAIT);
            assertThat(second.getLeaseExpiresAt()).isNull();
            assertThat(second.getAttempts().get(0).getStatus()).isEqualTo(AttemptStatus.TIMED_OUT);
            assertThat(second.getAttempts().get(0).getErrorMessage()).contains("lease expired");
        }

        @Test
        void lateResultFromATimedOutAttemptIsRejected() {
            first.start(T0, LEASE);
            first.expireLease(BACKOFF, T0.plus(LEASE));

            assertThatThrownBy(() -> first.complete(1, T0.plus(LEASE))).isInstanceOf(StaleAttemptException.class);
            // validate allows a single attempt, so the timeout failed the run
            assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        }

        @Test
        void outcomeForAnOldAttemptIsRejected() {
            first.start(T0, LEASE);
            first.complete(1, T0);
            second.start(T0, LEASE);
            second.failRetryable(1, "timeout", BACKOFF, T0);
            second.start(T0.plus(BACKOFF), LEASE);

            assertThatThrownBy(() -> second.complete(1, T0.plus(BACKOFF)))
                    .isInstanceOf(StaleAttemptException.class);
            assertThat(second.getStatus()).isEqualTo(StepStatus.RUNNING);
        }

        @Test
        void duplicateOutcomeForACompletedStepIsRejected() {
            first.start(T0, LEASE);
            first.complete(1, T0);

            assertThatThrownBy(() -> first.complete(1, T0)).isInstanceOf(StaleAttemptException.class);
            assertThatThrownBy(() -> first.start(T0, LEASE)).isInstanceOf(IllegalStateTransitionException.class);
            assertThat(run.currentStep()).contains(second);
        }

        @Test
        void stepCannotBeClaimedTwice() {
            first.start(T0, LEASE);

            assertThatThrownBy(() -> first.start(T0, LEASE)).isInstanceOf(IllegalStateTransitionException.class);
            assertThat(first.getAttemptCount()).isEqualTo(1);
        }
    }

    @Nested
    class Compensation {

        private WorkflowRun run;
        private StepExecution reserve;
        private StepExecution charge;
        private StepExecution notify;
        private StepExecution ship;

        @BeforeEach
        void setUp() {
            WorkflowDefinition definition = new WorkflowDefinition("order-processing", 1, T0);
            definition.addStep("reserve", "RESERVE_INVENTORY", 3, "RELEASE_INVENTORY");
            definition.addStep("charge", "CHARGE_PAYMENT", 3, "REFUND_PAYMENT");
            definition.addStep("notify", "SEND_EMAIL", 3);
            definition.addStep("ship", "CREATE_SHIPMENT", 3);
            run = WorkflowRun.start(definition, Map.of(), T0);
            reserve = run.getSteps().get(0);
            charge = run.getSteps().get(1);
            notify = run.getSteps().get(2);
            ship = run.getSteps().get(3);
            for (StepExecution step : new StepExecution[]{reserve, charge, notify}) {
                step.start(T0, LEASE);
                step.complete(1, T0);
            }
            ship.start(T0, LEASE);
        }

        @Test
        void failureAfterCompensableStepsCompensatesThemInReverseOrder() {
            ship.failPermanently(1, "cannot ship", T0.plusSeconds(1));

            assertThat(run.getStatus()).isEqualTo(RunStatus.COMPENSATING);
            assertThat(run.getCompletedAt()).isNull();
            assertThat(run.getSteps()).hasSize(6);
            StepExecution refund = run.getSteps().get(4);
            StepExecution release = run.getSteps().get(5);
            assertThat(refund.getKind()).isEqualTo(StepKind.COMPENSATION);
            assertThat(refund.getStepType()).isEqualTo("REFUND_PAYMENT");
            assertThat(refund.getName()).isEqualTo("charge");
            assertThat(release.getStepType()).isEqualTo("RELEASE_INVENTORY");
            assertThat(run.currentStep()).contains(refund);
            assertThat(refund.getNextAttemptAt()).isEqualTo(T0.plusSeconds(1));
            assertThat(release.getNextAttemptAt()).isNull();
        }

        @Test
        void completingEveryCompensationCompensatesTheRun() {
            ship.failPermanently(1, "cannot ship", T0);
            StepExecution refund = run.getSteps().get(4);
            StepExecution release = run.getSteps().get(5);

            refund.start(T0, LEASE);
            refund.complete(1, T0);
            assertThat(run.currentStep()).contains(release);
            release.start(T0, LEASE);
            release.complete(1, T0.plusSeconds(3));

            assertThat(run.getStatus()).isEqualTo(RunStatus.COMPENSATED);
            assertThat(run.getCompletedAt()).isEqualTo(T0.plusSeconds(3));
            assertThat(run.currentStep()).isEmpty();
        }

        @Test
        void failedCompensationNeedsManualAttention() {
            ship.failPermanently(1, "cannot ship", T0);
            StepExecution refund = run.getSteps().get(4);

            refund.start(T0, LEASE);
            refund.failPermanently(1, "refund rejected", T0);

            assertThat(run.getStatus()).isEqualTo(RunStatus.COMPENSATION_FAILED);
            assertThat(run.getSteps().get(5).getStatus()).isEqualTo(StepStatus.PENDING);
        }

        @Test
        void failureWithNothingToCompensateJustFails() {
            WorkflowDefinition definition = new WorkflowDefinition("plain", 1, T0);
            definition.addStep("validate", "VALIDATE_ORDER", 1);
            definition.addStep("notify", "SEND_EMAIL", 1);
            WorkflowRun plain = WorkflowRun.start(definition, Map.of(), T0);
            plain.getSteps().get(0).start(T0, LEASE);
            plain.getSteps().get(0).complete(1, T0);
            plain.getSteps().get(1).start(T0, LEASE);

            plain.getSteps().get(1).failPermanently(1, "smtp down", T0);

            assertThat(plain.getStatus()).isEqualTo(RunStatus.FAILED);
            assertThat(plain.getSteps()).hasSize(2);
        }
    }
}
