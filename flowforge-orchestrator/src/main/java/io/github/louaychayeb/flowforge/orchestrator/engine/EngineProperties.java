package io.github.louaychayeb.flowforge.orchestrator.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Tuning knobs for the execution engine, bound from {@code flowforge.engine.*}.
 *
 * @param batchSize      maximum number of steps a dispatcher claims per poll
 * @param publishTimeout how long to wait for Kafka to acknowledge a step command
 * @param retry          backoff between attempts of a failing step
 */
@ConfigurationProperties("flowforge.engine")
public record EngineProperties(
        @DefaultValue("10") int batchSize,
        @DefaultValue("10s") Duration publishTimeout,
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
