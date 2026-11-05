package io.github.louaychayeb.flowforge.orchestrator.definition;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, UUID> {

    Optional<WorkflowDefinition> findFirstByNameOrderByVersionDesc(String name);

    Optional<WorkflowDefinition> findByNameAndVersion(String name, int version);

    @Query("""
            select d from WorkflowDefinition d
            where d.version = (select max(x.version) from WorkflowDefinition x where x.name = d.name)
            order by d.name
            """)
    List<WorkflowDefinition> findLatestOfEach();

    @Query("select coalesce(max(d.version), 0) from WorkflowDefinition d where d.name = :name")
    int findLatestVersion(String name);
}
