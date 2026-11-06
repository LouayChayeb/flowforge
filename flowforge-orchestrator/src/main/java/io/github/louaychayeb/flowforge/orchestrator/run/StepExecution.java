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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The state of one logical step within a {@link WorkflowRun}. Its id is stable across retries;
 * each try is recorded as a separate {@link StepAttempt}.
 *
 * <p>All state changes go through {@link StepStatus#canTransitionTo}, and outcomes must name the
 * attempt they belong to, so a late or duplicated outcome cannot overwrite newer state.
 *
 * <p>While {@code RUNNING} the step holds a lease. If no outcome arrives before it expires — the worker
 * died, the command was lost — the attempt is timed out and retried like any retryable failure.
 */
@Entity
@Table(name = "step_execution")
public class StepExecution {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workflow_run_id")
    private WorkflowRun run;

    private int position;

    private String name;

    private String stepType;

    @Enumerated(EnumType.STRING)
    private StepKind kind;

    /** For a forward step: the step type that undoes it, or {@code null}. */
    private String compensationType;

    /** For a compensation step: the forward step it undoes. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "compensates_step_execution_id")
    private StepExecution compensates;

    /** For a forward step: positions of the forward steps it waits for. Empty for compensation steps. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Integer> dependsOn = new ArrayList<>();

    /** For a {@code WAIT} step: how long after its dependencies complete it fires. {@code null} otherwise. */
    private Long waitMillis;

    /** What the step produced for later steps; set when it completes. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> output;

    @Enumerated(EnumType.STRING)
    private StepStatus status;

    private int attemptCount;

    private int maxAttempts;

    private Instant nextAttemptAt;

    private Instant leaseExpiresAt;

    private Instant createdAt;

    private Instant updatedAt;

    @Version
    private Long lockVersion;

    @OneToMany(mappedBy = "stepExecution", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("attemptNumber")
    private List<StepAttempt> attempts = new ArrayList<>();

    protected StepExecution() {
    }

    private StepExecution(WorkflowRun run, int position, String name, String stepType, StepKind kind,
                          int maxAttempts, Instant now) {
        this.id = UUID.randomUUID();
        this.run = run;
        this.position = position;
        this.name = name;
        this.stepType = stepType;
        this.kind = kind;
        this.status = StepStatus.PENDING;
        this.attemptCount = 0;
        this.maxAttempts = maxAttempts;
        this.createdAt = now;
        this.updatedAt = now;
    }

    static StepExecution forward(WorkflowRun run, StepDefinition definition, Instant now) {
        StepExecution step = new StepExecution(run, definition.getPosition(), definition.getName(),
                definition.getStepType(), StepKind.FORWARD, definition.getMaxAttempts(), now);
        step.compensationType = definition.getCompensationType();
        step.dependsOn = new ArrayList<>(definition.getDependsOn());
        step.waitMillis = definition.getWaitDuration() == null ? null : definition.getWaitDuration().toMillis();
        return step;
    }

    /**
     * A step that undoes {@code completed}, using its compensation type and attempt budget.
     */
    static StepExecution compensation(WorkflowRun run, StepExecution completed, int position, Instant now) {
        StepExecution step = new StepExecution(run, position, completed.getName(), completed.getCompensationType(),
                StepKind.COMPENSATION, completed.getMaxAttempts(), now);
        step.compensates = completed;
        return step;
    }

    /**
     * Marks a pending step as ready to be claimed: now, or — for a {@code WAIT} step — once its wait is over. The due
     * time is stored, so a timer survives any restart.
     */
    void makeEligible(Instant now) {
        if (status != StepStatus.PENDING) {
            throw new IllegalStateTransitionException("Step %s cannot become eligible in status %s".formatted(id, status),
                    Map.of("stepExecutionId", id, "stepStatus", status));
        }
        nextAttemptAt = waitMillis == null ? now : now.plusMillis(waitMillis);
        updatedAt = now;
    }

    /**
     * Takes back the eligibility of a step that has not started yet — on cancellation, nothing new may start. A
     * pending timer is cancelled the same way.
     */
    void withdraw(Instant now) {
        if (status != StepStatus.PENDING || attemptCount != 0) {
            throw new IllegalStateTransitionException("Step %s has already started and cannot be withdrawn".formatted(id),
                    Map.of("stepExecutionId", id, "stepStatus", status));
        }
        nextAttemptAt = null;
        updatedAt = now;
    }

    /**
     * Operator override: gives a {@code FAILED} step another {@code extraAttempts} and schedules one now. The only
     * way a step leaves a terminal state; its earlier attempts stay in the history unchanged.
     */
    void reopenByOperator(int extraAttempts, Instant now) {
        if (status != StepStatus.FAILED) {
            throw new IllegalStateTransitionException("Only a FAILED step can be retried, step %s is %s".formatted(id, status),
                    Map.of("stepExecutionId", id, "stepStatus", status));
        }
        if (extraAttempts < 1) {
            throw new IllegalArgumentException("extraAttempts must be at least 1");
        }
        status = StepStatus.RETRY_WAIT;
        maxAttempts = attemptCount + extraAttempts;
        nextAttemptAt = now;
        updatedAt = now;
    }

    /** A built-in timer: the orchestrator completes it itself when it comes due, no worker involved. */
    public boolean isTimer() {
        return kind == StepKind.FORWARD && WorkflowDefinition.WAIT.equals(stepType);
    }

    /**
     * A built-in signal step: completed by a signal delivered through the API. Its {@code nextAttemptAt} is the
     * deadline; when the dispatcher claims it, no signal came in time.
     */
    public boolean isSignal() {
        return kind == StepKind.FORWARD && WorkflowDefinition.SIGNAL.equals(stepType);
    }

    /** Eligible and waiting for its signal: the only time a signal is accepted. */
    public boolean isAwaitingSignal() {
        return isSignal() && status == StepStatus.PENDING && nextAttemptAt != null;
    }

    /**
     * Completes a waiting signal step with the signal's payload as its output, in one move: an attempt opens and
     * succeeds at the same instant. The run then goes on as after any completed step.
     *
     * @throws OperationNotAllowedException if the step is not waiting for a signal right now (its dependencies are
     *         still running, it was already signalled, it timed out, or the run is winding down)
     */
    public void deliverSignal(Map<String, Object> payload, Instant now) {
        if (!isAwaitingSignal()) {
            throw new OperationNotAllowedException("Step %s is not waiting for a signal (it is %s%s)".formatted(name,
                    status, status == StepStatus.PENDING ? ", not yet due" : ""),
                    Map.of("step", name, "stepStatus", status));
        }
        StepAttempt attempt = start(now, Duration.ZERO);
        complete(attempt.getAttemptNumber(), payload, now);
    }

    /** The deadline passed without a signal: the step fails for good, and the run compensates what it completed. */
    public void missSignal(int attemptNumber, Instant now) {
        failPermanently(attemptNumber, "No signal " + name + " within " + Duration.ofMillis(waitMillis), now);
    }

    /**
     * Claims the step, opens a new attempt and takes a lease on it until {@code now + leaseDuration}.
     */
    public StepAttempt start(Instant now, Duration leaseDuration) {
        transitionTo(StepStatus.RUNNING, now);
        attemptCount++;
        nextAttemptAt = null;
        leaseExpiresAt = now.plus(leaseDuration);
        StepAttempt attempt = new StepAttempt(this, attemptCount, now);
        attempts.add(attempt);
        return attempt;
    }

    public void complete(int attemptNumber, Instant now) {
        complete(attemptNumber, Map.of(), now);
    }

    /** Completes the attempt and records what the step produced for the steps after it. */
    public void complete(int attemptNumber, Map<String, Object> producedOutput, Instant now) {
        activeAttempt(attemptNumber).finish(AttemptStatus.SUCCEEDED, null, now);
        transitionTo(StepStatus.COMPLETED, now);
        output = producedOutput == null || producedOutput.isEmpty() ? null : new LinkedHashMap<>(producedOutput);
        run.onStepCompleted(this, now);
    }

    /**
     * Records a failure that may succeed on retry. Schedules another attempt after {@code backoff}
     * if attempts remain; otherwise fails the step.
     */
    public void failRetryable(int attemptNumber, String error, Duration backoff, Instant now) {
        activeAttempt(attemptNumber).finish(AttemptStatus.RETRYABLE_FAILURE, error, now);
        retryOrFail(backoff, now);
    }

    public void failPermanently(int attemptNumber, String error, Instant now) {
        activeAttempt(attemptNumber).finish(AttemptStatus.PERMANENT_FAILURE, error, now);
        transitionTo(StepStatus.FAILED, now);
        run.onStepFailed(this, now);
    }

    /**
     * Times out the active attempt because its lease expired without an outcome. Any outcome that arrives
     * later for that attempt is stale and will be rejected. Callers decide when a lease counts as expired.
     */
    public void expireLease(Duration backoff, Instant now) {
        if (status != StepStatus.RUNNING) {
            throw new IllegalStateTransitionException("Step %s holds no lease in status %s".formatted(id, status));
        }
        attempts.get(attempts.size() - 1).finish(AttemptStatus.TIMED_OUT,
                "No result before lease expired at " + leaseExpiresAt, now);
        retryOrFail(backoff, now);
    }

    private void retryOrFail(Duration backoff, Instant now) {
        if (attemptCount < maxAttempts) {
            transitionTo(StepStatus.RETRY_WAIT, now);
            nextAttemptAt = now.plus(backoff);
        } else {
            transitionTo(StepStatus.FAILED, now);
            run.onStepFailed(this, now);
        }
    }

    private StepAttempt activeAttempt(int attemptNumber) {
        if (status != StepStatus.RUNNING || attemptNumber != attemptCount) {
            throw new StaleAttemptException(id, attemptNumber, attemptCount, status);
        }
        return attempts.get(attempts.size() - 1);
    }

    private void transitionTo(StepStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateTransitionException("Step %s cannot move from %s to %s".formatted(id, status, target),
                    Map.of("stepExecutionId", id, "from", status, "to", target));
        }
        status = target;
        updatedAt = now;
        if (target != StepStatus.RUNNING) {
            leaseExpiresAt = null;
        }
    }

    public UUID getId() {
        return id;
    }

    public WorkflowRun getRun() {
        return run;
    }

    public int getPosition() {
        return position;
    }

    public String getName() {
        return name;
    }

    public String getStepType() {
        return stepType;
    }

    public StepKind getKind() {
        return kind;
    }

    public String getCompensationType() {
        return compensationType;
    }

    /** For a compensation step, the completed step it undoes; otherwise {@code null}. */
    public StepExecution getCompensates() {
        return compensates;
    }

    /** Positions of the forward steps this one waits for. */
    public List<Integer> getDependsOn() {
        return Collections.unmodifiableList(dependsOn);
    }

    /** Not started and not yet eligible: still waiting for its dependencies (or never going to run). */
    boolean isWaitingForDependencies() {
        return status == StepStatus.PENDING && nextAttemptAt == null && attemptCount == 0;
    }

    /** Eligible, running or between retries: work that has started and will still produce an outcome. */
    boolean isInFlight() {
        return status == StepStatus.RUNNING || status == StepStatus.RETRY_WAIT
                || (status == StepStatus.PENDING && nextAttemptAt != null);
    }

    /** When the successful attempt finished, or {@code null} if the step has not completed. */
    Instant completedAt() {
        if (status != StepStatus.COMPLETED || attempts.isEmpty()) {
            return null;
        }
        return attempts.get(attempts.size() - 1).getFinishedAt();
    }

    /** What the step produced, or an empty map. */
    public Map<String, Object> getOutput() {
        return output == null ? Map.of() : Collections.unmodifiableMap(output);
    }

    public Long getWaitMillis() {
        return waitMillis;
    }

    public StepStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<StepAttempt> getAttempts() {
        return Collections.unmodifiableList(attempts);
    }
}
