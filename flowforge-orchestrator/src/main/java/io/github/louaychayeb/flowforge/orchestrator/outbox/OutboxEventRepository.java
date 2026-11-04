package io.github.louaychayeb.flowforge.orchestrator.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Locks up to {@code limit} unpublished events, oldest first. {@code SKIP LOCKED} lets several
     * publishers run concurrently without sending the same event twice.
     */
    @Query(value = """
            SELECT * FROM outbox_event
            WHERE published_at IS NULL
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockPending(int limit);

    List<OutboxEvent> findByMessageKeyAndPublishedAtIsNull(String messageKey);
}
