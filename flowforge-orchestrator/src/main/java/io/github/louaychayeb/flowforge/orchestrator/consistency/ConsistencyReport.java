package io.github.louaychayeb.flowforge.orchestrator.consistency;

import java.time.Instant;
import java.util.List;

/**
 * Result of a {@link ConsistencyChecker} run.
 *
 * @param checkedAt         when the check ran
 * @param workflow          the workflow name the check was restricted to, or {@code null} for all runs
 * @param invariantsChecked how many invariants were evaluated
 * @param consistent        {@code true} if every invariant holds
 * @param violations        the invariants that do not hold
 */
public record ConsistencyReport(
        Instant checkedAt,
        String workflow,
        int invariantsChecked,
        boolean consistent,
        List<Violation> violations
) {

    /**
     * @param invariant   short name of the broken invariant
     * @param description what the invariant requires
     * @param count       how many rows break it
     * @param sample      ids of up to 20 offending rows
     */
    public record Violation(String invariant, String description, int count, List<String> sample) {
    }
}
