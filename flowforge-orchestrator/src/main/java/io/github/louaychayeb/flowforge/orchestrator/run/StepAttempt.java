package io.github.louaychayeb.flowforge.orchestrator.run;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * One try at executing a {@link StepExecution}. Attempts are appended, never overwritten,
 * so the full failure and retry history of every step is kept.
 */
@Entity
@Table(name = "step_attempt")
public class StepAttempt {

    static final int MAX_ERROR_LENGTH = 2000;

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "step_execution_id")
    private StepExecution stepExecution;

    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    private AttemptStatus status;

    private Instant startedAt;

    private Instant finishedAt;

    private String errorMessage;

    @Version
    private Long lockVersion;

    protected StepAttempt() {
    }

    StepAttempt(StepExecution stepExecution, int attemptNumber, Instant startedAt) {
        this.id = UUID.randomUUID();
        this.stepExecution = stepExecution;
        this.attemptNumber = attemptNumber;
        this.status = AttemptStatus.RUNNING;
        this.startedAt = startedAt;
    }

    void finish(AttemptStatus outcome, String error, Instant now) {
        if (status != AttemptStatus.RUNNING) {
            throw new IllegalStateTransitionException("Attempt %s is already %s".formatted(id, status));
        }
        status = outcome;
        finishedAt = now;
        errorMessage = truncate(error);
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_ERROR_LENGTH);
    }

    public UUID getId() {
        return id;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public AttemptStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
