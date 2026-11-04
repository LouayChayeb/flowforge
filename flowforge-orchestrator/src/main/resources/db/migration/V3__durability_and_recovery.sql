-- Phase 3: transactional outbox, step leases and saga compensation.

-- Compensation: a step may name the step type that undoes it.
ALTER TABLE step_definition ADD COLUMN compensation_type VARCHAR(100);

-- Runs that fail after completing compensable steps compensate them before ending.
ALTER TABLE workflow_run DROP CONSTRAINT workflow_run_status_check;
ALTER TABLE workflow_run ADD CONSTRAINT ck_workflow_run_status
    CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED', 'COMPENSATING', 'COMPENSATED', 'COMPENSATION_FAILED'));
ALTER TABLE workflow_run DROP CONSTRAINT ck_workflow_run_completed_at;
ALTER TABLE workflow_run ADD CONSTRAINT ck_workflow_run_completed_at
    CHECK ((status IN ('RUNNING', 'COMPENSATING')) = (completed_at IS NULL));

ALTER TABLE step_execution
    ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'FORWARD' CHECK (kind IN ('FORWARD', 'COMPENSATION')),
    ADD COLUMN compensation_type VARCHAR(100),
    ADD COLUMN compensates_step_execution_id UUID REFERENCES step_execution (id),
    -- Set while RUNNING: if no result arrives by then, the attempt is timed out and retried.
    ADD COLUMN lease_expires_at TIMESTAMPTZ;

-- Steps left RUNNING before leases existed are expired immediately, so recovery picks them up.
UPDATE step_execution SET lease_expires_at = now() WHERE status = 'RUNNING';

ALTER TABLE step_execution ADD CONSTRAINT ck_step_execution_lease
    CHECK ((status = 'RUNNING') = (lease_expires_at IS NOT NULL));

-- Supports the recovery sweeper's "find expired leases" query.
CREATE INDEX idx_step_execution_lease ON step_execution (lease_expires_at) WHERE status = 'RUNNING';

ALTER TABLE step_attempt DROP CONSTRAINT step_attempt_status_check;
ALTER TABLE step_attempt ADD CONSTRAINT ck_step_attempt_status
    CHECK (status IN ('RUNNING', 'SUCCEEDED', 'RETRYABLE_FAILURE', 'PERMANENT_FAILURE', 'TIMED_OUT'));

-- Messages written in the same transaction as the state change that requires them,
-- and published to Kafka afterwards by the outbox publisher.
CREATE TABLE outbox_event (
    id               UUID          PRIMARY KEY,
    topic            VARCHAR(200)  NOT NULL,
    message_key      VARCHAR(200)  NOT NULL,
    payload          JSONB         NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL,
    published_at     TIMESTAMPTZ,
    publish_attempts INTEGER       NOT NULL CHECK (publish_attempts >= 0),
    last_error       VARCHAR(2000),
    lock_version     BIGINT        NOT NULL
);

CREATE INDEX idx_outbox_event_pending ON outbox_event (created_at) WHERE published_at IS NULL;
