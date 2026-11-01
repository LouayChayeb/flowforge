package io.github.louaychayeb.flowforge.orchestrator.definition;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.UUID;

/**
 * One step of a {@link WorkflowDefinition}: what to run ({@code stepType}) and how many times it may be attempted.
 */
@Entity
@Table(name = "step_definition")
public class StepDefinition {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workflow_definition_id")
    private WorkflowDefinition workflowDefinition;

    private int position;

    private String name;

    private String stepType;

    private int maxAttempts;

    @Version
    private Long lockVersion;

    protected StepDefinition() {
    }

    StepDefinition(WorkflowDefinition workflowDefinition, int position, String name, String stepType, int maxAttempts) {
        this.id = UUID.randomUUID();
        this.workflowDefinition = workflowDefinition;
        this.position = position;
        this.name = name;
        this.stepType = stepType;
        this.maxAttempts = maxAttempts;
    }

    public UUID getId() {
        return id;
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

    public int getMaxAttempts() {
        return maxAttempts;
    }
}
