package io.github.louaychayeb.flowforge.orchestrator.run;

import io.github.louaychayeb.flowforge.orchestrator.definition.StepDefinition;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinition;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One execution of a {@link WorkflowDefinition}. Steps run strictly in order: only the step at
 * {@code currentPosition} can be eligible, and the run advances when that step completes.
 *
 * <p>If a step fails terminally after earlier steps completed, those that declare a compensation are
 * undone in reverse order (a saga). Compensation steps are appended after the forward steps and run
 * through the same claim, retry and lease machinery.
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

    private int currentPosition;

    private Instant createdAt;

    private Instant updatedAt;

    private Instant completedAt;

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
     * Creates a run with one {@link StepExecution} per step of the definition and makes the first step eligible.
     */
    public static WorkflowRun start(WorkflowDefinition definition, Map<String, Object> input, Instant now) {
        if (definition.getSteps().isEmpty()) {
            throw new IllegalArgumentException("Workflow definition " + definition.getName() + " has no steps");
        }
        WorkflowRun run = new WorkflowRun(definition, input, now);
        for (StepDefinition stepDefinition : definition.getSteps()) {
            run.steps.add(StepExecution.forward(run, stepDefinition, now));
        }
        run.steps.get(0).makeEligible(now);
        return run;
    }

    /**
     * Called by a step once it has completed. Makes the next step eligible, or ends the run:
     * {@code COMPLETED} after the last forward step, {@code COMPENSATED} after the last compensation.
     */
    void onStepCompleted(StepExecution step, Instant now) {
        requireCurrent(step);
        int next = step.getPosition() + 1;
        if (next < steps.size()) {
            advanceTo(next, now);
        } else {
            transitionTo(status == RunStatus.COMPENSATING ? RunStatus.COMPENSATED : RunStatus.COMPLETED, now);
        }
    }

    /**
     * Called by a step once it has failed terminally. A failed forward step starts compensation of the
     * completed steps that declare one, or fails the run if there are none. A failed compensation leaves
     * the run in {@code COMPENSATION_FAILED}: the system can no longer restore a consistent state by itself.
     */
    void onStepFailed(StepExecution step, Instant now) {
        requireCurrent(step);
        if (status == RunStatus.COMPENSATING) {
            transitionTo(RunStatus.COMPENSATION_FAILED, now);
            return;
        }
        List<StepExecution> toCompensate = new ArrayList<>();
        for (int i = step.getPosition() - 1; i >= 0; i--) {
            StepExecution completed = steps.get(i);
            if (completed.getStatus() == StepStatus.COMPLETED && completed.getCompensationType() != null) {
                toCompensate.add(completed);
            }
        }
        if (toCompensate.isEmpty()) {
            transitionTo(RunStatus.FAILED, now);
            return;
        }
        transitionTo(RunStatus.COMPENSATING, now);
        int firstCompensation = steps.size();
        for (StepExecution completed : toCompensate) {
            steps.add(StepExecution.compensation(this, completed, steps.size(), now));
        }
        advanceTo(firstCompensation, now);
    }

    private void advanceTo(int position, Instant now) {
        currentPosition = position;
        steps.get(position).makeEligible(now);
        updatedAt = now;
    }

    private void requireCurrent(StepExecution step) {
        if (step.getPosition() != currentPosition) {
            throw new IllegalStateTransitionException("Step %s at position %d is not the current step (%d) of run %s"
                    .formatted(step.getName(), step.getPosition(), currentPosition, id));
        }
    }

    private void transitionTo(RunStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateTransitionException("Run %s cannot move from %s to %s".formatted(id, status, target));
        }
        status = target;
        updatedAt = now;
        if (target.isTerminal()) {
            completedAt = now;
        }
    }

    /**
     * The step currently executing or waiting to execute; empty once the run is terminal.
     */
    public Optional<StepExecution> currentStep() {
        return status.isTerminal() ? Optional.empty() : Optional.of(steps.get(currentPosition));
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

    public List<StepExecution> getSteps() {
        return Collections.unmodifiableList(steps);
    }
}
