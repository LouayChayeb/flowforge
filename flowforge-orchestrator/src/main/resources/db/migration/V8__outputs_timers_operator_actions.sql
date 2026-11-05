-- Step outputs, durable timers and operator actions.

-- What a step produced for the steps after it (e.g. a charge id); set when the step completes.
ALTER TABLE step_execution ADD COLUMN output JSONB;
ALTER TABLE step_execution ADD CONSTRAINT ck_step_execution_output
    CHECK (output IS NULL OR (status = 'COMPLETED' AND jsonb_typeof(output) = 'object'));

-- Durable timers: a WAIT step becomes claimable wait_millis after its dependencies complete, and the
-- orchestrator completes it itself when it is claimed. Because the due time is next_attempt_at, a timer
-- survives restarts like any other scheduled step.
ALTER TABLE step_definition ADD COLUMN wait_millis BIGINT;
ALTER TABLE step_definition ADD CONSTRAINT ck_step_definition_wait
    CHECK ((step_type = 'WAIT') = (wait_millis IS NOT NULL) AND (wait_millis IS NULL OR wait_millis > 0));
ALTER TABLE step_execution ADD COLUMN wait_millis BIGINT;
ALTER TABLE step_execution ADD CONSTRAINT ck_step_execution_wait CHECK (wait_millis IS NULL OR wait_millis > 0);

-- Cancellation: requested by an operator, then carried out like a failure (drain, compensate) ending CANCELLED.
ALTER TABLE workflow_run ADD COLUMN cancel_requested_at TIMESTAMPTZ;
ALTER TABLE workflow_run DROP CONSTRAINT ck_workflow_run_status;
ALTER TABLE workflow_run ADD CONSTRAINT ck_workflow_run_status CHECK (status IN
    ('RUNNING', 'COMPLETED', 'FAILED', 'COMPENSATING', 'COMPENSATED', 'COMPENSATION_FAILED', 'CANCELLED'));
ALTER TABLE workflow_run ADD CONSTRAINT ck_workflow_run_cancelled
    CHECK (status <> 'CANCELLED' OR cancel_requested_at IS NOT NULL);

-- Audit log of every manual intervention: who did what to which run, when and why.
CREATE TABLE operator_action (
    id              UUID         PRIMARY KEY,
    workflow_run_id UUID         NOT NULL REFERENCES workflow_run (id),
    action          VARCHAR(40)  NOT NULL CHECK (action IN ('CANCEL', 'RETRY_COMPENSATION')),
    actor           VARCHAR(100) NOT NULL,
    reason          VARCHAR(500),
    run_status      VARCHAR(20)  NOT NULL,
    performed_at    TIMESTAMPTZ  NOT NULL,
    lock_version    BIGINT       NOT NULL
);

CREATE INDEX idx_operator_action_run ON operator_action (workflow_run_id, performed_at);
