package io.github.louaychayeb.flowforge.orchestrator.engine;

import java.time.Instant;

/**
 * Spring events published inside the engine's transactions. They are hints, not commands: {@link EngineWakeup}
 * reacts to them only after the transaction commits, and the periodic loops do the same work anyway if a
 * hint is lost (for example because the process died right after the commit).
 */
public final class EngineEvents {

    private EngineEvents() {
    }

    /** Some step may be claimable at {@code at} (now, or when a retry backoff ends). */
    public record StepsMayBeReady(Instant at) {
    }

    /** Step commands were written to the outbox and are waiting to be published. */
    public record CommandsWritten(int count) {
    }
}
