package io.github.louaychayeb.flowforge.orchestrator.run;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

    /**
     * Ids of up to {@code limit} running steps whose lease expired before {@code cutoff}. Not locked: each one
     * is re-checked under {@link #findByIdForUpdate} before it is touched.
     */
    @Query(value = """
            SELECT id FROM step_execution
            WHERE status = 'RUNNING'
              AND lease_expires_at < :cutoff
            ORDER BY lease_expires_at
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findExpiredLeaseIds(Instant cutoff, int limit);

    /**
     * Loads a step and locks its row until the transaction ends, waiting if another transaction holds it.
     * Recording a result and expiring a lease both lock the step first, so they apply one after the other:
     * whichever comes second sees the other's outcome and is rejected as stale.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StepExecution s where s.id = :id")
    Optional<StepExecution> findByIdForUpdate(UUID id);
}
