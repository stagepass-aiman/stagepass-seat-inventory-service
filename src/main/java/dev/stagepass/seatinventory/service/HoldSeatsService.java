package dev.stagepass.seatinventory.service;

import dev.stagepass.seatinventory.entity.IdempotencyKeyEntity;
import dev.stagepass.seatinventory.entity.OutboxEntity;
import dev.stagepass.seatinventory.entity.SeatHoldEntity;
import dev.stagepass.seatinventory.entity.SeatHoldSeatEntity;
import dev.stagepass.seatinventory.entity.SeatStateEntity;
import dev.stagepass.seatinventory.entity.SeatStateEnum;
import dev.stagepass.seatinventory.entity.SeatStateTransitionEntity;
import dev.stagepass.seatinventory.redis.RedisKeySchema;
import dev.stagepass.seatinventory.redis.SeatHoldLuaScript;
import dev.stagepass.seatinventory.repository.IdempotencyKeyRepository;
import dev.stagepass.seatinventory.repository.OutboxRepository;
import dev.stagepass.seatinventory.repository.SeatHoldRepository;
import dev.stagepass.seatinventory.repository.SeatHoldSeatRepository;
import dev.stagepass.seatinventory.repository.SeatStateRepository;
import dev.stagepass.seatinventory.repository.SeatStateTransitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates the two-store hold creation flow (ADR-006 §3.2).
 *
 * <p><strong>Phase 1 — Redis Lua SETNX (synchronous, in gRPC handler thread):</strong>
 * Execute hold-seats.lua. Atomic all-or-nothing. If any seat is unavailable, rollback
 * all acquired keys and return UNAVAILABLE to caller. No PostgreSQL involved here.
 *
 * <p><strong>Phase 2 — PostgreSQL async write (after Phase 1 succeeds):</strong>
 * INSERT seat_holds + seat_hold_seats + seat_state updates + outbox row.
 * All in one @Transactional. Uses INSERT ... ON CONFLICT DO NOTHING for idempotency.
 *
 * <p><strong>The response is sent after Phase 1.</strong> Phase 2 is attempted
 * synchronously in the same request for consistency, but if Phase 2 fails, the
 * reconciliation scheduler will detect the orphaned Redis key and log it for
 * investigation. The TTL will naturally expire if not reconciled.
 */
@Service
public class HoldSeatsService {

    private static final Logger log = LoggerFactory.getLogger(HoldSeatsService.class);

    private final SeatHoldLuaScript luaScript;
    private final SeatStateRepository seatStateRepo;
    private final SeatHoldRepository seatHoldRepo;
    private final SeatHoldSeatRepository seatHoldSeatRepo;
    private final SeatStateTransitionRepository transitionRepo;
    private final OutboxRepository outboxRepo;
    private final IdempotencyKeyRepository idempotencyRepo;

    @Value("${stagepass.redis.seat-hold-ttl-seconds:600}")
    private long holdTtlSeconds;

    public HoldSeatsService(
            final SeatHoldLuaScript luaScript,
            final SeatStateRepository seatStateRepo,
            final SeatHoldRepository seatHoldRepo,
            final SeatHoldSeatRepository seatHoldSeatRepo,
            final SeatStateTransitionRepository transitionRepo,
            final OutboxRepository outboxRepo,
            final IdempotencyKeyRepository idempotencyRepo) {
        this.luaScript = luaScript;
        this.seatStateRepo = seatStateRepo;
        this.seatHoldRepo = seatHoldRepo;
        this.seatHoldSeatRepo = seatHoldSeatRepo;
        this.transitionRepo = transitionRepo;
        this.outboxRepo = outboxRepo;
        this.idempotencyRepo = idempotencyRepo;
    }

    /**
     * Result of a HoldSeats operation.
     *
     * @param success          true if all seats were held
     * @param holdId           UUID of the created/existing hold (null if !success)
     * @param heldUntil        TTL expiry time (null if !success)
     * @param unavailableSeats seat IDs that could not be held (empty if success)
     */
    public record HoldResult(
            boolean success,
            UUID holdId,
            Instant heldUntil,
            List<UUID> unavailableSeats) {}

    /**
     * Attempt to hold seats for a booking.
     * Idempotent: if seats already held for this bookingId, returns existing hold.
     */
    public HoldResult holdSeats(
            final UUID bookingId,
            final UUID eventId,
            final UUID customerId,
            final List<UUID> seatIds,
            final String holdSource) {

        // ── Idempotency check ────────────────────────────────────────────────
        // If we've already processed this bookingId for HOLD_SEATS, return cached result.
        final Optional<IdempotencyKeyEntity> existingKey =
                idempotencyRepo.findByIdempotencyKeyAndOperation(
                        bookingId.toString(), "HOLD_SEATS");
        if (existingKey.isPresent()) {
            log.info("HoldSeats idempotency hit — bookingId={} returning cached result",
                    bookingId);
            // TODO Phase 4 impl: deserialise existingKey.get().getResponsePayload()
            // For now, find the existing hold and return it.
            return seatHoldRepo.findByBookingId(bookingId)
                    .map(hold -> new HoldResult(
                            true, hold.getId(), hold.getExpiresAt(), List.of()))
                    .orElse(new HoldResult(false, null, null,
                            List.of())); // hold expired between check and lookup
        }

        // ── Phase 1: Redis Lua SETNX (fast path) ────────────────────────────
        final List<String> seatKeys = seatIds.stream()
                .map(seatId -> RedisKeySchema.seatHoldKey(eventId, seatId))
                .toList();

        @SuppressWarnings("unchecked")
        final List<Object> luaResult = luaScript.executeHoldSeats(
                seatKeys, bookingId, holdTtlSeconds);

        final long statusCode = (long) luaResult.get(0);
        if (statusCode == 0L) {
            // One or more seats unavailable. Lua script rolled back acquired keys.
            final List<UUID> unavailable = extractUnavailableSeatIds(
                    luaResult, seatKeys, seatIds);
            log.info("HoldSeats UNAVAILABLE — bookingId={} unavailable={}", bookingId, unavailable);
            return new HoldResult(false, null, null, unavailable);
        }

        // ── Phase 2: PostgreSQL async write ─────────────────────────────────
        final Instant expiresAt = Instant.now().plusSeconds(holdTtlSeconds);
        final UUID holdId = UUID.randomUUID();

        try {
            persistHold(holdId, bookingId, eventId, customerId, seatIds,
                    holdSource, expiresAt);
        } catch (final Exception e) {
            // Phase 2 failure: Redis hold succeeded but PG write failed.
            // Reconciliation scheduler will detect orphan and log for investigation.
            // The Redis TTL will naturally expire, releasing the seat.
            log.error("HoldSeats Phase 2 PG write failed — bookingId={} holdId={}. "
                    + "Redis hold is live; reconciliation scheduler will detect orphan. "
                    + "Cause: {}", bookingId, holdId, e.getMessage());
        }

        log.info("HoldSeats SUCCESS — bookingId={} holdId={} expiresAt={}", bookingId, holdId, expiresAt);
        return new HoldResult(true, holdId, expiresAt, List.of());
    }

    @Transactional
    protected void persistHold(
            final UUID holdId,
            final UUID bookingId,
            final UUID eventId,
            final UUID customerId,
            final List<UUID> seatIds,
            final String holdSource,
            final Instant expiresAt) {

        // INSERT ... ON CONFLICT (booking_id) DO NOTHING — idempotent retry handling.
        // If the conflict fires, the hold already exists; that is correct and expected.
        // Note: Spring Data JPA save() does not support ON CONFLICT natively.
        // Use a custom @Modifying @Query or catch DataIntegrityViolationException.
        // TODO Phase 4 impl: implement native upsert query.
        // For scaffold: use save() — will throw on duplicate, caught by caller.
        final SeatHoldEntity hold = SeatHoldEntity.create(
                holdId, bookingId, eventId, customerId, holdSource, expiresAt, seatIds.size());
        seatHoldRepo.save(hold);

        // Create one seat_hold_seats row per seat
        final List<SeatHoldSeatEntity> holdSeats = new ArrayList<>();
        for (final UUID seatId : seatIds) {
            holdSeats.add(SeatHoldSeatEntity.create(UUID.randomUUID(), hold, seatId, eventId));
        }
        seatHoldSeatRepo.saveAll(holdSeats);

        // Update seat_state rows: AVAILABLE → HELD
        // Uses plain UPDATE (not @Version CAS) — Redis Lua is the correctness gate.
        final List<SeatStateEntity> seats =
                seatStateRepo.findByEventIdAndSeatIdIn(eventId, seatIds);
        for (final SeatStateEntity seat : seats) {
            final SeatStateEnum prev = seat.getState();
            seat.markHeld(bookingId, customerId, expiresAt);
            seatStateRepo.save(seat);

            // Audit trail (must be in same transaction as state change)
            final String mac = computePayloadMac(seat.getSeatId(), bookingId,
                    prev.name(), "HELD", Instant.now());
            transitionRepo.save(SeatStateTransitionEntity.create(
                    seat.getSeatId(), eventId, bookingId, customerId,
                    prev.name(), "HELD", "HOLD_CREATED",
                    holdSource.equals("FLASH_SALE") ? "FLASH_SALE_CONSUMER" : "BOOKING_SERVICE",
                    null, mac));

            // Outbox row (same transaction — guarantees delivery to Kafka)
            outboxRepo.save(OutboxEntity.create(
                    UUID.randomUUID(),
                    "seat.state-changed",
                    seat.getSeatId(),
                    eventId,
                    buildSeatStateChangedPayload(seat, "AVAILABLE", "HELD",
                            "HOLD_CREATED", bookingId, expiresAt)));
        }
    }

    // ── Private helpers ───────────────────────────────────────

    private List<UUID> extractUnavailableSeatIds(
            final List<Object> luaResult,
            final List<String> seatKeys,
            final List<UUID> seatIds) {
        // Lua returns {0, "unavailableKey1", ...}. Map keys back to seat IDs.
        final List<UUID> unavailable = new ArrayList<>();
        for (int i = 1; i < luaResult.size(); i++) {
            final String unavailableKey = (String) luaResult.get(i);
            final int keyIndex = seatKeys.indexOf(unavailableKey);
            if (keyIndex >= 0 && keyIndex < seatIds.size()) {
                unavailable.add(seatIds.get(keyIndex));
            }
        }
        return unavailable;
    }

    private String computePayloadMac(
            final UUID seatId, final UUID bookingId,
            final String fromState, final String toState, final Instant occurredAt) {
        // TODO Phase 4 impl: HMAC-SHA256(Vault secret, seatId||bookingId||fromState||toState||occurredAt)
        // Stub returns placeholder for scaffold compilation.
        return "STUB_MAC_" + seatId;
    }

    private String buildSeatStateChangedPayload(
            final SeatStateEntity seat, final String from, final String to,
            final String reason, final UUID bookingId, final Instant heldUntil) {
        // TODO Phase 4 impl: build proper JSON matching seat_async.yaml SeatStateChangedPayload
        return String.format(
                "{\"messageId\":\"%s\",\"schemaVersion\":\"1.0.0\","
                + "\"eventType\":\"seat.state-changed\","
                + "\"seatId\":\"%s\",\"eventId\":\"%s\","
                + "\"fromState\":\"%s\",\"toState\":\"%s\","
                + "\"transitionReason\":\"%s\",\"bookingId\":\"%s\"}",
                UUID.randomUUID(), seat.getSeatId(), seat.getEventId(),
                from, to, reason, bookingId);
    }
}
