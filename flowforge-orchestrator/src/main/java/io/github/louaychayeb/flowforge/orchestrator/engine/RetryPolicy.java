package io.github.louaychayeb.flowforge.orchestrator.engine;

import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Computes how long to wait before retrying a step after a failed attempt.
 */
@Component
public class RetryPolicy {

    private final EngineProperties.Retry config;

    public RetryPolicy(EngineProperties properties) {
        this.config = properties.retry();
    }

    /**
     * @param failedAttempt the attempt number that just failed, starting at 1
     */
    public Duration backoffAfter(int failedAttempt) {
        double factor = Math.pow(config.multiplier(), Math.max(0, failedAttempt - 1));
        double millis = config.initialBackoff().toMillis() * factor;
        long maxMillis = config.maxBackoff().toMillis();
        return Duration.ofMillis(millis >= maxMillis ? maxMillis : (long) millis);
    }
}
