package dev.stagepass.seatinventory.entity;

/**
 * Seat state machine states (ADR-006 §3.9).
 *
 * <p>State storage mapping:
 * <pre>
 *   AVAILABLE  — Redis key absent;  PG state = AVAILABLE
 *   HELD       — Redis key present, value = {bookingId}, TTL = 600s; PG state = HELD
 *   BOOKED     — Redis key present, value = "BOOKED", no TTL; PG state = BOOKED
 *   BLOCKED    — Redis key absent (admin only, low-frequency); PG state = BLOCKED
 * </pre>
 *
 * <p>Valid transitions (ADR-006 §3.9):
 * <pre>
 *   AVAILABLE → HELD       HoldSeats Lua SETNX (normal gRPC or flash sale Kafka consumer)
 *   HELD → AVAILABLE       ReleaseSeats (saga compensation) OR Redis TTL expiry (NFR-REL-004)
 *   HELD → BOOKED          CommitSeats (payment confirmed, SERIALIZABLE txn, NFR-REL-008)
 *   AVAILABLE → BLOCKED    Admin command via seat.commands Kafka topic
 *   BLOCKED → AVAILABLE    Admin command via seat.commands Kafka topic
 *   BOOKED → AVAILABLE     Post-event or booking cancellation saga
 * </pre>
 */
public enum SeatStateEnum {
    AVAILABLE,
    HELD,
    BOOKED,
    BLOCKED
}
