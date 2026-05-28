package dev.stagepass.seatinventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Transactional Outbox for Kafka publishing (NFR-REL-005).
 *
 * <p>outbox row + seat_state UPDATE are in the SAME PostgreSQL transaction.
 * If the transaction commits, the Kafka event will eventually be published.
 * If it rolls back, neither the state change nor the event persists.
 *
 * <p>outbox.id is used as the Kafka message messageId for consumer deduplication
 * (NFR-REL-002). Maps to messageId in seat_async.yaml.
 *
 * <p>Publisher pattern: @Scheduled SELECT ... FOR UPDATE SKIP LOCKED ORDER BY created_at ASC.
 * SKIP LOCKED prevents two publisher threads from processing the same row concurrently.
 */
@Entity
@Table(name = "outbox")
public class OutboxEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    // Matches seat_async.yaml event types
    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType = "SEAT_STATE";

    // seat_state.seat_id
    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    // Kafka partition key: event_id (ADR-003 §3.4.2)
    @Column(name = "partition_key", nullable = false)
    private UUID partitionKey;

    // Full JSON payload matching seat_async.yaml. columnDefinition required for validate.
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "PENDING";

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEntity() {}

    public static OutboxEntity create(
            final UUID id,
            final String eventType,
            final UUID aggregateId,
            final UUID partitionKey,
            final String payload) {
        final OutboxEntity e = new OutboxEntity();
        e.id = id;
        e.eventType = eventType;
        e.aggregateId = aggregateId;
        e.partitionKey = partitionKey;
        e.payload = payload;
        e.status = "PENDING";
        e.attempts = 0;
        e.createdAt = Instant.now();
        return e;
    }

    public UUID getId()             { return id; }
    public String getEventType()    { return eventType; }
    public String getAggregateType(){ return aggregateType; }
    public UUID getAggregateId()    { return aggregateId; }
    public UUID getPartitionKey()   { return partitionKey; }
    public String getPayload()      { return payload; }
    public String getStatus()       { return status; }
    public int getAttempts()        { return attempts; }
    public Instant getCreatedAt()   { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }

    public void markPublished() {
        this.status = "PUBLISHED";
        this.publishedAt = Instant.now();
    }

    public void incrementAttempts() {
        this.attempts++;
    }

    public void markFailed() {
        this.status = "FAILED";
    }
}
