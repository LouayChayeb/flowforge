-- Idempotent starts: a client that sends the same Idempotency-Key twice (a retry after a timeout, a double click,
-- a webhook delivered twice) gets the run it already started instead of a second one. The key is unique across
-- all runs; request_hash fingerprints the workflow and input it was first used with, so a key reused for a
-- different request is refused instead of silently returning the wrong run.
ALTER TABLE workflow_run ADD COLUMN idempotency_key VARCHAR(200);
ALTER TABLE workflow_run ADD COLUMN request_hash VARCHAR(64);
ALTER TABLE workflow_run ADD CONSTRAINT ck_workflow_run_idempotency
    CHECK ((idempotency_key IS NULL) = (request_hash IS NULL));
CREATE UNIQUE INDEX uq_workflow_run_idempotency_key ON workflow_run (idempotency_key) WHERE idempotency_key IS NOT NULL;

-- Signals: a SIGNAL step waits for an event from outside (a webhook, a manager's approval), delivered through the
-- API. Like a timer it is completed by the orchestrator, with no worker; its wait_millis is the deadline: a signal
-- that has not arrived by then fails the step, and the run compensates.
ALTER TABLE step_definition DROP CONSTRAINT ck_step_definition_wait;
ALTER TABLE step_definition ADD CONSTRAINT ck_step_definition_wait
    CHECK ((step_type IN ('WAIT', 'SIGNAL')) = (wait_millis IS NOT NULL) AND (wait_millis IS NULL OR wait_millis > 0));

-- Who delivered a signal is audited like any other manual intervention.
ALTER TABLE operator_action DROP CONSTRAINT operator_action_action_check;
ALTER TABLE operator_action ADD CONSTRAINT operator_action_action_check
    CHECK (action IN ('CANCEL', 'RETRY_COMPENSATION', 'SIGNAL'));
