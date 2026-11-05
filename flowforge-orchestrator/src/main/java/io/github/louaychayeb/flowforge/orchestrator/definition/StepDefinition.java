package io.github.louaychayeb.flowforge.orchestrator.definition;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * One step of a {@link WorkflowDefinition}: what to run ({@code stepType}), how many times it may be attempted,
 * optionally what undoes it ({@code compensationType}), and which earlier steps it waits for ({@code dependsOn}).
 * Steps whose dependencies are all complete run in parallel.
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

    private String compensationType;

    /** Positions of the earlier steps this one waits for; empty for a step that can start immediately. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Integer> dependsOn = new ArrayList<>();

    @Version
    private Long lockVersion;

    protected StepDefinition() {
    }

    StepDefinition(WorkflowDefinition workflowDefinition, int position, String name, String stepType, int maxAttempts,
                   String compensationType, List<Integer> dependsOn) {
        this.id = UUID.randomUUID();
        this.workflowDefinition = workflowDefinition;
        this.position = position;
        this.name = name;
        this.stepType = stepType;
        this.maxAttempts = maxAttempts;
        this.compensationType = compensationType;
        this.dependsOn = new ArrayList<>(dependsOn);
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

    /**
     * Step type that undoes this step, or {@code null} if the step needs no compensation.
     */
    public String getCompensationType() {
        return compensationType;
    }

    /** Positions of the steps this one waits for. */
    public List<Integer> getDependsOn() {
        return Collections.unmodifiableList(dependsOn);
    }
}
