package dev.stagepass.seatinventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable audit trail. NEVER updated or deleted in production.
 *
 * <p>Uses BIGSERIAL PK for append-only ordering (not UUID).
 * This means @GeneratedValue(IDENTITY) — the DB assigns the ID, not the application.
 * Rationale: BIGSERIAL guarantees insertion order without a secondary ORDER BY;
 * more space-efficient (8 bytes vs 16 bytes) for high-volume tables (Phase 4 ER §3.4).
 *
 * <p>payload_mac: HMAC-SHA256 of key fields detects post-insertion tampering
 * (STRIDE THR-SEAT-02). Secret from Vault.
 *
 * <p>No FK columns: audit record must survive seat/booking deletion.
 */
@Entity
@Table(name = "seat_state_transitions")
public class SeatStateTransitionEntity {

    // BIGSERIAL — DB-assigned. Do not set in application code.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    // No FK — audit survives seat deletion
    @Column(name = "seat_id", nullable = false, updatable = false)
    private UUID seatId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "booking_id", updatable = false)
    private UUID bookingId;

    @Column(name = "customer_id", updatable = false)
    private UUID customerId;

    @Column(name = "from_state", nullable = false, length = 20, updatable = false)
    private String fromState;

    @Column(name = "to_state", nullable = false, length = 20, updatable = false)
    private String toState;

    @Column(name = "transition_reason", nullable = false, length = 50, updatable = false)
    private String transitionReason;

    @Column(name = "performed_by_service", nullable = false, length = 50, updatable = false)
    private String performedByService;

    @Column(name = "performed_by_user_id", updatable = false)
    private UUID performedByUserId;

    // HMAC-SHA256(seat_id || booking_id || from_state || to_state || occurred_at)
    // Must be verified on demand to detect tampering (STRIDE THR-SEAT-02).
    @Column(name = "payload_mac", nullable = false, length = 64, updatable = false)
    private String payloadMac;

    // Immutable. Set once on INSERT.
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected SeatStateTransitionEntity() {}

    public static SeatStateTransitionEntity create(
            final UUID seatId,
            final UUID eventId,
            final UUID bookingId,
            final UUID customerId,
            final String fromState,
            final String toState,
            final String transitionReason,
            final String performedByService,
            final UUID performedByUserId,
            final String payloadMac) {
        final SeatStateTransitionEntity e = new SeatStateTransitionEntity();
        e.seatId = seatId;
        e.eventId = eventId;
        e.bookingId = bookingId;
        e.customerId = customerId;
        e.fromState = fromState;
        e.toState = toState;
        e.transitionReason = transitionReason;
        e.performedByService = performedByService;
        e.performedByUserId = performedByUserId;
        e.payloadMac = payloadMac;
        e.occurredAt = Instant.now();
        return e;
    }

    public Long getId()                  { return id; }
    public UUID getSeatId()              { return seatId; }
    public UUID getEventId()             { return eventId; }
    public UUID getBookingId()           { return bookingId; }
    public UUID getCustomerId()          { return customerId; }
    public String getFromState()         { return fromState; }
    public String getToState()           { return toState; }
    public String getTransitionReason()  { return transitionReason; }
    public String getPerformedByService(){ return performedByService; }
    public UUID getPerformedByUserId()   { return performedByUserId; }
    public String getPayloadMac()        { return payloadMac; }
    public Instant getOccurredAt()       { return occurredAt; }
}
