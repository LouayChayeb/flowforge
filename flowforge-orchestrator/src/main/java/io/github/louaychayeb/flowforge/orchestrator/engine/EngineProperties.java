package io.github.louaychayeb.flowforge.orchestrator.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Tuning knobs for the execution engine, bound from {@code flowforge.engine.*}.
 *
 * @param batchSize      maximum number of steps or outbox events handled per poll
 * @param publishTimeout how long to wait for Kafka to acknowledge an outbox event
 * @param leaseDuration  how long a claimed attempt may run before it is timed out and retried;
 *                       must exceed the slowest step plus queueing time
 * @param retry          backoff between attempts of a failing step
 */
@ConfigurationProperties("flowforge.engine")
public record EngineProperties(
        @DefaultValue("10") int batchSize,
        @DefaultValue("10s") Duration publishTimeout,
        @DefaultValue("60s") Duration leaseDuration,
        @DefaultValue Retry retry
) {

    /**
     * Exponential backoff: {@code initialBackoff * multiplier^(attempt - 1)}, capped at {@code maxBackoff}.
     */
    public record Retry(
            @DefaultValue("1s") Duration initialBackoff,
            @DefaultValue("1m") Duration maxBackoff,
            @DefaultValue("2.0") double multiplier
    ) {
    }
}
