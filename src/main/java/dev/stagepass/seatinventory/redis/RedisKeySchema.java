package dev.stagepass.seatinventory.redis;

import java.util.UUID;

/**
 * Redis key schema for the Seat Inventory Service (ADR-006 §3.10).
 *
 * <p>Key patterns:
 * <pre>
 *   seat:{eventId}:{seatId}
 *     Value: {bookingId} when HELD
 *            "BOOKED" when CommitSeats completes
 *     TTL:   600s when HELD (set atomically with SET NX EX)
 *            no TTL when BOOKED (permanent until event concludes)
 *
 *   flash-hold-processed:{bookingId}
 *     Value: "success" or "failed:{seatId1},{seatId2},..."
 *     TTL:   3600s (flash sale consumer idempotency window)
 *
 *   seat-cooldown:{userId}:{seatId}
 *     Value: "1"
 *     TTL:   120s (THR-SEAT-04: cooling period after hold expiry)
 * </pre>
 *
 * <p>Key cardinality estimate: 50,000-seat stadium event → ~4 MB Redis memory.
 * 10 concurrent popular events → ~40 MB. Well within 256 MB budget (NFR-PERF-043).
 */
public final class RedisKeySchema {

    // BOOKED sentinel value stored in Redis when CommitSeats completes.
    // Distinguishes BOOKED seats from held seats (which store a bookingId UUID).
    public static final String BOOKED_SENTINEL = "BOOKED";

    private RedisKeySchema() {}

    /**
     * Seat hold key. Value is bookingId (UUID) when HELD, "BOOKED" after CommitSeats.
     * Pattern: seat:{eventId}:{seatId}
     */
    public static String seatHoldKey(final UUID eventId, final UUID seatId) {
        return "seat:" + eventId + ":" + seatId;
    }

    /**
     * Flash sale consumer idempotency key.
     * Prevents re-processing a Kafka message on consumer rebalance (ADR-006 §3.3).
     * Pattern: flash-hold-processed:{bookingId}
     */
    public static String flashHoldProcessedKey(final UUID bookingId) {
        return "flash-hold-processed:" + bookingId;
    }

    /**
     * Cooling period key after hold expiry (STRIDE THR-SEAT-04).
     * Prevents a user from immediately re-holding a seat after their hold expired
     * without completing checkout.
     * Pattern: seat-cooldown:{userId}:{seatId}
     */
    public static String seatCooldownKey(final UUID userId, final UUID seatId) {
        return "seat-cooldown:" + userId + ":" + seatId;
    }
}
