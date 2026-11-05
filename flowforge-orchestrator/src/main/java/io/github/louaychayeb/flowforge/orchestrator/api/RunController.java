package io.github.louaychayeb.flowforge.orchestrator.api;

import io.github.louaychayeb.flowforge.orchestrator.api.RunDtos.RunResponse;
import io.github.louaychayeb.flowforge.orchestrator.api.WorkflowDtos.OperatorActionRequest;
import io.github.louaychayeb.flowforge.orchestrator.insight.InsightQueries;
import io.github.louaychayeb.flowforge.orchestrator.operator.OperatorActionRepository;
import io.github.louaychayeb.flowforge.orchestrator.operator.OperatorService;
import io.github.louaychayeb.flowforge.orchestrator.run.RunStatus;
import io.github.louaychayeb.flowforge.orchestrator.run.WorkflowRunService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/runs")
public class RunController {

    private static final int MAX_LIMIT = 200;
    /** Who performed an operator action, from the {@code X-Actor} header; a real deployment takes it from auth. */
    static final String ACTOR_HEADER = "X-Actor";
    private static final int MAX_ACTOR_LENGTH = 100;

    private final WorkflowRunService runService;
    private final OperatorService operators;
    private final OperatorActionRepository actions;
    private final InsightQueries insight;

    public RunController(WorkflowRunService runService, OperatorService operators, OperatorActionRepository actions,
                         InsightQueries insight) {
        this.runService = runService;
        this.operators = operators;
        this.actions = actions;
        this.insight = insight;
    }

    /** The most recent runs, newest first. An unknown {@code status} is a 400, not an empty list. */
    @GetMapping
    public List<InsightQueries.RunSummary> recent(@RequestParam(defaultValue = "30") int limit,
                                                  @RequestParam(required = false) RunStatus status) {
        return insight.recentRuns(Math.max(1, Math.min(limit, MAX_LIMIT)), status == null ? null : status.name());
    }

    @GetMapping("/{runId}")
    public RunResponse get(@PathVariable UUID runId) {
        return runService.find(runId, run -> RunResponse.from(run, actions.findByWorkflowRunIdOrderByPerformedAt(runId)));
    }

    /** The step commands the run produced, with when each was written to the outbox and sent to Kafka. */
    @GetMapping("/{runId}/commands")
    public List<InsightQueries.CommandMessage> commands(@PathVariable UUID runId) {
        runService.find(runId, run -> run.getId()); // 404 for an unknown run
        return insight.commandsOfRun(runId);
    }

    /**
     * Cancels a running run: nothing new starts, in-flight steps finish, completed steps are compensated, and the run
     * ends {@code CANCELLED}. {@code 202}: the winding down happens afterwards. {@code 409} if it already finished.
     */
    @PostMapping("/{runId}/cancel")
    public ResponseEntity<RunResponse> cancel(@PathVariable UUID runId,
                                              @RequestHeader(value = ACTOR_HEADER, required = false) String actor,
                                              @Valid @RequestBody(required = false) OperatorActionRequest request) {
        operators.cancel(runId, actor(actor), request == null ? null : request.reason());
        return ResponseEntity.accepted().body(get(runId));
    }

    /**
     * Retries the compensation that failed in a {@code COMPENSATION_FAILED} run, e.g. once the service that rejected
     * it is back. {@code 409} for a run in any other state.
     */
    @PostMapping("/{runId}/retry")
    public ResponseEntity<RunResponse> retry(@PathVariable UUID runId,
                                             @RequestHeader(value = ACTOR_HEADER, required = false) String actor,
                                             @Valid @RequestBody(required = false) OperatorActionRequest request) {
        operators.retryCompensation(runId, actor(actor), request == null ? null : request.reason());
        return ResponseEntity.accepted().body(get(runId));
    }

    private static String actor(String header) {
        if (header == null || header.isBlank()) {
            return "anonymous";
        }
        String trimmed = header.strip();
        return trimmed.length() <= MAX_ACTOR_LENGTH ? trimmed : trimmed.substring(0, MAX_ACTOR_LENGTH);
    }
}
