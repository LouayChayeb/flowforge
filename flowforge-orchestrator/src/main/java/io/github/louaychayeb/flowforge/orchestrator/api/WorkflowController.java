package io.github.louaychayeb.flowforge.orchestrator.api;

import io.github.louaychayeb.flowforge.orchestrator.api.RunDtos.RunCreatedResponse;
import io.github.louaychayeb.flowforge.orchestrator.api.WorkflowDtos.CreateWorkflowRequest;
import io.github.louaychayeb.flowforge.orchestrator.api.WorkflowDtos.StartRunRequest;
import io.github.louaychayeb.flowforge.orchestrator.api.WorkflowDtos.WorkflowResponse;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinition;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinitionService;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinitionService.NewStep;
import io.github.louaychayeb.flowforge.orchestrator.error.MalformedRequestException;
import io.github.louaychayeb.flowforge.orchestrator.run.RunStarter;
import io.github.louaychayeb.flowforge.orchestrator.run.WorkflowRun;
import io.github.louaychayeb.flowforge.orchestrator.run.WorkflowRunService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final int MAX_KEY_LENGTH = 200;

    private final WorkflowDefinitionService definitionService;
    private final RunStarter runStarter;

    public WorkflowController(WorkflowDefinitionService definitionService, RunStarter runStarter) {
        this.definitionService = definitionService;
        this.runStarter = runStarter;
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
     * Starts a run and returns immediately ({@code 202}); poll the {@code Location} to follow its progress.
     *
     * <p>With an {@code Idempotency-Key} header the run starts at most once per key: repeating the request — a retry
     * after a timeout, the same order submitted twice — answers {@code 200} with the run the key already started and
     * {@code Idempotent-Replayed: true}. The same key with a different workflow or input is refused ({@code 422}).
     */
    @PostMapping("/{name}/runs")
    public ResponseEntity<RunCreatedResponse> startRun(@PathVariable String name,
                                                       @RequestHeader(value = IDEMPOTENCY_KEY, required = false)
                                                       String idempotencyKey,
                                                       @Valid @RequestBody StartRunRequest request) {
        if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH)) {
            throw new MalformedRequestException("The Idempotency-Key header must be 1 to " + MAX_KEY_LENGTH
                    + " characters", Map.of("header", IDEMPOTENCY_KEY));
        }
        WorkflowRunService.Started started = runStarter.start(name, request.input(), idempotencyKey);
        WorkflowRun run = started.run();
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/runs/{id}").buildAndExpand(run.getId()).toUri();
        if (started.replayed()) {
            return ResponseEntity.ok().location(location).header("Idempotent-Replayed", "true")
                    .body(RunCreatedResponse.from(run));
        }
        return ResponseEntity.accepted().location(location).body(RunCreatedResponse.from(run));
    }
}
