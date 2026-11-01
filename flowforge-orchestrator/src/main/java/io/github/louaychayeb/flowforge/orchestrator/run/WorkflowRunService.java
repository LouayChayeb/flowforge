package io.github.louaychayeb.flowforge.orchestrator.run;

import io.github.louaychayeb.flowforge.orchestrator.api.NotFoundException;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinition;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinitionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Service
public class WorkflowRunService {

    private final WorkflowDefinitionRepository definitions;
    private final WorkflowRunRepository runs;
    private final Clock clock;

    public WorkflowRunService(WorkflowDefinitionRepository definitions, WorkflowRunRepository runs, Clock clock) {
        this.definitions = definitions;
        this.runs = runs;
        this.clock = clock;
    }

    /**
     * Starts a run of the latest version of the named workflow. Only state is written here; the
     * dispatcher picks the first step up asynchronously, so this returns immediately.
     */
    @Transactional
    public WorkflowRun start(String workflowName, Map<String, Object> input) {
        WorkflowDefinition definition = definitions.findFirstByNameOrderByVersionDesc(workflowName)
                .orElseThrow(() -> new NotFoundException("Workflow " + workflowName + " not found"));
        return runs.save(WorkflowRun.start(definition, input, clock.instant()));
    }

    /**
     * Loads a run and maps it inside the transaction, so lazy associations can be read.
     */
    @Transactional(readOnly = true)
    public <T> T find(UUID runId, Function<WorkflowRun, T> mapper) {
        WorkflowRun run = runs.findById(runId)
                .orElseThrow(() -> new NotFoundException("Run " + runId + " not found"));
        return mapper.apply(run);
    }
}
