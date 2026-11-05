package io.github.louaychayeb.flowforge.orchestrator.api;

import io.github.louaychayeb.flowforge.orchestrator.engine.RunTracing;
import io.github.louaychayeb.flowforge.orchestrator.run.AttemptStatus;
import io.github.louaychayeb.flowforge.orchestrator.run.RunStatus;
import io.github.louaychayeb.flowforge.orchestrator.run.StepAttempt;
import io.github.louaychayeb.flowforge.orchestrator.run.StepExecution;
import io.github.louaychayeb.flowforge.orchestrator.run.StepKind;
import io.github.louaychayeb.flowforge.orchestrator.run.StepStatus;
import io.github.louaychayeb.flowforge.orchestrator.run.WorkflowRun;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Response bodies for the run endpoints.
 */
public final class RunDtos {

    private RunDtos() {
    }

    public record RunCreatedResponse(UUID runId, String workflow, int version, RunStatus status) {

        public static RunCreatedResponse from(WorkflowRun run) {
            return new RunCreatedResponse(run.getId(), run.getDefinition().getName(),
                    run.getDefinition().getVersion(), run.getStatus());
        }
    }

    public record RunResponse(
            UUID runId,
            String workflow,
            int version,
            RunStatus status,
            String currentStep,
            List<String> activeSteps,
            Map<String, Object> input,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt,
            String traceId,
            List<StepExecutionResponse> steps
    ) {

        public static RunResponse from(WorkflowRun run) {
            return new RunResponse(
                    run.getId(),
                    run.getDefinition().getName(),
                    run.getDefinition().getVersion(),
                    run.getStatus(),
                    run.currentStep().map(StepExecution::getName).orElse(null),
                    run.activeSteps().stream()
                            .map(s -> s.getKind() == StepKind.COMPENSATION ? "undo " + s.getName() : s.getName())
                            .toList(),
                    run.getInput(),
                    run.getCreatedAt(),
                    run.getUpdatedAt(),
                    run.getCompletedAt(),
                    RunTracing.traceId(run.getTraceParent()),
                    run.getSteps().stream().map(StepExecutionResponse::from).toList());
        }
    }

    public record StepExecutionResponse(
            UUID stepExecutionId,
            String name,
            String type,
            StepKind kind,
            StepStatus status,
            int attemptCount,
            int maxAttempts,
            List<String> dependsOn,
            Instant nextAttemptAt,
            Instant leaseExpiresAt,
            List<AttemptResponse> attempts
    ) {

        public static StepExecutionResponse from(StepExecution step) {
            return new StepExecutionResponse(step.getId(), step.getName(), step.getStepType(), step.getKind(),
                    step.getStatus(), step.getAttemptCount(), step.getMaxAttempts(),
                    step.getDependsOn().stream().map(p -> step.getRun().getSteps().get(p).getName()).toList(),
                    step.getNextAttemptAt(),
                    step.getLeaseExpiresAt(), step.getAttempts().stream().map(AttemptResponse::from).toList());
        }
    }

    public record AttemptResponse(
            int attempt,
            AttemptStatus status,
            Instant startedAt,
            Instant finishedAt,
            String errorMessage
    ) {

        public static AttemptResponse from(StepAttempt attempt) {
            return new AttemptResponse(attempt.getAttemptNumber(), attempt.getStatus(), attempt.getStartedAt(),
                    attempt.getFinishedAt(), attempt.getErrorMessage());
        }
    }
}
