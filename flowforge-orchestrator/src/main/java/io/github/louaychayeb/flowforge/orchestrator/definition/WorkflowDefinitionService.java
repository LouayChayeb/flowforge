package io.github.louaychayeb.flowforge.orchestrator.definition;

import io.github.louaychayeb.flowforge.orchestrator.api.NotFoundException;
import io.github.louaychayeb.flowforge.orchestrator.engine.StepHandlerRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class WorkflowDefinitionService {

    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final WorkflowDefinitionRepository definitions;
    private final StepHandlerRegistry handlers;
    private final Clock clock;

    public WorkflowDefinitionService(WorkflowDefinitionRepository definitions, StepHandlerRegistry handlers, Clock clock) {
        this.definitions = definitions;
        this.handlers = handlers;
        this.clock = clock;
    }

    public record NewStep(String name, String type, Integer maxAttempts) {
    }

    /**
     * Registers a new version of the named workflow. Versions start at 1 and increase by one.
     */
    @Transactional
    public WorkflowDefinition register(String name, List<NewStep> steps) {
        validate(steps);
        int version = definitions.findLatestVersion(name) + 1;
        WorkflowDefinition definition = new WorkflowDefinition(name, version, clock.instant());
        for (NewStep step : steps) {
            definition.addStep(step.name(), step.type(),
                    step.maxAttempts() == null ? DEFAULT_MAX_ATTEMPTS : step.maxAttempts());
        }
        return definitions.save(definition);
    }

    @Transactional(readOnly = true)
    public WorkflowDefinition latest(String name) {
        WorkflowDefinition definition = definitions.findFirstByNameOrderByVersionDesc(name)
                .orElseThrow(() -> new NotFoundException("Workflow " + name + " not found"));
        definition.getSteps().size(); // initialise while the session is open
        return definition;
    }

    private void validate(List<NewStep> steps) {
        Set<String> names = new HashSet<>();
        for (NewStep step : steps) {
            if (!names.add(step.name())) {
                throw new InvalidWorkflowDefinitionException("Duplicate step name " + step.name());
            }
            if (!handlers.supports(step.type())) {
                throw new InvalidWorkflowDefinitionException("Unknown step type " + step.type()
                        + "; supported types are " + handlers.stepTypes());
            }
        }
    }
}
