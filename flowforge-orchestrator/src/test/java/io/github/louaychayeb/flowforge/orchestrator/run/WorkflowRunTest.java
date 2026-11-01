package io.github.louaychayeb.flowforge.orchestrator.run;

import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinition;
import org.junit.jupiter.api.BeforeEach;
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
    void completingAStepAdvancesTheRun() {
        first.start(T0);
        first.complete(1, T0.plusSeconds(1));

        assertThat(first.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(first.getAttempts()).singleElement()
                .extracting(StepAttempt::getStatus).isEqualTo(AttemptStatus.SUCCEEDED);
        assertThat(run.currentStep()).contains(second);
        assertThat(second.getNextAttemptAt()).isEqualTo(T0.plusSeconds(1));
    }

    @Test
    void completingTheLastStepCompletesTheRun() {
        first.start(T0);
        first.complete(1, T0);
        second.start(T0);
        second.complete(1, T0.plusSeconds(2));

        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.getCompletedAt()).isEqualTo(T0.plusSeconds(2));
        assertThat(run.currentStep()).isEmpty();
    }

    @Test
    void retryableFailureSchedulesARetryWhileAttemptsRemain() {
        first.start(T0);
        first.complete(1, T0);
        second.start(T0);
        second.failRetryable(1, "timeout", BACKOFF, T0);

        assertThat(second.getStatus()).isEqualTo(StepStatus.RETRY_WAIT);
        assertThat(second.getNextAttemptAt()).isEqualTo(T0.plus(BACKOFF));
        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);

        second.start(T0.plus(BACKOFF));
        second.complete(2, T0.plus(BACKOFF));

        assertThat(second.getAttempts()).extracting(StepAttempt::getStatus)
                .containsExactly(AttemptStatus.RETRYABLE_FAILURE, AttemptStatus.SUCCEEDED);
        assertThat(second.getAttempts().get(0).getErrorMessage()).isEqualTo("timeout");
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void exhaustingAttemptsFailsTheStepAndTheRun() {
        first.start(T0);
        first.failRetryable(1, "timeout", BACKOFF, T0);

        assertThat(first.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(second.getStatus()).isEqualTo(StepStatus.PENDING);
        assertThat(second.getNextAttemptAt()).isNull();
    }

    @Test
    void permanentFailureDoesNotRetry() {
        first.start(T0);
        first.complete(1, T0);
        second.start(T0);
        second.failPermanently(1, "declined", T0);

        assertThat(second.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(second.getAttemptCount()).isEqualTo(1);
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void outcomeForAnOldAttemptIsRejected() {
        first.start(T0);
        first.complete(1, T0);
        second.start(T0);
        second.failRetryable(1, "timeout", BACKOFF, T0);
        second.start(T0.plus(BACKOFF));

        assertThatThrownBy(() -> second.complete(1, T0.plus(BACKOFF)))
                .isInstanceOf(StaleAttemptException.class);
        assertThat(second.getStatus()).isEqualTo(StepStatus.RUNNING);
    }

    @Test
    void duplicateOutcomeForACompletedStepIsRejected() {
        first.start(T0);
        first.complete(1, T0);

        assertThatThrownBy(() -> first.complete(1, T0)).isInstanceOf(StaleAttemptException.class);
        assertThatThrownBy(() -> first.start(T0)).isInstanceOf(IllegalStateTransitionException.class);
        assertThat(run.currentStep()).contains(second);
    }

    @Test
    void stepCannotBeClaimedTwice() {
        first.start(T0);

        assertThatThrownBy(() -> first.start(T0)).isInstanceOf(IllegalStateTransitionException.class);
        assertThat(first.getAttemptCount()).isEqualTo(1);
    }
}
