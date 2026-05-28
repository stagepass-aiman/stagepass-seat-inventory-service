package dev.stagepass.seatinventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * Hold header per booking. Expiry tracking and reconciliation anchor.
 *
 * <p>The UNIQUE constraint on booking_id is the idempotency enforcement:
 * HoldSeats uses INSERT ... ON CONFLICT (booking_id) DO NOTHING.
 * If the conflict fires, this is a retry — the hold already exists.
 * expires_at is NEVER reset on retry (ADR-006 §3.8).
 */
@Entity
@Table(
    name = "seat_holds",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_seat_holds_booking", columnNames = {"booking_id"})
    }
)
public class SeatHoldEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    // Idempotency anchor. UNIQUE. One hold per booking saga invocation.
    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    // HELD → BOOKED (CommitSeats) or HELD → RELEASED (ReleaseSeats) or HELD → EXPIRED
    @Column(name = "status", nullable = false, length = 20)
    private String status = "HELD";

    // GRPC (normal path) or FLASH_SALE (Kafka consumer path)
    @Column(name = "hold_source", nullable = false, length = 20)
    private String holdSource = "GRPC";

    // Reconciliation query: WHERE expires_at < NOW() AND status = 'HELD'
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    // Denormalised: avoids COUNT(*) when checking hold size in hot path
    @Column(name = "seat_count", nullable = false)
    private int seatCount;

    @Column(name = "committed_at")
    private Instant committedAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SeatHoldEntity() {}

    public static SeatHoldEntity create(
            final UUID id,
            final UUID bookingId,
            final UUID eventId,
            final UUID customerId,
            final String holdSource,
            final Instant expiresAt,
            final int seatCount) {
        final SeatHoldEntity e = new SeatHoldEntity();
        e.id = id;
        e.bookingId = bookingId;
        e.eventId = eventId;
        e.customerId = customerId;
        e.status = "HELD";
        e.holdSource = holdSource;
        e.expiresAt = expiresAt;
        e.seatCount = seatCount;
        e.createdAt = Instant.now();
        e.updatedAt = Instant.now();
        return e;
    }

    public UUID getId()          { return id; }
    public UUID getBookingId()   { return bookingId; }
    public UUID getEventId()     { return eventId; }
    public UUID getCustomerId()  { return customerId; }
    public String getStatus()    { return status; }
    public String getHoldSource(){ return holdSource; }
    public Instant getExpiresAt(){ return expiresAt; }
    public int getSeatCount()    { return seatCount; }
    public Instant getCommittedAt() { return committedAt; }
    public Instant getReleasedAt()  { return releasedAt; }
    public Instant getCreatedAt()   { return createdAt; }
    public Instant getUpdatedAt()   { return updatedAt; }

    public void markBooked() {
        this.status = "BOOKED";
        this.committedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void markReleased() {
        this.status = "RELEASED";
        this.releasedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void markExpired() {
        this.status = "EXPIRED";
        this.releasedAt = Instant.now();
        this.updatedAt = Instant.now();
    }
}
