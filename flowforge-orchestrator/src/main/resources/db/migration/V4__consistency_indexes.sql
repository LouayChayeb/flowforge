-- Lets the consistency checker find the step command for an attempt without scanning the outbox.
CREATE INDEX idx_outbox_event_step_command ON outbox_event ((payload ->> 'stepExecutionId'));

-- Lets the consistency checker find the compensation of a step.
CREATE INDEX idx_step_execution_compensates ON step_execution (compensates_step_execution_id)
    WHERE compensates_step_execution_id IS NOT NULL;
