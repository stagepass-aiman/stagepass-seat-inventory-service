package dev.stagepass.seatinventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * gRPC call idempotency. booking_id is the saga's natural anchor.
 * Stored in PostgreSQL (not Redis) because the gRPC response payload
 * must survive Redis restarts (Phase 4 ER diagram §3.6).
 *
 * <p>UNIQUE(idempotency_key, operation) allows one record per operation
 * per booking (HOLD_SEATS, COMMIT_SEATS, RELEASE_SEATS are independent).
 */
@Entity
@Table(
    name = "idempotency_keys",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_idempotency_key_op",
                          columnNames = {"idempotency_key", "operation"})
    }
)
public class IdempotencyKeyEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    // booking_id as string. VARCHAR(36) matches UUID string format.
    @Column(name = "idempotency_key", nullable = false, length = 36, updatable = false)
    private String idempotencyKey;

    @Column(name = "operation", nullable = false, length = 50, updatable = false)
    private String operation;

    // Fast-path status check without deserialising full response payload
    @Column(name = "result", nullable = false, length = 20, updatable = false)
    private String result;

    // Serialised gRPC response. columnDefinition required for Hibernate validate.
    @Column(name = "response_payload", nullable = false, columnDefinition = "jsonb",
            updatable = false)
    private String responsePayload;

    // 24-hour TTL. Cleanup scheduler purges expired rows.
    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IdempotencyKeyEntity() {}

    public static IdempotencyKeyEntity create(
            final UUID id,
            final String idempotencyKey,
            final String operation,
            final String result,
            final String responsePayload,
            final Instant expiresAt) {
        final IdempotencyKeyEntity e = new IdempotencyKeyEntity();
        e.id = id;
        e.idempotencyKey = idempotencyKey;
        e.operation = operation;
        e.result = result;
        e.responsePayload = responsePayload;
        e.expiresAt = expiresAt;
        e.createdAt = Instant.now();
        return e;
    }

    public UUID getId()               { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getOperation()      { return operation; }
    public String getResult()         { return result; }
    public String getResponsePayload(){ return responsePayload; }
    public Instant getExpiresAt()     { return expiresAt; }
    public Instant getCreatedAt()     { return createdAt; }
}
