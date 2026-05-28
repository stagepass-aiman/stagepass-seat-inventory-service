package dev.stagepass.seatinventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Durable per-seat state. Source of truth for committed seat state.
 *
 * <p><strong>Hibernate ddl-auto=validate critical rules (Phase 3 build log §4):</strong>
 * <ul>
 *   <li>All String fields → VARCHAR(N) with explicit @Column(length=N)</li>
 *   <li>Instant → TIMESTAMPTZ (no @Column(columnDefinition) needed)</li>
 *   <li>BigDecimal price → columnDefinition = "numeric(19,2)" required</li>
 *   <li>Enum → @Enumerated(STRING), @Column(length=20)</li>
 *   <li>@Version Long → bigint column in migration (V1)</li>
 * </ul>
 *
 * <p><strong>@Version scope:</strong> This entity has @Version for Admin block/unblock
 * operations ONLY. The HoldSeats code path must NEVER trigger the version increment —
 * Redis Lua SETNX is the lock for holds. Applying optimistic locking to HoldSeats
 * would cause CAS retry storms under flash sale load (ADR-006 §3.2 rationale).
 */
@Entity
@Table(
    name = "seat_state",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_seat_state_event_seat",
                          columnNames = {"event_id", "seat_id"})
    }
)
public class SeatStateEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    // No FK — cross-service boundary. Validated at service layer.
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    // Non-fungible seat identifier. Comes from Venue Service layout.
    @Column(name = "seat_id", nullable = false, updatable = false)
    private UUID seatId;

    @Column(name = "section_id", nullable = false)
    private UUID sectionId;

    // Nullable for GA (general admission) events
    @Column(name = "row_label", length = 10)
    private String rowLabel;

    // Nullable for GA events
    @Column(name = "seat_number", length = 20)
    private String seatNumber;

    @Column(name = "category", nullable = false, length = 30)
    private String category = "STANDARD";

    // ADR-004: exact decimal. columnDefinition required for Hibernate ddl-auto=validate.
    // Never float or double (NFR-REL-010).
    @Column(name = "price", nullable = false, columnDefinition = "numeric(19,2)")
    private BigDecimal price;

    // ADR-004: currency coupled to amount. VARCHAR(3) — NOT CHAR(3).
    // CHAR maps to bpchar in PG; Hibernate validate rejects it.
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private SeatStateEnum state = SeatStateEnum.AVAILABLE;

    // Present when state IN (HELD, BOOKED). No FK — Booking is a different service.
    @Column(name = "booking_id")
    private UUID bookingId;

    // Present when state IN (HELD, BOOKED). Denormalised for notification routing.
    @Column(name = "customer_id")
    private UUID customerId;

    // Informational only. Redis is authoritative for hold timing (ADR-006 §3.1).
    @Column(name = "held_until")
    private Instant heldUntil;

    // Set atomically in CommitSeats SERIALIZABLE transaction.
    @Column(name = "booked_at")
    private Instant bookedAt;

    // No FK — audit must survive Admin account deletion.
    @Column(name = "blocked_by_user_id")
    private UUID blockedByUserId;

    @Column(name = "blocked_at")
    private Instant blockedAt;

    // @Version — ADMIN OPS ONLY. See class javadoc for why.
    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SeatStateEntity() {
        // JPA requires a no-arg constructor. Protected prevents direct instantiation.
    }

    // Static factory — enforces all required fields at construction time.
    public static SeatStateEntity create(
            final UUID id,
            final UUID eventId,
            final UUID seatId,
            final UUID sectionId,
            final String rowLabel,
            final String seatNumber,
            final String category,
            final BigDecimal price,
            final String currency) {
        final SeatStateEntity entity = new SeatStateEntity();
        entity.id = id;
        entity.eventId = eventId;
        entity.seatId = seatId;
        entity.sectionId = sectionId;
        entity.rowLabel = rowLabel;
        entity.seatNumber = seatNumber;
        entity.category = category;
        entity.price = price;
        entity.currency = currency;
        entity.state = SeatStateEnum.AVAILABLE;
        entity.createdAt = Instant.now();
        entity.updatedAt = Instant.now();
        return entity;
    }

    // ── Getters ──────────────────────────────────────────────

    public UUID getId()             { return id; }
    public UUID getEventId()        { return eventId; }
    public UUID getSeatId()         { return seatId; }
    public UUID getSectionId()      { return sectionId; }
    public String getRowLabel()     { return rowLabel; }
    public String getSeatNumber()   { return seatNumber; }
    public String getCategory()     { return category; }
    public BigDecimal getPrice()    { return price; }
    public String getCurrency()     { return currency; }
    public SeatStateEnum getState() { return state; }
    public UUID getBookingId()      { return bookingId; }
    public UUID getCustomerId()     { return customerId; }
    public Instant getHeldUntil()   { return heldUntil; }
    public Instant getBookedAt()    { return bookedAt; }
    public UUID getBlockedByUserId(){ return blockedByUserId; }
    public Instant getBlockedAt()   { return blockedAt; }
    public Long getVersion()        { return version; }
    public Instant getCreatedAt()   { return createdAt; }
    public Instant getUpdatedAt()   { return updatedAt; }

    // ── Mutators (only called from service layer) ─────────────

    public void markHeld(final UUID bookingId, final UUID customerId, final Instant heldUntil) {
        this.state = SeatStateEnum.HELD;
        this.bookingId = bookingId;
        this.customerId = customerId;
        this.heldUntil = heldUntil;
        this.updatedAt = Instant.now();
    }

    public void markBooked(final Instant bookedAt) {
        this.state = SeatStateEnum.BOOKED;
        this.heldUntil = null;
        this.bookedAt = bookedAt;
        this.updatedAt = Instant.now();
    }

    public void markAvailable() {
        this.state = SeatStateEnum.AVAILABLE;
        this.bookingId = null;
        this.customerId = null;
        this.heldUntil = null;
        this.updatedAt = Instant.now();
    }

    public void markBlocked(final UUID adminUserId) {
        this.state = SeatStateEnum.BLOCKED;
        this.blockedByUserId = adminUserId;
        this.blockedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void markUnblocked() {
        this.state = SeatStateEnum.AVAILABLE;
        this.blockedByUserId = null;
        this.blockedAt = null;
        this.updatedAt = Instant.now();
    }
}
