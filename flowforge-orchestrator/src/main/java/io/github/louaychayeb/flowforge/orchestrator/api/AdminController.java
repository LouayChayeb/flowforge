package io.github.louaychayeb.flowforge.orchestrator.api;

import io.github.louaychayeb.flowforge.orchestrator.consistency.ConsistencyChecker;
import io.github.louaychayeb.flowforge.orchestrator.consistency.ConsistencyReport;
import io.github.louaychayeb.flowforge.orchestrator.engine.EngineMetrics;
import io.github.louaychayeb.flowforge.orchestrator.insight.EngineSettings;
import io.github.louaychayeb.flowforge.orchestrator.insight.InsightQueries;
import io.github.louaychayeb.flowforge.orchestrator.insight.KafkaInspector;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Operational views: correctness, configuration, what is in the database right now and what Kafka sees.
 * The dashboard at {@code /} is built on these.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ConsistencyChecker checker;
    private final InsightQueries insight;
    private final KafkaInspector kafka;
    private final EngineSettings settings;
    private final EngineMetrics metrics;
    private final Clock clock;

    public AdminController(ConsistencyChecker checker, InsightQueries insight, KafkaInspector kafka,
                           EngineSettings settings, EngineMetrics metrics, Clock clock) {
        this.checker = checker;
        this.insight = insight;
        this.kafka = kafka;
        this.settings = settings;
        this.metrics = metrics;
        this.clock = clock;
    }

    @GetMapping("/consistency")
    public ConsistencyReport consistency(@RequestParam(required = false) String workflow) {
        return checker.check(workflow);
    }

    @GetMapping("/consistency/invariants")
    public List<ConsistencyChecker.InvariantInfo> invariants() {
        return checker.invariants();
    }

    public record EngineStats(Instant now, String instance, EngineMetrics.Snapshot counters,
                              InsightQueries.DatabaseStats database) {
    }

    /**
     * Database-wide counts and the steps holding a lease or waiting to retry. The counters are this
     * instance's only: with several orchestrators each one counts what it did itself.
     */
    @GetMapping("/stats")
    public EngineStats stats() {
        return new EngineStats(clock.instant(), settings.view().instance(), metrics.snapshot(), insight.databaseStats());
    }

    @GetMapping("/config")
    public EngineSettings.View config() {
        return settings.view();
    }

    @GetMapping("/kafka")
    public KafkaInspector.KafkaView kafka() {
        return kafka.snapshot();
    }
}
