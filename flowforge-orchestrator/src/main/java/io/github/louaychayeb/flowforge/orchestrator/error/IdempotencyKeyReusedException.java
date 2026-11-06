package io.github.louaychayeb.flowforge.orchestrator.error;

import java.util.Map;
import java.util.UUID;

/**
 * An Idempotency-Key that already started a run, sent again with a different workflow or input. Replaying the first
 * run would hand the client the wrong result, and starting a second would defeat the key, so the request is refused.
 */
public class IdempotencyKeyReusedException extends FlowForgeException {

    public IdempotencyKeyReusedException(String key, UUID existingRunId) {
        super(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                "Idempotency key " + key + " was first used to start run " + existingRunId
                        + " with a different workflow or input",
                Map.of("idempotencyKey", key, "runId", existingRunId));
    }
}
