package io.github.louaychayeb.flowforge.orchestrator.operator;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OperatorActionRepository extends JpaRepository<OperatorAction, UUID> {

    List<OperatorAction> findByWorkflowRunIdOrderByPerformedAt(UUID workflowRunId);
}
