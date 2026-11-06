package io.github.louaychayeb.flowforge.orchestrator.run;

import io.github.louaychayeb.flowforge.orchestrator.definition.StepDefinition;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinition;
import io.github.louaychayeb.flowforge.orchestrator.error.OperationNotAllowedException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One execution of a {@link WorkflowDefinition}.
 *
 * <p>Forward steps form a dependency graph: a step becomes eligible as soon as every step it depends on has
 * completed, so independent steps run in parallel. A workflow whose steps each depend on the previous one is the
 * classic sequential case.
 *
 * <p>If a forward step fails for good, no new step starts; steps already in flight are allowed to finish, since
 * they may still succeed and then need undoing too. Once nothing is in flight, the completed steps that declare a
 * compensation are undone one at a time, most recently completed first — a saga. Compensation steps are appended
 * after the forward steps and go through the same claim, retry and lease machinery; during compensation
 * {@code currentPosition} is the compensation step that may run.
 */
@Entity
@Table(name = "workflow_run")
public class WorkflowRun {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workflow_definition_id")
    private WorkflowDefinition definition;

    @Enumerated(EnumType.STRING)
    private RunStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> input;

    /** The compensation step that may run; while forward steps run, the lowest-positioned active one. */
    private int currentPosition;

    private Instant createdAt;

    private Instant updatedAt;

    private Instant completedAt;

    /** When an operator asked to cancel the run; from then on it winds down like a failed run. */
    private Instant cancelRequestedAt;

    /** W3C traceparent of the trace this run belongs to; {@code null} when tracing is off. */
    private String traceParent;

    /** The client's Idempotency-Key, unique across runs; {@code null} when the client sent none. */
    private String idempotencyKey;

    /** SHA-256 of the workflow name and input the key was first used with: a reuse for anything else is refused. */
    private String requestHash;

    @Version
    private Long lockVersion;

    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<StepExecution> steps = new ArrayList<>();

    protected WorkflowRun() {
    }

    private WorkflowRun(WorkflowDefinition definition, Map<String, Object> input, Instant now) {
        this.id = UUID.randomUUID();
        this.definition = definition;
        this.status = RunStatus.RUNNING;
        this.input = new LinkedHashMap<>(input);
        this.currentPosition = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Creates the run with one forward step per definition step and makes every step without dependencies
     * eligible.
     */
    public static WorkflowRun start(WorkflowDefinition definition, Map<String, Object> input, Instant now) {
        if (definition.getSteps().isEmpty()) {
            throw new IllegalArgumentException("Workflow definition " + definition.getName() + " has no steps");
        }
        WorkflowRun run = new WorkflowRun(definition, input, now);
        for (StepDefinition stepDefinition : definition.getSteps()) {
            run.steps.add(StepExecution.forward(run, stepDefinition, now));
        }
        for (StepExecution step : run.steps) {
            if (step.getDependsOn().isEmpty()) {
                step.makeEligible(now);
            }
        }
        run.moveCursor();
        return run;
    }

    void onStepCompleted(StepExecution step, Instant now) {
        requireActive(step);
        if (step.getKind() == StepKind.COMPENSATION) {
            int next = step.getPosition() + 1;
            if (next < steps.size()) {
                currentPosition = next;
                steps.get(next).makeEligible(now);
                updatedAt = now;
            } else {
                transitionTo(isCancelling() ? RunStatus.CANCELLED : RunStatus.COMPENSATED, now);
            }
            return;
        }
        if (isFailing()) {
            // A step that was already in flight when another one failed. It completed, so it is compensated too.
            finishFailureOnceDrained(now);
            return;
        }
        boolean allCompleted = true;
        for (StepExecution candidate : forwardSteps()) {
            if (candidate.getStatus() != StepStatus.COMPLETED) {
                allCompleted = false;
                if (candidate.isWaitingForDependencies() && dependenciesCompleted(candidate)) {
                    candidate.makeEligible(now);
                }
            }
        }
        if (allCompleted) {
            transitionTo(RunStatus.COMPLETED, now);
        } else {
            updatedAt = now;
            moveCursor();
        }
    }

    void onStepFailed(StepExecution step, Instant now) {
        requireActive(step);
        if (step.getKind() == StepKind.COMPENSATION) {
            transitionTo(RunStatus.COMPENSATION_FAILED, now);
            return;
        }
        finishFailureOnceDrained(now);
    }

    /**
     * After a forward step failed for good: if no forward step is still in flight, undo the completed ones (most
     * recently completed first) or, with nothing to undo, fail the run. Otherwise wait for the next outcome.
     */
    private void finishFailureOnceDrained(Instant now) {
        updatedAt = now;
        if (forwardSteps().stream().anyMatch(StepExecution::isInFlight)) {
            moveCursor();
            return;
        }
        List<StepExecution> toCompensate = forwardSteps().stream()
                .filter(s -> s.getStatus() == StepStatus.COMPLETED && s.getCompensationType() != null)
                .sorted(Comparator.comparing(StepExecution::completedAt)
                        .thenComparing(StepExecution::getPosition).reversed())
                .toList();
        if (toCompensate.isEmpty()) {
            transitionTo(isCancelling() ? RunStatus.CANCELLED : RunStatus.FAILED, now);
            return;
        }
        transitionTo(RunStatus.COMPENSATING, now);
        int firstCompensation = steps.size();
        for (StepExecution completed : toCompensate) {
            steps.add(StepExecution.compensation(this, completed, steps.size(), now));
        }
        currentPosition = firstCompensation;
        steps.get(firstCompensation).makeEligible(now);
    }

    /** A forward step has failed for good, or the run is being cancelled: nothing new may start. */
    private boolean isFailing() {
        return isCancelling() || forwardSteps().stream().anyMatch(s -> s.getStatus() == StepStatus.FAILED);
    }

    public boolean isCancelling() {
        return cancelRequestedAt != null;
    }

    /**
     * Operator action: stop the run. Nothing new starts — steps that are eligible but have not started, timers
     * included, are withdrawn — and steps already in flight are allowed to finish, because they may have side effects
     * that need undoing. Then, exactly like after a failure, every completed step with a compensation is undone, most
     * recently completed first, and the run ends {@code CANCELLED}.
     *
     * <p>Idempotent: cancelling a run that is already being cancelled does nothing.
     *
     * @throws OperationNotAllowedException if the run has finished or is already compensating a failure
     */
    public void cancel(Instant now) {
        if (isCancelling() && !status.isTerminal()) {
            return;
        }
        if (status != RunStatus.RUNNING) {
            throw new OperationNotAllowedException("Run %s is %s and can no longer be cancelled".formatted(id, status),
                    Map.of("runId", id, "runStatus", status));
        }
        cancelRequestedAt = now;
        for (StepExecution step : forwardSteps()) {
            if (step.getStatus() == StepStatus.PENDING && step.getNextAttemptAt() != null) {
                step.withdraw(now);
            }
        }
        finishFailureOnceDrained(now);
    }

    /**
     * Operator action: retry the compensation that failed, with {@code extraAttempts} more attempts — for example
     * after the payment provider that rejected a refund is back. The run returns to {@code COMPENSATING}; the failed
     * attempts stay in the history.
     *
     * @throws OperationNotAllowedException unless the run is {@code COMPENSATION_FAILED}
     */
    public void retryCompensation(int extraAttempts, Instant now) {
        if (!status.canBeReopenedByOperator()) {
            throw new OperationNotAllowedException(
                    "Only a COMPENSATION_FAILED run can be retried; run %s is %s".formatted(id, status),
                    Map.of("runId", id, "runStatus", status));
        }
        StepExecution failed = steps.get(currentPosition);
        failed.reopenByOperator(extraAttempts, now);
        // The one transition out of a terminal state, made explicitly by an operator and recorded in the audit log.
        status = RunStatus.COMPENSATING;
        completedAt = null;
        updatedAt = now;
    }

    private boolean dependenciesCompleted(StepExecution step) {
        return step.getDependsOn().stream().allMatch(p -> steps.get(p).getStatus() == StepStatus.COMPLETED);
    }

    private List<StepExecution> forwardSteps() {
        return steps.stream().filter(s -> s.getKind() == StepKind.FORWARD).toList();
    }

    private void requireActive(StepExecution step) {
        boolean allowed = step.getKind() == StepKind.COMPENSATION
                ? status == RunStatus.COMPENSATING && step.getPosition() == currentPosition
                : status == RunStatus.RUNNING;
        if (!allowed) {
            throw new IllegalStateTransitionException("Step %s at position %d cannot report while run %s is %s"
                    .formatted(step.getName(), step.getPosition(), id, status));
        }
    }

    /** While forward steps run, point currentPosition at the lowest-positioned active one (for display). */
    private void moveCursor() {
        forwardSteps().stream().filter(StepExecution::isInFlight).findFirst()
                .ifPresent(s -> currentPosition = s.getPosition());
    }

    private void transitionTo(RunStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateTransitionException("Run %s cannot move from %s to %s".formatted(id, status, target),
                    Map.of("runId", id, "from", status, "to", target));
        }
        status = target;
        updatedAt = now;
        if (target.isTerminal()) {
            completedAt = now;
        }
    }

    /** The first step that may run or is running now, if any. With parallel steps see {@link #activeSteps()}. */
    public Optional<StepExecution> currentStep() {
        if (status.isTerminal()) {
            return Optional.empty();
        }
        if (status == RunStatus.COMPENSATING) {
            return Optional.of(steps.get(currentPosition));
        }
        return forwardSteps().stream().filter(StepExecution::isInFlight).findFirst();
    }

    /** Every step that is eligible, running or waiting to retry. */
    public List<StepExecution> activeSteps() {
        if (status.isTerminal()) {
            return List.of();
        }
        return steps.stream().filter(StepExecution::isInFlight).toList();
    }

    public UUID getId() {
        return id;
    }

    public WorkflowDefinition getDefinition() {
        return definition;
    }

    public RunStatus getStatus() {
        return status;
    }

    public Map<String, Object> getInput() {
        return Collections.unmodifiableMap(input);
    }

    public int getCurrentPosition() {
        return currentPosition;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCancelRequestedAt() {
        return cancelRequestedAt;
    }

    public String getTraceParent() {
        return traceParent;
    }

    /** Ties the run to the client's Idempotency-Key; see {@link WorkflowRunService#start}. */
    void assignIdempotencyKey(String key, String hash) {
        if (key == null || key.isBlank() || hash == null) {
            throw new IllegalArgumentException("An idempotency key and its request hash are both required");
        }
        this.idempotencyKey = key;
        this.requestHash = hash;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    String getRequestHash() {
        return requestHash;
    }

    /** Records the trace this run belongs to; see {@code RunTracing}. */
    public void attachTrace(String traceParent) {
        this.traceParent = traceParent;
    }

    public List<StepExecution> getSteps() {
        return Collections.unmodifiableList(steps);
    }
}
