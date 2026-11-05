package io.github.louaychayeb.flowforge.orchestrator.engine;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

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
    private final MeterRegistry registry;

    public EngineMetrics(MeterRegistry registry) {
        this.registry = registry;
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

    /** End-to-end duration of a finished run, by workflow and final status; a histogram, for percentiles. */
    public void runFinished(String workflow, String status, Duration duration) {
        Timer.builder("flowforge.run.duration").description("Time from run start to its terminal status")
                .tag("workflow", workflow).tag("status", status).publishPercentileHistogram()
                .register(registry).record(duration);
    }

    /** How long one attempt took from claim to outcome, by step type and outcome. */
    public void attemptFinished(String stepType, String outcome, Duration duration) {
        Timer.builder("flowforge.step.attempt.duration").description("Time from claiming an attempt to its outcome")
                .tag("type", stepType).tag("outcome", outcome).publishPercentileHistogram()
                .register(registry).record(duration);
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

    /** Every counter of this instance, for the dashboard. Counters start at zero when the process starts. */
    public Snapshot snapshot() {
        return new Snapshot((long) stepsClaimed.count(), (long) resultsApplied.count(), (long) resultsStale.count(),
                (long) leasesExpired.count(), (long) outboxPublished.count(), (long) outboxFailures.count());
    }

    public record Snapshot(long stepsClaimed, long resultsApplied, long resultsStale, long leasesExpired,
                           long outboxPublished, long outboxFailures) {
    }

    public double staleResults() {
        return resultsStale.count();
    }

    public double expiredLeases() {
        return leasesExpired.count();
    }
}
