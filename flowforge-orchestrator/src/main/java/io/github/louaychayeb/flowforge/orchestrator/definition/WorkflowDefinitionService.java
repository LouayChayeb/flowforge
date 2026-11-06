package io.github.louaychayeb.flowforge.orchestrator.definition;

import io.github.louaychayeb.flowforge.orchestrator.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class WorkflowDefinitionService {

    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    /** Longest timer accepted: long enough for real waiting periods, short enough to catch unit mistakes. */
    static final Duration MAX_WAIT = Duration.ofDays(30);

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
     * @param waitFor          for the built-in types only: how long a {@code WAIT} timer waits, or how long a
     *                         {@code SIGNAL} step waits at most for its signal
     */
    public record NewStep(String name, String type, Integer maxAttempts, String compensationType,
                          List<String> dependsOn, Duration waitFor) {

        public NewStep(String name, String type, Integer maxAttempts) {
            this(name, type, maxAttempts, null, null, null);
        }

        public NewStep(String name, String type, Integer maxAttempts, String compensationType) {
            this(name, type, maxAttempts, compensationType, null, null);
        }

        public NewStep(String name, String type, Integer maxAttempts, String compensationType,
                       List<String> dependsOn) {
            this(name, type, maxAttempts, compensationType, dependsOn, null);
        }

        /** A durable timer step. */
        public static NewStep timer(String name, Duration wait, List<String> dependsOn) {
            return new NewStep(name, WorkflowDefinition.WAIT, 1, null, dependsOn, wait);
        }

        /** A step that waits up to {@code timeout} for the signal named after it. */
        public static NewStep signal(String name, Duration timeout, List<String> dependsOn) {
            return new NewStep(name, WorkflowDefinition.SIGNAL, 1, null, dependsOn, timeout);
        }
    }

    /**
     * Registers a new version of the named workflow. Versions start at 1 and increase by one; runs in flight keep the
     * version they started with.
     *
     * <p>Step types are not checked here: handlers live in the workers, which the orchestrator does not know. A step
     * whose type no worker handles fails permanently when it runs.
     *
     * @throws InvalidWorkflowDefinitionException if the steps do not form a valid definition
     */
    @Transactional
    public WorkflowDefinition register(String name, List<NewStep> steps) {
        validate(steps);
        int version = definitions.findLatestVersion(name) + 1;
        WorkflowDefinition definition = new WorkflowDefinition(name, version, clock.instant());
        Map<String, Integer> positions = new HashMap<>();
        for (NewStep step : steps) {
            List<Integer> dependsOn = step.dependsOn() == null
                    ? (positions.isEmpty() ? List.of() : List.of(positions.size() - 1))
                    : step.dependsOn().stream().map(positions::get).toList();
            if (WorkflowDefinition.WAIT.equals(step.type())) {
                definition.addTimer(step.name(), step.waitFor(), dependsOn);
            } else if (WorkflowDefinition.SIGNAL.equals(step.type())) {
                definition.addSignal(step.name(), step.waitFor(), dependsOn);
            } else {
                int maxAttempts = step.maxAttempts() == null ? DEFAULT_MAX_ATTEMPTS : step.maxAttempts();
                definition.addStep(step.name(), step.type(), maxAttempts, step.compensationType(), dependsOn);
            }
            positions.put(step.name(), positions.size());
        }
        return definitions.save(definition);
    }

    /**
     * @throws NotFoundException.WorkflowNotFound if no version of the workflow exists
     */
    @Transactional(readOnly = true)
    public WorkflowDefinition latest(String name) {
        WorkflowDefinition definition = definitions.findFirstByNameOrderByVersionDesc(name)
                .orElseThrow(() -> new NotFoundException.WorkflowNotFound(name));
        definition.getSteps().size(); // initialise while the session is open
        return definition;
    }

    /** The latest version of every registered workflow, steps loaded. */
    @Transactional(readOnly = true)
    public List<WorkflowDefinition> latestOfEach() {
        List<WorkflowDefinition> all = definitions.findLatestOfEach();
        all.forEach(definition -> definition.getSteps().size());
        return all;
    }

    private void validate(List<NewStep> steps) {
        if (steps == null || steps.isEmpty()) {
            throw new InvalidWorkflowDefinitionException("A workflow needs at least one step");
        }
        Set<String> names = new HashSet<>();
        for (NewStep step : steps) {
            validateDependencies(step, names);
            validateBuiltIn(step);
            if (!names.add(step.name())) {
                throw new InvalidWorkflowDefinitionException("Duplicate step name " + step.name(), step.name());
            }
        }
    }

    private static void validateDependencies(NewStep step, Set<String> earlier) {
        if (step.dependsOn() == null) {
            return;
        }
        for (String dependency : step.dependsOn()) {
            if (dependency.equals(step.name())) {
                throw new InvalidWorkflowDefinitionException("Step " + step.name() + " cannot depend on itself",
                        step.name());
            }
            // Earlier steps only: this is what keeps a definition free of cycles.
            if (!earlier.contains(dependency)) {
                throw new InvalidWorkflowDefinitionException("Step " + step.name() + " depends on " + dependency
                        + ", which is not a step declared before it", step.name());
            }
        }
        if (new HashSet<>(step.dependsOn()).size() != step.dependsOn().size()) {
            throw new InvalidWorkflowDefinitionException("Step " + step.name() + " lists a dependency twice",
                    step.name());
        }
    }

    /** The built-in types, WAIT and SIGNAL, need a wait (a signal's deadline); no other step may have one. */
    private static void validateBuiltIn(NewStep step) {
        String type = step.type();
        boolean builtIn = WorkflowDefinition.WAIT.equals(type) || WorkflowDefinition.SIGNAL.equals(type);
        if (!builtIn) {
            if (step.waitFor() != null) {
                throw new InvalidWorkflowDefinitionException("Only WAIT and SIGNAL steps can have a wait, not "
                        + step.name(), step.name());
            }
            return;
        }
        if (step.waitFor() == null || step.waitFor().isZero() || step.waitFor().isNegative()) {
            throw new InvalidWorkflowDefinitionException(type + " step " + step.name() + " needs a positive "
                    + (WorkflowDefinition.SIGNAL.equals(type) ? "deadline (waitFor)" : "wait"), step.name());
        }
        if (step.waitFor().compareTo(MAX_WAIT) > 0) {
            throw new InvalidWorkflowDefinitionException(type + " step " + step.name() + " waits longer than "
                    + MAX_WAIT.toDays() + " days", step.name());
        }
        if (step.compensationType() != null) {
            throw new InvalidWorkflowDefinitionException(type + " step " + step.name() + " has nothing to compensate",
                    step.name());
        }
    }
}
