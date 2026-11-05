package io.github.louaychayeb.flowforge.orchestrator.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Base of every exception the orchestrator raises on purpose. Each carries an {@link ErrorCode}, so the API layer
 * can turn any of them into the right problem response without knowing the concrete class, and optional structured
 * details (ids, states) that end up as properties of that response instead of being parsed out of the message.
 *
 * <p>Unchecked, like most Spring code: these signal broken preconditions that callers cannot sensibly recover from
 * locally, and they roll back the surrounding transaction.
 */
public abstract class FlowForgeException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> details;

    protected FlowForgeException(ErrorCode code, String message) {
        this(code, message, Map.of(), null);
    }

    protected FlowForgeException(ErrorCode code, String message, Map<String, ?> details) {
        this(code, message, details, null);
    }

    protected FlowForgeException(ErrorCode code, String message, Map<String, ?> details, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public ErrorCode code() {
        return code;
    }

    /** Structured context, e.g. {@code runId} and {@code status}; safe to show to API clients. */
    public Map<String, Object> details() {
        return details;
    }
}
