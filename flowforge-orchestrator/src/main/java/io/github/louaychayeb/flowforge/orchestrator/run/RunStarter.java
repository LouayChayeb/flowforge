package io.github.louaychayeb.flowforge.orchestrator.run;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Starts runs at most once per Idempotency-Key, races included.
 *
 * <p>Two requests with the same new key can both pass the "no run has this key yet" check; the unique index then lets
 * one insert and fails the other. That failure rolls back the loser's transaction, so the lookup of the winner runs
 * here, outside it, in a transaction of its own — which is why this is not a method of {@link WorkflowRunService}.
 */
@Component
public class RunStarter {

    private final WorkflowRunService runs;

    public RunStarter(WorkflowRunService runs) {
        this.runs = runs;
    }

    public WorkflowRunService.Started start(String workflowName, Map<String, Object> input, String idempotencyKey) {
        try {
            return runs.start(workflowName, input, idempotencyKey);
        } catch (DataIntegrityViolationException e) {
            if (idempotencyKey == null) {
                throw e;
            }
            // Lost the race: the other request's run is committed now.
            return runs.findByIdempotencyKey(workflowName, input, idempotencyKey).orElseThrow(() -> e);
        }
    }
}
