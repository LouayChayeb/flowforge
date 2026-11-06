package io.github.louaychayeb.flowforge.orchestrator.definition;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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
 * A named, versioned, ordered list of steps. Definitions are immutable once created:
 * changing a workflow means registering a new version, so runs in flight keep the
 * definition they started with.
 */
@Entity
@Table(name = "workflow_definition")
public class WorkflowDefinition {

    @Id
    private UUID id;

    private String name;

    private int version;

    private Instant createdAt;

    @Version
    private Long lockVersion;

    @OneToMany(mappedBy = "workflowDefinition", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<StepDefinition> steps = new ArrayList<>();

    protected WorkflowDefinition() {
    }

    public WorkflowDefinition(String name, int version, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.version = version;
        this.createdAt = createdAt;
    }

    public void addStep(String stepName, String stepType, int maxAttempts) {
        addStep(stepName, stepType, maxAttempts, null);
    }

    /** Adds a step that waits for the step before it (the first step waits for nothing). */
    public void addStep(String stepName, String stepType, int maxAttempts, String compensationType) {
        addStep(stepName, stepType, maxAttempts, compensationType,
                steps.isEmpty() ? List.of() : List.of(steps.size() - 1));
    }

    /**
     * Adds a step that waits for the steps at the given positions, which must all be earlier steps: that rule
     * alone makes a cycle impossible.
     */
    public void addStep(String stepName, String stepType, int maxAttempts, String compensationType,
                        List<Integer> dependsOn) {
        int position = steps.size();
        for (int dependency : dependsOn) {
            if (dependency < 0 || dependency >= position) {
                throw new IllegalArgumentException("Step " + stepName + " can only depend on earlier steps, not position "
                        + dependency);
            }
        }
        steps.add(new StepDefinition(this, position, stepName, stepType, maxAttempts, compensationType,
                dependsOn.stream().distinct().sorted().toList(), null));
    }

    /** The built-in step type for timers: completed by the orchestrator itself once its wait is over. */
    public static final String WAIT = "WAIT";

    /**
     * The built-in step type for signals: completed when an event from outside arrives through the API (an approval,
     * a webhook), carrying its payload as the step's output; failed if none arrives within its wait.
     */
    public static final String SIGNAL = "SIGNAL";

    /**
     * Adds a durable timer: a step that completes {@code wait} after its dependencies did. No worker runs it, and it
     * has nothing to undo.
     */
    public void addTimer(String stepName, Duration wait, List<Integer> dependsOn) {
        addBuiltIn(stepName, WAIT, wait, dependsOn);
    }

    /**
     * Adds a signal step: once its dependencies complete, it waits up to {@code timeout} for the signal named after
     * it. No worker runs it, and it has nothing to undo; a missed deadline fails it like any permanent failure.
     */
    public void addSignal(String stepName, Duration timeout, List<Integer> dependsOn) {
        addBuiltIn(stepName, SIGNAL, timeout, dependsOn);
    }

    private void addBuiltIn(String stepName, String type, Duration wait, List<Integer> dependsOn) {
        int position = steps.size();
        for (int dependency : dependsOn) {
            if (dependency < 0 || dependency >= position) {
                throw new IllegalArgumentException("Step " + stepName + " can only depend on earlier steps, not position "
                        + dependency);
            }
        }
        if (wait == null || wait.isZero() || wait.isNegative()) {
            throw new IllegalArgumentException(type + " step " + stepName + " needs a positive wait");
        }
        steps.add(new StepDefinition(this, position, stepName, type, 1, null,
                dependsOn.stream().distinct().sorted().toList(), wait));
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<StepDefinition> getSteps() {
        return Collections.unmodifiableList(steps);
    }
}
