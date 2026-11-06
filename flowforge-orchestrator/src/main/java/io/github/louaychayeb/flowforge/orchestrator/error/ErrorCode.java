package io.github.louaychayeb.flowforge.orchestrator.error;

import org.springframework.http.HttpStatus;

/**
 * Every error the API can return, with the HTTP status it maps to. The code is part of the API contract: clients
 * should branch on it, not on the human-readable message, which may change.
 */
public enum ErrorCode {

    WORKFLOW_NOT_FOUND(HttpStatus.NOT_FOUND, "Workflow not found"),
    RUN_NOT_FOUND(HttpStatus.NOT_FOUND, "Run not found"),
    STEP_NOT_FOUND(HttpStatus.NOT_FOUND, "Step not found"),
    INVALID_WORKFLOW_DEFINITION(HttpStatus.BAD_REQUEST, "Invalid workflow definition"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Malformed request"),
    /** An operation the current state does not allow, e.g. cancelling a run that already finished. */
    OPERATION_NOT_ALLOWED(HttpStatus.CONFLICT, "Operation not allowed in the current state"),
    /** Another transaction changed the same data first; repeating the request is safe. */
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "Concurrent modification"),
    /** A state machine refused a transition. Inside the engine this means a bug or a stale message. */
    ILLEGAL_STATE_TRANSITION(HttpStatus.CONFLICT, "Illegal state transition"),
    /** An outcome for an attempt that is no longer active; never reaches the API, handled by the listener. */
    STALE_ATTEMPT(HttpStatus.CONFLICT, "Stale attempt"),
    /** An Idempotency-Key already used for a different request: a client bug, never safe to replay. */
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency key reused for a different request"),
    /** No credentials, or wrong ones, for an endpoint that needs them. */
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    /** Authenticated, but without the role the endpoint needs. */
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access denied"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    /** The problem {@code type} URI, e.g. {@code urn:flowforge:problem:run-not-found}. */
    public String typeUri() {
        return "urn:flowforge:problem:" + name().toLowerCase().replace('_', '-');
    }
}
