-- The dashboard lists the most recent runs; without this index that is a sort of the whole table.
CREATE INDEX idx_workflow_run_created_at ON workflow_run (created_at DESC);
