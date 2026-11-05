package io.github.louaychayeb.flowforge.orchestrator.operator;

import io.github.louaychayeb.flowforge.orchestrator.run.RunStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One manual intervention on a run, kept forever: who did what, when, why, and the run's status just before. The
 * engine's own history (attempts) never records operator decisions, so this is the only place they live.
 */
@Entity
@Table(name = "operator_action")
public class OperatorAction {

    public enum Type {
        CANCEL,
        RETRY_COMPENSATION
    }

    @Id
    private UUID id;

    private UUID workflowRunId;

    @Enumerated(EnumType.STRING)
    private Type action;

    private String actor;

    private String reason;

    @Enumerated(EnumType.STRING)
    private RunStatus runStatus;

    private Instant performedAt;

    @Version
    private Long lockVersion;

    protected OperatorAction() {
    }

    public OperatorAction(UUID workflowRunId, Type action, String actor, String reason, RunStatus runStatus,
                          Instant performedAt) {
        this.id = UUID.randomUUID();
        this.workflowRunId = Objects.requireNonNull(workflowRunId, "workflowRunId");
        this.action = Objects.requireNonNull(action, "action");
        this.actor = Objects.requireNonNull(actor, "actor");
        this.reason = reason;
        this.runStatus = Objects.requireNonNull(runStatus, "runStatus");
        this.performedAt = Objects.requireNonNull(performedAt, "performedAt");
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkflowRunId() {
        return workflowRunId;
    }

    public Type getAction() {
        return action;
    }

    public String getActor() {
        return actor;
    }

    public String getReason() {
        return reason;
    }

    /** The run's status just before the action. */
    public RunStatus getRunStatus() {
        return runStatus;
    }

    public Instant getPerformedAt() {
        return performedAt;
    }
}
