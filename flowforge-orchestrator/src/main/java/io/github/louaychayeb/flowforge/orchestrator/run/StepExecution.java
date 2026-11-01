package io.github.louaychayeb.flowforge.orchestrator.run;

import io.github.louaychayeb.flowforge.orchestrator.definition.StepDefinition;
import jakarta.persistence.CascadeType;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * The state of one logical step within a {@link WorkflowRun}. Its id is stable across retries;
 * each try is recorded as a separate {@link StepAttempt}.
 *
 * <p>All state changes go through {@link StepStatus#canTransitionTo}, and outcomes must name the
 * attempt they belong to, so a late or duplicated outcome cannot overwrite newer state.
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
    private StepStatus status;

    private int attemptCount;

    private int maxAttempts;

    private Instant nextAttemptAt;

    private Instant createdAt;

    private Instant updatedAt;

    @Version
    private Long lockVersion;

    @OneToMany(mappedBy = "stepExecution", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("attemptNumber")
    private List<StepAttempt> attempts = new ArrayList<>();

    protected StepExecution() {
    }

    StepExecution(WorkflowRun run, StepDefinition definition, Instant now) {
        this.id = UUID.randomUUID();
        this.run = run;
        this.position = definition.getPosition();
        this.name = definition.getName();
        this.stepType = definition.getStepType();
        this.status = StepStatus.PENDING;
        this.attemptCount = 0;
        this.maxAttempts = definition.getMaxAttempts();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Marks a pending step as ready to be claimed now.
     */
    void makeEligible(Instant now) {
        if (status != StepStatus.PENDING) {
            throw new IllegalStateTransitionException("Step %s cannot become eligible in status %s".formatted(id, status));
        }
        nextAttemptAt = now;
        updatedAt = now;
    }

    /**
     * Claims the step and opens a new attempt.
     */
    public StepAttempt start(Instant now) {
        transitionTo(StepStatus.RUNNING, now);
        attemptCount++;
        nextAttemptAt = null;
        StepAttempt attempt = new StepAttempt(this, attemptCount, now);
        attempts.add(attempt);
        return attempt;
    }

    public void complete(int attemptNumber, Instant now) {
        activeAttempt(attemptNumber).finish(AttemptStatus.SUCCEEDED, null, now);
        transitionTo(StepStatus.COMPLETED, now);
        run.onStepCompleted(this, now);
    }

    /**
     * Records a failure that may succeed on retry. Schedules another attempt after {@code backoff}
     * if attempts remain; otherwise fails the step and the run.
     */
    public void failRetryable(int attemptNumber, String error, Duration backoff, Instant now) {
        activeAttempt(attemptNumber).finish(AttemptStatus.RETRYABLE_FAILURE, error, now);
        if (attemptCount < maxAttempts) {
            transitionTo(StepStatus.RETRY_WAIT, now);
            nextAttemptAt = now.plus(backoff);
        } else {
            transitionTo(StepStatus.FAILED, now);
            run.onStepFailed(this, now);
        }
    }

    public void failPermanently(int attemptNumber, String error, Instant now) {
        activeAttempt(attemptNumber).finish(AttemptStatus.PERMANENT_FAILURE, error, now);
        transitionTo(StepStatus.FAILED, now);
        run.onStepFailed(this, now);
    }

    private StepAttempt activeAttempt(int attemptNumber) {
        if (status != StepStatus.RUNNING || attemptNumber != attemptCount) {
            throw new StaleAttemptException(id, attemptNumber, attemptCount, status);
        }
        return attempts.get(attempts.size() - 1);
    }

    private void transitionTo(StepStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateTransitionException("Step %s cannot move from %s to %s".formatted(id, status, target));
        }
        status = target;
        updatedAt = now;
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
