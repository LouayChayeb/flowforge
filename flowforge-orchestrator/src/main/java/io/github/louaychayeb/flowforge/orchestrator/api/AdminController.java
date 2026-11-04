package io.github.louaychayeb.flowforge.orchestrator.api;

import io.github.louaychayeb.flowforge.orchestrator.consistency.ConsistencyChecker;
import io.github.louaychayeb.flowforge.orchestrator.consistency.ConsistencyReport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ConsistencyChecker checker;

    public AdminController(ConsistencyChecker checker) {
        this.checker = checker;
    }

    /**
     * Checks stored execution history against FlowForge's invariants.
     *
     * @param workflow optional workflow name to restrict the check to
     */
    @GetMapping("/consistency")
    public ConsistencyReport consistency(@RequestParam(required = false) String workflow) {
        return checker.check(workflow);
    }
}
