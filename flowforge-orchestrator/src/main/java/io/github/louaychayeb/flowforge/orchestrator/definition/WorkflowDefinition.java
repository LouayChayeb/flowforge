package io.github.louaychayeb.flowforge.orchestrator.definition;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

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

    public void addStep(String stepName, String stepType, int maxAttempts, String compensationType) {
        steps.add(new StepDefinition(this, steps.size(), stepName, stepType, maxAttempts, compensationType));
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
