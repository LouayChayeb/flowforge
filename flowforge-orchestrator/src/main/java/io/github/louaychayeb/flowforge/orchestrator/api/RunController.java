package io.github.louaychayeb.flowforge.orchestrator.api;

import io.github.louaychayeb.flowforge.orchestrator.api.RunDtos.RunResponse;
import io.github.louaychayeb.flowforge.orchestrator.insight.InsightQueries;
import io.github.louaychayeb.flowforge.orchestrator.run.WorkflowRunService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/runs")
public class RunController {

    private static final int MAX_LIMIT = 200;

    private final WorkflowRunService runService;
    private final InsightQueries insight;

    public RunController(WorkflowRunService runService, InsightQueries insight) {
        this.runService = runService;
        this.insight = insight;
    }

    /** The most recent runs, newest first. */
    @GetMapping
    public List<InsightQueries.RunSummary> recent(@RequestParam(defaultValue = "30") int limit,
                                                  @RequestParam(required = false) String status) {
        return insight.recentRuns(Math.max(1, Math.min(limit, MAX_LIMIT)), status);
    }

    @GetMapping("/{runId}")
    public RunResponse get(@PathVariable UUID runId) {
        return runService.find(runId, RunResponse::from);
    }

    /** The step commands the run produced, with when each was written to the outbox and sent to Kafka. */
    @GetMapping("/{runId}/commands")
    public List<InsightQueries.CommandMessage> commands(@PathVariable UUID runId) {
        runService.find(runId, run -> run.getId()); // 404 for an unknown run
        return insight.commandsOfRun(runId);
    }
}
