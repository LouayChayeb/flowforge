package io.github.louaychayeb.flowforge.orchestrator.run;

import io.github.louaychayeb.flowforge.orchestrator.error.NotFoundException;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinition;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinitionRepository;
import io.github.louaychayeb.flowforge.orchestrator.engine.EngineEvents;
import io.github.louaychayeb.flowforge.orchestrator.engine.RunTracing;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Service
public class WorkflowRunService {

    private final WorkflowDefinitionRepository definitions;
    private final WorkflowRunRepository runs;
    private final ApplicationEventPublisher events;
    private final RunTracing tracing;
    private final Clock clock;

    public WorkflowRunService(WorkflowDefinitionRepository definitions, WorkflowRunRepository runs,
                              ApplicationEventPublisher events, RunTracing tracing, Clock clock) {
        this.definitions = definitions;
        this.runs = runs;
        this.events = events;
        this.tracing = tracing;
        this.clock = clock;
    }

    /**
     * Starts a run of the latest version of the named workflow. Only state is written here; the
     * dispatcher picks the first step up asynchronously (woken as soon as this commits), so this returns
     * immediately.
     */
    @Transactional
    public WorkflowRun start(String workflowName, Map<String, Object> input) {
        WorkflowDefinition definition = definitions.findFirstByNameOrderByVersionDesc(workflowName)
                .orElseThrow(() -> new NotFoundException.WorkflowNotFound(workflowName));
        Instant now = clock.instant();
        WorkflowRun run = WorkflowRun.start(definition, input, now);
        run.attachTrace(tracing.runStarted(workflowName, run.getId().toString()));
        runs.save(run);
        // Steps without dependencies are claimable now; a timer among them, when it is due.
        run.activeSteps().stream().map(StepExecution::getNextAttemptAt).distinct()
                .forEach(at -> events.publishEvent(new EngineEvents.StepsMayBeReady(at)));
        return run;
    }

    /**
     * Loads a run and maps it inside the transaction, so lazy associations can be read.
     */
    @Transactional(readOnly = true)
    public <T> T find(UUID runId, Function<WorkflowRun, T> mapper) {
        WorkflowRun run = runs.findById(runId)
                .orElseThrow(() -> new NotFoundException.RunNotFound(runId));
        return mapper.apply(run);
    }
}
