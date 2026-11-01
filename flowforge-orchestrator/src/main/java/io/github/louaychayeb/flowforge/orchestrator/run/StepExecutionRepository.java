package io.github.louaychayeb.flowforge.orchestrator.run;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface StepExecutionRepository extends JpaRepository<StepExecution, UUID> {

    /**
     * Locks up to {@code limit} steps that are ready to be claimed. {@code SKIP LOCKED} lets several
     * dispatchers poll concurrently: rows another transaction is claiming are skipped, not waited on,
     * so no step is ever claimed twice.
     */
    @Query(value = """
            SELECT * FROM step_execution
            WHERE status IN ('PENDING', 'RETRY_WAIT')
              AND next_attempt_at <= :now
            ORDER BY next_attempt_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<StepExecution> lockClaimable(Instant now, int limit);
}
