package io.github.louaychayeb.flowforge.orchestrator.definition;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, UUID> {

    Optional<WorkflowDefinition> findFirstByNameOrderByVersionDesc(String name);

    Optional<WorkflowDefinition> findByNameAndVersion(String name, int version);

    @Query("select coalesce(max(d.version), 0) from WorkflowDefinition d where d.name = :name")
    int findLatestVersion(String name);
}
