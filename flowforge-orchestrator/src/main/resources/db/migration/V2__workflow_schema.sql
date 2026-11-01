-- Core workflow schema: definitions, runs, step executions and attempt history.

CREATE TABLE workflow_definition (
    id           UUID         PRIMARY KEY,
    name         VARCHAR(100) NOT NULL,
    version      INTEGER      NOT NULL CHECK (version >= 1),
    created_at   TIMESTAMPTZ  NOT NULL,
    lock_version BIGINT       NOT NULL,
    CONSTRAINT uq_workflow_definition_name_version UNIQUE (name, version)
);

CREATE TABLE step_definition (
    id                     UUID         PRIMARY KEY,
    workflow_definition_id UUID         NOT NULL REFERENCES workflow_definition (id),
    position               INTEGER      NOT NULL CHECK (position >= 0),
    name                   VARCHAR(100) NOT NULL,
    step_type              VARCHAR(100) NOT NULL,
    max_attempts           INTEGER      NOT NULL CHECK (max_attempts >= 1),
    lock_version           BIGINT       NOT NULL,
    CONSTRAINT uq_step_definition_position UNIQUE (workflow_definition_id, position),
    CONSTRAINT uq_step_definition_name UNIQUE (workflow_definition_id, name)
);

CREATE TABLE workflow_run (
    id                     UUID        PRIMARY KEY,
    workflow_definition_id UUID        NOT NULL REFERENCES workflow_definition (id),
    status                 VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    input                  JSONB       NOT NULL,
    current_position       INTEGER     NOT NULL CHECK (current_position >= 0),
    created_at             TIMESTAMPTZ NOT NULL,
    updated_at             TIMESTAMPTZ NOT NULL,
    completed_at           TIMESTAMPTZ,
    lock_version           BIGINT      NOT NULL,
    -- A run has a completion time exactly when it is in a terminal state.
    CONSTRAINT ck_workflow_run_completed_at CHECK ((status = 'RUNNING') = (completed_at IS NULL))
);

CREATE TABLE step_execution (
    id              UUID         PRIMARY KEY,
    workflow_run_id UUID         NOT NULL REFERENCES workflow_run (id),
    position        INTEGER      NOT NULL CHECK (position >= 0),
    name            VARCHAR(100) NOT NULL,
    step_type       VARCHAR(100) NOT NULL,
    status          VARCHAR(20)  NOT NULL
        CHECK (status IN ('PENDING', 'RUNNING', 'RETRY_WAIT', 'COMPLETED', 'FAILED')),
    attempt_count   INTEGER      NOT NULL CHECK (attempt_count >= 0),
    max_attempts    INTEGER      NOT NULL CHECK (max_attempts >= 1),
    -- When the step may next be claimed; NULL while the step is not eligible to run.
    next_attempt_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    lock_version    BIGINT       NOT NULL,
    CONSTRAINT uq_step_execution_position UNIQUE (workflow_run_id, position),
    CONSTRAINT ck_step_execution_attempts CHECK (attempt_count <= max_attempts)
);

-- Supports the dispatcher's "find claimable steps" query.
CREATE INDEX idx_step_execution_claimable
    ON step_execution (next_attempt_at)
    WHERE status IN ('PENDING', 'RETRY_WAIT') AND next_attempt_at IS NOT NULL;

CREATE TABLE step_attempt (
    id                UUID          PRIMARY KEY,
    step_execution_id UUID          NOT NULL REFERENCES step_execution (id),
    attempt_number    INTEGER       NOT NULL CHECK (attempt_number >= 1),
    status            VARCHAR(20)   NOT NULL
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'RETRYABLE_FAILURE', 'PERMANENT_FAILURE')),
    started_at        TIMESTAMPTZ   NOT NULL,
    finished_at       TIMESTAMPTZ,
    error_message     VARCHAR(2000),
    lock_version      BIGINT        NOT NULL,
    CONSTRAINT uq_step_attempt_number UNIQUE (step_execution_id, attempt_number)
);

CREATE INDEX idx_workflow_run_definition ON workflow_run (workflow_definition_id);
CREATE INDEX idx_step_execution_run ON step_execution (workflow_run_id);
