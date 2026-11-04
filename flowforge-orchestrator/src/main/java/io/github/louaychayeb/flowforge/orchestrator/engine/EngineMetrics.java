package io.github.louaychayeb.flowforge.orchestrator.engine;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Counters for what the engine does, exposed under {@code /actuator/metrics/flowforge.*}. The stale-result
 * and expired-lease counters show how often the failure-handling paths actually fire.
 */
@Component
public class EngineMetrics {

    private final Counter stepsClaimed;
    private final Counter resultsApplied;
    private final Counter resultsStale;
    private final Counter leasesExpired;
    private final Counter outboxPublished;
    private final Counter outboxFailures;

    public EngineMetrics(MeterRegistry registry) {
        this.stepsClaimed = Counter.builder("flowforge.steps.claimed")
                .description("Step attempts claimed by a dispatcher").register(registry);
        this.resultsApplied = Counter.builder("flowforge.results").tag("result", "applied")
                .description("Step results received from workers").register(registry);
        this.resultsStale = Counter.builder("flowforge.results").tag("result", "stale")
                .description("Step results received from workers").register(registry);
        this.leasesExpired = Counter.builder("flowforge.leases.expired")
                .description("Running attempts timed out because their lease expired").register(registry);
        this.outboxPublished = Counter.builder("flowforge.outbox.published")
                .description("Outbox events sent to Kafka").register(registry);
        this.outboxFailures = Counter.builder("flowforge.outbox.failures")
                .description("Failed attempts to send an outbox event").register(registry);
    }

    public void stepsClaimed(int count) {
        stepsClaimed.increment(count);
    }

    public void resultApplied() {
        resultsApplied.increment();
    }

    public void resultStale() {
        resultsStale.increment();
    }

    public void leasesExpired(int count) {
        leasesExpired.increment(count);
    }

    public void outboxPublished(int count) {
        outboxPublished.increment(count);
    }

    public void outboxFailure() {
        outboxFailures.increment();
    }

    public double staleResults() {
        return resultsStale.count();
    }

    public double expiredLeases() {
        return leasesExpired.count();
    }
}
