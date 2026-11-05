package io.github.louaychayeb.flowforge.orchestrator.run;

import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs whose steps form a graph rather than a line. The diamond used throughout:
 *
 * <pre>
 *        ┌── b (undo: UNDO_B) ──┐
 *   a ───┤                      ├── d
 *        └── c ─────────────────┘
 * </pre>
 */
class ParallelRunTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration BACKOFF = Duration.ofSeconds(5);

    private WorkflowRun run;
    private StepExecution a;
    private StepExecution b;
    private StepExecution c;
    private StepExecution d;

    @BeforeEach
    void setUp() {
        WorkflowDefinition definition = new WorkflowDefinition("diamond", 1, T0);
        definition.addStep("a", "A", 3, null, List.of());
        definition.addStep("b", "B", 3, "UNDO_B", List.of(0));
        definition.addStep("c", "C", 3, null, List.of(0));
        definition.addStep("d", "D", 3, null, List.of(1, 2));
        run = WorkflowRun.start(definition, Map.of(), T0);
        a = run.getSteps().get(0);
        b = run.getSteps().get(1);
        c = run.getSteps().get(2);
        d = run.getSteps().get(3);
    }

    @Test
    void startMakesOnlyTheStepsWithoutDependenciesEligible() {
        assertThat(a.getNextAttemptAt()).isEqualTo(T0);
        assertThat(List.of(b, c, d)).allSatisfy(s -> assertThat(s.getNextAttemptAt()).isNull());
        assertThat(run.activeSteps()).containsExactly(a);
    }

    @Test
    void bothBranchesBecomeEligibleTogether() {
        succeed(a, 1);

        assertThat(b.getNextAttemptAt()).isNotNull();
        assertThat(c.getNextAttemptAt()).isNotNull();
        assertThat(d.getNextAttemptAt()).isNull();
        assertThat(run.activeSteps()).containsExactly(b, c);
    }

    @Test
    void theJoinWaitsForBothBranches() {
        succeed(a, 1);
        b.start(T0.plusSeconds(2), LEASE);
        c.start(T0.plusSeconds(2), LEASE);

        b.complete(1, T0.plusSeconds(3));
        assertThat(d.getNextAttemptAt()).as("c is still running").isNull();

        c.complete(1, T0.plusSeconds(4));
        assertThat(d.getNextAttemptAt()).isEqualTo(T0.plusSeconds(4));
    }

    @Test
    void theRunCompletesWhenEveryStepHas() {
        succeed(a, 1);
        succeed(b, 2);
        succeed(c, 3);
        succeed(d, 4);

        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.activeSteps()).isEmpty();
    }

    @Test
    void aFailureWaitsForTheStepsStillInFlightThenUndoesThem() {
        succeed(a, 1);
        b.start(T0.plusSeconds(2), LEASE);
        c.start(T0.plusSeconds(2), LEASE);

        c.failPermanently(1, "rejected", T0.plusSeconds(3));
        assertThat(run.getStatus()).as("b is still running and may succeed").isEqualTo(RunStatus.RUNNING);
        assertThat(run.getSteps()).hasSize(4);

        b.complete(1, T0.plusSeconds(4));
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPENSATING);
        assertThat(d.getNextAttemptAt()).as("nothing new starts after a failure").isNull();
        StepExecution undoB = run.getSteps().get(4);
        assertThat(undoB.getKind()).isEqualTo(StepKind.COMPENSATION);
        assertThat(undoB.getStepType()).isEqualTo("UNDO_B");
        assertThat(undoB.getNextAttemptAt()).isEqualTo(T0.plusSeconds(4));

        succeed(undoB, 5);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPENSATED);
    }

    @Test
    void aSiblingWaitingToRetryIsWaitedForToo() {
        succeed(a, 1);
        b.start(T0.plusSeconds(2), LEASE);
        c.start(T0.plusSeconds(2), LEASE);
        b.failRetryable(1, "timeout", BACKOFF, T0.plusSeconds(3));

        c.failPermanently(1, "rejected", T0.plusSeconds(3));
        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);

        b.start(T0.plusSeconds(9), LEASE);
        b.complete(2, T0.plusSeconds(10));
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPENSATING);
    }

    @Test
    void withNothingToUndoTheRunFailsOnceDrained() {
        succeed(a, 1);
        b.start(T0.plusSeconds(2), LEASE);
        c.start(T0.plusSeconds(2), LEASE);

        c.failPermanently(1, "rejected", T0.plusSeconds(3));
        b.failPermanently(1, "rejected too", T0.plusSeconds(4));

        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getSteps()).as("no compensation appended").hasSize(4);
    }

    @Test
    void compensationsRunMostRecentlyCompletedFirst() {
        WorkflowDefinition definition = new WorkflowDefinition("fan-out", 1, T0);
        definition.addStep("x", "X", 1, "UNDO_X", List.of());
        definition.addStep("y", "Y", 1, "UNDO_Y", List.of());
        definition.addStep("z", "Z", 1, null, List.of(0, 1));
        WorkflowRun fanOut = WorkflowRun.start(definition, Map.of(), T0);
        StepExecution x = fanOut.getSteps().get(0);
        StepExecution y = fanOut.getSteps().get(1);
        StepExecution z = fanOut.getSteps().get(2);

        // y (position 1) completes before x (position 0): reverse completion order is x, then y.
        succeed(y, 1);
        succeed(x, 2);
        z.start(T0.plusSeconds(3), LEASE);
        z.failPermanently(1, "rejected", T0.plusSeconds(4));

        assertThat(fanOut.getSteps().subList(3, 5)).extracting(StepExecution::getStepType)
                .containsExactly("UNDO_X", "UNDO_Y");
    }

    @Test
    void stepsCanOnlyDependOnEarlierSteps() {
        WorkflowDefinition definition = new WorkflowDefinition("bad", 1, T0);
        definition.addStep("first", "A", 1, null, List.of());
        assertThatThrownBy(() -> definition.addStep("second", "B", 1, null, List.of(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void succeed(StepExecution step, long second) {
        step.start(T0.plusSeconds(second).minusMillis(500), LEASE);
        step.complete(step.getAttemptCount(), T0.plusSeconds(second));
    }
}
