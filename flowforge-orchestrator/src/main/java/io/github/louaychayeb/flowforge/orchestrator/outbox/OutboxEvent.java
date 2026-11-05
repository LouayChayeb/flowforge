package io.github.louaychayeb.flowforge.orchestrator.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A Kafka message that must be published because of a state change committed in the same transaction.
 *
 * <p>Writing the message to the database instead of sending it directly means a crash can no longer
 * leave the database saying "step dispatched" while no command exists: either both the state change and
 * this row commit, or neither does. The {@link OutboxPublisher} sends it afterwards — at least once.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    static final int MAX_ERROR_LENGTH = 2000;

    @Id
    private UUID id;

    private String topic;

    private String messageKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> payload;

    private Instant createdAt;

    private Instant publishedAt;

    private int publishAttempts;

    private String lastError;

    /** W3C traceparent of the step attempt that produced this event; {@code null} when tracing is off. */
    private String traceParent;

    @Version
    private Long lockVersion;

    protected OutboxEvent() {
    }

    public OutboxEvent(String topic, String messageKey, Map<String, Object> payload, Instant createdAt,
                       String traceParent) {
        this(topic, messageKey, payload, createdAt);
        this.traceParent = traceParent;
    }

    public OutboxEvent(String topic, String messageKey, Map<String, Object> payload, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.topic = topic;
        this.messageKey = messageKey;
        this.payload = new LinkedHashMap<>(payload);
        this.createdAt = createdAt;
    }

    void markPublished(Instant now) {
        publishAttempts++;
        publishedAt = now;
        lastError = null;
    }

    void recordFailure(String error) {
        publishAttempts++;
        lastError = error == null || error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    public UUID getId() {
        return id;
    }

    public String getTopic() {
        return topic;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public Map<String, Object> getPayload() {
        return Collections.unmodifiableMap(payload);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getPublishAttempts() {
        return publishAttempts;
    }

    public String getTraceParent() {
        return traceParent;
    }

    public String getLastError() {
        return lastError;
    }
}
