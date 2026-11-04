package io.github.louaychayeb.flowforge.orchestrator.api;

import io.github.louaychayeb.flowforge.orchestrator.definition.StepDefinition;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Request and response bodies for the workflow definition endpoints.
 */
public final class WorkflowDtos {

    static final String NAME_PATTERN = "^[a-z0-9]+(-[a-z0-9]+)*$";
    static final String STEP_TYPE_PATTERN = "^[A-Z][A-Z0-9_]*$";

    private WorkflowDtos() {
    }

    public record CreateWorkflowRequest(
            @NotBlank @Size(max = 100) @Pattern(regexp = NAME_PATTERN, message = "must be lowercase kebab-case")
            String name,
            @NotEmpty @Size(max = 50) List<@Valid @NotNull StepRequest> steps
    ) {
    }

    public record StepRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 100) @Pattern(regexp = STEP_TYPE_PATTERN, message = "must be UPPER_SNAKE_CASE")
            String type,
            @Min(1) @Max(20) Integer maxAttempts,
            @Size(max = 100) @Pattern(regexp = STEP_TYPE_PATTERN, message = "must be UPPER_SNAKE_CASE")
            String compensateWith
    ) {
    }

    public record WorkflowResponse(String name, int version, Instant createdAt, List<StepResponse> steps) {

        public static WorkflowResponse from(WorkflowDefinition definition) {
            return new WorkflowResponse(definition.getName(), definition.getVersion(), definition.getCreatedAt(),
                    definition.getSteps().stream().map(StepResponse::from).toList());
        }
    }

    public record StepResponse(String name, String type, int maxAttempts, String compensateWith) {

        public static StepResponse from(StepDefinition step) {
            return new StepResponse(step.getName(), step.getStepType(), step.getMaxAttempts(),
                    step.getCompensationType());
        }
    }

    public record StartRunRequest(@NotNull Map<String, Object> input) {
    }
}
