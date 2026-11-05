-- Steps can depend on any earlier steps instead of only the previous one, so independent steps run in parallel.
-- depends_on holds the positions of the forward steps a step waits for, as a JSON array.

ALTER TABLE step_definition ADD COLUMN depends_on JSONB;
-- Everything defined before this migration was sequential: each step waited for the one before it.
UPDATE step_definition
SET depends_on = CASE WHEN position = 0 THEN '[]'::jsonb ELSE jsonb_build_array(position - 1) END;
ALTER TABLE step_definition ALTER COLUMN depends_on SET NOT NULL;
ALTER TABLE step_definition ADD CONSTRAINT ck_step_definition_depends_on CHECK (jsonb_typeof(depends_on) = 'array');

ALTER TABLE step_execution ADD COLUMN depends_on JSONB;
-- Compensation steps form their own sequential chain and depend on no forward step.
UPDATE step_execution
SET depends_on = CASE WHEN kind = 'COMPENSATION' OR position = 0 THEN '[]'::jsonb
                      ELSE jsonb_build_array(position - 1) END;
ALTER TABLE step_execution ALTER COLUMN depends_on SET NOT NULL;
ALTER TABLE step_execution ADD CONSTRAINT ck_step_execution_depends_on CHECK (jsonb_typeof(depends_on) = 'array');
