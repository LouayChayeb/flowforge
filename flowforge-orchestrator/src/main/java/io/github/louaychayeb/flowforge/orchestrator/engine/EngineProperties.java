package io.github.louaychayeb.flowforge.orchestrator.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
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
 * @param wakeup         run the dispatch and outbox loops as soon as work appears instead of only on their
 *                       ticks ({@link EngineWakeup}); the ticks remain as a safety net
 * @param wakeupLinger   how long a wake-up waits before running, so that work appearing close together is
 *                       handled in one batch (like Kafka's {@code linger.ms}); zero runs every wake-up at once
 */
@ConfigurationProperties("flowforge.engine")
public record EngineProperties(
        @DefaultValue("10") int batchSize,
        @DefaultValue("10s") Duration publishTimeout,
        @DefaultValue("60s") Duration leaseDuration,
        @DefaultValue Retry retry,
        @DefaultValue("true") boolean wakeup,
        @DefaultValue("20ms") Duration wakeupLinger
) {

    @ConstructorBinding
    public EngineProperties {
    }

    public EngineProperties(int batchSize, Duration publishTimeout, Duration leaseDuration, Retry retry) {
        this(batchSize, publishTimeout, leaseDuration, retry, true, Duration.ofMillis(20));
    }

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
