package io.github.louaychayeb.flowforge.orchestrator.definition;

import io.github.louaychayeb.flowforge.orchestrator.api.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
     * @param dependsOn        names of earlier steps this one waits for; {@code null} means the step before it (the
     *                         classic sequential workflow), an empty list means it can start immediately
     */
    public record NewStep(String name, String type, Integer maxAttempts, String compensationType,
                          List<String> dependsOn) {

        public NewStep(String name, String type, Integer maxAttempts) {
            this(name, type, maxAttempts, null, null);
        }

        public NewStep(String name, String type, Integer maxAttempts, String compensationType) {
            this(name, type, maxAttempts, compensationType, null);
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
        Map<String, Integer> positions = new HashMap<>();
        for (NewStep step : steps) {
            int maxAttempts = step.maxAttempts() == null ? DEFAULT_MAX_ATTEMPTS : step.maxAttempts();
            if (step.dependsOn() == null) {
                definition.addStep(step.name(), step.type(), maxAttempts, step.compensationType());
            } else {
                definition.addStep(step.name(), step.type(), maxAttempts, step.compensationType(),
                        step.dependsOn().stream().map(positions::get).toList());
            }
            positions.put(step.name(), positions.size());
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
    /** The latest version of every registered workflow, steps loaded. */
    @Transactional(readOnly = true)
    public List<WorkflowDefinition> latestOfEach() {
        List<WorkflowDefinition> all = definitions.findLatestOfEach();
        all.forEach(definition -> definition.getSteps().size());
        return all;
    }

    private void validate(List<NewStep> steps) {
        Set<String> names = new HashSet<>();
        for (NewStep step : steps) {
            if (step.dependsOn() != null) {
                for (String dependency : step.dependsOn()) {
                    if (dependency.equals(step.name())) {
                        throw new InvalidWorkflowDefinitionException("Step " + step.name() + " cannot depend on itself");
                    }
                    // Earlier steps only: this is what keeps a definition free of cycles.
                    if (!names.contains(dependency)) {
                        throw new InvalidWorkflowDefinitionException("Step " + step.name() + " depends on " + dependency
                                + ", which is not a step declared before it");
                    }
                }
                if (new HashSet<>(step.dependsOn()).size() != step.dependsOn().size()) {
                    throw new InvalidWorkflowDefinitionException("Step " + step.name() + " lists a dependency twice");
                }
            }
            if (!names.add(step.name())) {
                throw new InvalidWorkflowDefinitionException("Duplicate step name " + step.name());
            }
        }
    }
}
