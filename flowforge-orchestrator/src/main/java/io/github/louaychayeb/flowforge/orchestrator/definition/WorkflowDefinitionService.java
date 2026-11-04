package io.github.louaychayeb.flowforge.orchestrator.definition;

import io.github.louaychayeb.flowforge.orchestrator.api.NotFoundException;
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
    private final Clock clock;

    public WorkflowDefinitionService(WorkflowDefinitionRepository definitions, Clock clock) {
        this.definitions = definitions;
        this.clock = clock;
    }

    /**
     * @param compensationType step type that undoes this step if the run fails later; {@code null} if none
     */
    public record NewStep(String name, String type, Integer maxAttempts, String compensationType) {

        public NewStep(String name, String type, Integer maxAttempts) {
            this(name, type, maxAttempts, null);
        }
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
                    step.maxAttempts() == null ? DEFAULT_MAX_ATTEMPTS : step.maxAttempts(), step.compensationType());
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

    /**
     * Step types are not checked here: handlers live in the workers, which the orchestrator does not know.
     * A step whose type no worker handles fails permanently when it runs.
     */
    private void validate(List<NewStep> steps) {
        Set<String> names = new HashSet<>();
        for (NewStep step : steps) {
            if (!names.add(step.name())) {
                throw new InvalidWorkflowDefinitionException("Duplicate step name " + step.name());
            }
        }
    }
}
