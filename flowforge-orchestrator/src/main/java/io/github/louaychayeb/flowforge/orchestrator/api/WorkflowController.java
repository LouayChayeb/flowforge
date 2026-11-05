package io.github.louaychayeb.flowforge.orchestrator.api;

import io.github.louaychayeb.flowforge.orchestrator.api.RunDtos.RunCreatedResponse;
import io.github.louaychayeb.flowforge.orchestrator.api.WorkflowDtos.CreateWorkflowRequest;
import io.github.louaychayeb.flowforge.orchestrator.api.WorkflowDtos.StartRunRequest;
import io.github.louaychayeb.flowforge.orchestrator.api.WorkflowDtos.WorkflowResponse;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinition;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinitionService;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinitionService.NewStep;
import io.github.louaychayeb.flowforge.orchestrator.run.WorkflowRun;
import io.github.louaychayeb.flowforge.orchestrator.run.WorkflowRunService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final WorkflowDefinitionService definitionService;
    private final WorkflowRunService runService;

    public WorkflowController(WorkflowDefinitionService definitionService, WorkflowRunService runService) {
        this.definitionService = definitionService;
        this.runService = runService;
    }

    /**
     * Registers a workflow definition. Posting an existing name registers its next version.
     */
    @PostMapping
    public ResponseEntity<WorkflowResponse> register(@Valid @RequestBody CreateWorkflowRequest request) {
        WorkflowDefinition definition = definitionService.register(request.name(), request.steps().stream()
                .map(step -> new NewStep(step.name(), step.type(), step.maxAttempts(), step.compensateWith(),
                        step.dependsOn(), step.waitFor()))
                .toList());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{name}").buildAndExpand(definition.getName()).toUri();
        return ResponseEntity.created(location).body(WorkflowResponse.from(definition));
    }

    /** The latest version of every registered workflow. */
    @GetMapping
    public List<WorkflowResponse> all() {
        return definitionService.latestOfEach().stream().map(WorkflowResponse::from).toList();
    }

    @GetMapping("/{name}")
    public WorkflowResponse latest(@PathVariable String name) {
        return WorkflowResponse.from(definitionService.latest(name));
    }

    /**
     * Starts a run and returns immediately; poll the {@code Location} to follow its progress.
     */
    @PostMapping("/{name}/runs")
    public ResponseEntity<RunCreatedResponse> startRun(@PathVariable String name,
                                                       @Valid @RequestBody StartRunRequest request) {
        WorkflowRun run = runService.start(name, request.input());
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/runs/{id}").buildAndExpand(run.getId()).toUri();
        return ResponseEntity.accepted().location(location).body(RunCreatedResponse.from(run));
    }
}
