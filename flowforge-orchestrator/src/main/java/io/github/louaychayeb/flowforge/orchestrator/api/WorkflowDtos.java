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

import java.time.Duration;
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
            String compensateWith,
            // Names of earlier steps to wait for. Omitted: the previous step. Empty: start immediately.
            List<@NotBlank String> dependsOn,
            // For type WAIT only: an ISO-8601 duration such as "PT20S" or "P3D".
            Duration waitFor
    ) {
    }

    public record WorkflowResponse(String name, int version, Instant createdAt, List<StepResponse> steps) {

        public static WorkflowResponse from(WorkflowDefinition definition) {
            return new WorkflowResponse(definition.getName(), definition.getVersion(), definition.getCreatedAt(),
                    definition.getSteps().stream().map(s -> StepResponse.from(s, definition.getSteps())).toList());
        }
    }

    public record StepResponse(String name, String type, int maxAttempts, String compensateWith,
                               List<String> dependsOn, Duration waitFor) {

        public static StepResponse from(StepDefinition step, List<StepDefinition> all) {
            return new StepResponse(step.getName(), step.getStepType(), step.getMaxAttempts(),
                    step.getCompensationType(), step.getDependsOn().stream().map(p -> all.get(p).getName()).toList(),
                    step.getWaitDuration());
        }
    }

    public record StartRunRequest(@NotNull Map<String, Object> input) {
    }

    /** Body of an operator action; the reason is kept in the run's audit log. */
    public record OperatorActionRequest(@Size(max = 500) String reason) {
    }

    /** A signal for a waiting SIGNAL step: its payload becomes the step's output, handed to every later step. */
    public record SignalRequest(Map<String, Object> payload, @Size(max = 500) String reason) {
    }
}
