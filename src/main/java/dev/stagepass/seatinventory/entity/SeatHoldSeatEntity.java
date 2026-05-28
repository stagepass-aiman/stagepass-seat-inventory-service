package dev.stagepass.seatinventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * Junction table: one row per seat per hold.
 * Enables multi-seat all-or-nothing atomicity tracking (ADR-006 §3.8).
 */
@Entity
@Table(
    name = "seat_hold_seats",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_seat_hold_seats_hold_seat",
                          columnNames = {"hold_id", "seat_id"})
    }
)
public class SeatHoldSeatEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hold_id", nullable = false)
    private SeatHoldEntity hold;

    // References seat_state.seat_id (domain key). Not seat_state.id.
    @Column(name = "seat_id", nullable = false)
    private UUID seatId;

    // Denormalised — avoids join to hold for event-scoped queries
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    // HELD → COMMITTED (CommitSeats) or HELD → RELEASED (ReleaseSeats)
    @Column(name = "status", nullable = false, length = 20)
    private String status = "HELD";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SeatHoldSeatEntity() {}

    public static SeatHoldSeatEntity create(
            final UUID id,
            final SeatHoldEntity hold,
            final UUID seatId,
            final UUID eventId) {
        final SeatHoldSeatEntity e = new SeatHoldSeatEntity();
        e.id = id;
        e.hold = hold;
        e.seatId = seatId;
        e.eventId = eventId;
        e.status = "HELD";
        e.createdAt = Instant.now();
        return e;
    }

    public UUID getId()              { return id; }
    public SeatHoldEntity getHold()  { return hold; }
    public UUID getSeatId()          { return seatId; }
    public UUID getEventId()         { return eventId; }
    public String getStatus()        { return status; }
    public Instant getCreatedAt()    { return createdAt; }

    public void markCommitted() { this.status = "COMMITTED"; }
    public void markReleased()  { this.status = "RELEASED"; }
}
