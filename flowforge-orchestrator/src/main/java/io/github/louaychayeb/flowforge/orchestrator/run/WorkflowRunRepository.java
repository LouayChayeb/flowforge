package io.github.louaychayeb.flowforge.orchestrator.run;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, UUID> {

    /**
     * Locks the run row. Taken after the step's row lock whenever an outcome is applied, so that outcomes for
     * different steps of the same (parallel) run are applied one after the other and every run-level decision —
     * what becomes eligible, whether the run is drained — sees the others' effects.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from WorkflowRun r where r.id = :id")
    Optional<WorkflowRun> findByIdForUpdate(UUID id);
}
