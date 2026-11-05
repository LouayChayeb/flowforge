package io.github.louaychayeb.flowforge.orchestrator.insight;

import io.github.louaychayeb.flowforge.orchestrator.engine.EngineProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The configuration this orchestrator instance is actually running with, so the dashboard can explain
 * timings (why a step waited, when a lease will expire) with real numbers instead of documented defaults.
 */
@Component
public class EngineSettings {

    private final View view;

    public EngineSettings(EngineProperties properties,
                          @Value("${HOSTNAME:${COMPUTERNAME:local}}-${server.port:8080}") String instance,
                          @Value("${flowforge.engine.scheduler.enabled:true}") boolean schedulerEnabled,
                          @Value("${flowforge.engine.scheduler.dispatch-interval-ms:500}") long dispatchIntervalMs,
                          @Value("${flowforge.engine.scheduler.outbox-interval-ms:200}") long outboxIntervalMs,
                          @Value("${flowforge.engine.scheduler.recovery-interval-ms:5000}") long recoveryIntervalMs,
                          @Value("${flowforge.kafka.partitions:3}") int partitions,
                          @Value("${spring.kafka.listener.concurrency:1}") int listenerConcurrency) {
        this.view = new View(instance, schedulerEnabled, dispatchIntervalMs, outboxIntervalMs, recoveryIntervalMs,
                properties.batchSize(), properties.leaseDuration(), properties.publishTimeout(),
                properties.retry().initialBackoff(), properties.retry().maxBackoff(), properties.retry().multiplier(),
                partitions, listenerConcurrency);
    }

    public record View(String instance, boolean schedulerEnabled, long dispatchIntervalMs, long outboxIntervalMs,
                       long recoveryIntervalMs, int batchSize, Duration leaseDuration, Duration publishTimeout,
                       Duration initialBackoff, Duration maxBackoff, double backoffMultiplier, int partitions,
                       int resultListenerThreads) {
    }

    public View view() {
        return view;
    }
}
