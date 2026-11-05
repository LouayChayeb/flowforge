-- Distributed tracing across the gaps where work waits in the database. A run keeps the W3C trace context
-- ("traceparent") it was started under, and each outbox row keeps the context of the step attempt that
-- produced it, so the publisher can send the command inside that trace. NULL when tracing is off.

ALTER TABLE workflow_run ADD COLUMN trace_parent VARCHAR(64);
ALTER TABLE outbox_event ADD COLUMN trace_parent VARCHAR(64);
