package io.github.louaychayeb.flowforge.orchestrator.api;

import io.github.louaychayeb.flowforge.orchestrator.api.RunDtos.RunResponse;
import io.github.louaychayeb.flowforge.orchestrator.run.WorkflowRunService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final WorkflowRunService runService;

    public RunController(WorkflowRunService runService) {
        this.runService = runService;
    }

    @GetMapping("/{runId}")
    public RunResponse get(@PathVariable UUID runId) {
        return runService.find(runId, RunResponse::from);
    }
}
