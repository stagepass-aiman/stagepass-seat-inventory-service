package dev.stagepass.seatinventory.service;

import dev.stagepass.seatinventory.entity.OutboxEntity;
import dev.stagepass.seatinventory.entity.SeatHoldEntity;
import dev.stagepass.seatinventory.entity.SeatHoldSeatEntity;
import dev.stagepass.seatinventory.entity.SeatStateEntity;
import dev.stagepass.seatinventory.entity.SeatStateTransitionEntity;
import dev.stagepass.seatinventory.repository.OutboxRepository;
import dev.stagepass.seatinventory.repository.SeatHoldRepository;
import dev.stagepass.seatinventory.repository.SeatHoldSeatRepository;
import dev.stagepass.seatinventory.repository.SeatStateRepository;
import dev.stagepass.seatinventory.repository.SeatStateTransitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Commits held seats to BOOKED after payment confirmation.
 *
 * <p><strong>SERIALIZABLE isolation (NFR-REL-008, ADR-006 §3.7):</strong>
 * This is the only method in the service that uses SERIALIZABLE isolation.
 * It is placed here — at CommitSeats, not HoldSeats — because:
 * <ul>
 *   <li>CommitSeats runs once per completed booking (after payment), much lower
 *       frequency than HoldSeats (every checkout attempt).</li>
 *   <li>At this point real money has moved. The HELD→BOOKED transition must be
 *       durable, isolated, and atomic with any revenue split entry the Booking
 *       Service creates.</li>
 *   <li>Applying SERIALIZABLE to HoldSeats would serialise all concurrent hold
 *       attempts through the PostgreSQL lock manager — equivalent to the pessimistic
 *       locking alternative ADR-006 explicitly rejected (ADR-006 §6.1).</li>
 * </ul>
 *
 * <p>On SERIALIZABLE serialisation failure (PostgreSQL SSI conflict): the caller
 * (Booking Service) retries per NFR-REL-006 (3 attempts, exponential backoff).
 * The reconciliation job backs this up: if all retries fail, it detects the HELD
 * seat and retries CommitSeats within 5 minutes.
 */
@Service
public class CommitSeatsService {

    private static final Logger log = LoggerFactory.getLogger(CommitSeatsService.class);

    private final SeatStateRepository seatStateRepo;
    private final SeatHoldRepository seatHoldRepo;
    private final SeatHoldSeatRepository seatHoldSeatRepo;
    private final SeatStateTransitionRepository transitionRepo;
    private final OutboxRepository outboxRepo;
    private final StringRedisTemplate redis;

    public CommitSeatsService(
            final SeatStateRepository seatStateRepo,
            final SeatHoldRepository seatHoldRepo,
            final SeatHoldSeatRepository seatHoldSeatRepo,
            final SeatStateTransitionRepository transitionRepo,
            final OutboxRepository outboxRepo,
            final StringRedisTemplate redis) {
        this.seatStateRepo = seatStateRepo;
        this.seatHoldRepo = seatHoldRepo;
        this.seatHoldSeatRepo = seatHoldSeatRepo;
        this.transitionRepo = transitionRepo;
        this.outboxRepo = outboxRepo;
        this.redis = redis;
    }

    public enum CommitResult { SUCCESS, HOLD_NOT_FOUND, SERIALIZATION_FAILURE }

    /**
     * Commit held seats to BOOKED.
     *
     * <p>Idempotent: if seats already BOOKED for this bookingId, returns SUCCESS.
     *
     * @throws org.springframework.dao.CannotSerializeTransactionException
     *         on SERIALIZABLE conflict — caller must retry per NFR-REL-006.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public CommitResult commitSeats(
            final UUID bookingId,
            final UUID eventId,
            final List<UUID> seatIds) {

        // Find the hold record
        final Optional<SeatHoldEntity> holdOpt = seatHoldRepo.findByBookingId(bookingId);
        if (holdOpt.isEmpty()) {
            log.warn("CommitSeats HOLD_NOT_FOUND — bookingId={}", bookingId);
            return CommitResult.HOLD_NOT_FOUND;
        }
        final SeatHoldEntity hold = holdOpt.get();

        // Idempotency: if already committed, return success
        if ("BOOKED".equals(hold.getStatus())) {
            log.info("CommitSeats idempotent — bookingId={} already BOOKED", bookingId);
            return CommitResult.SUCCESS;
        }

        final Instant committedAt = Instant.now();

        // Update each seat: HELD → BOOKED
        final List<SeatStateEntity> seats =
                seatStateRepo.findByEventIdAndSeatIdIn(eventId, seatIds);

        // Guard: must find exactly seatIds.length rows in HELD state for this booking.
        // If count differs, a concurrent release or re-hold occurred — abort.
        final long heldCount = seats.stream()
                .filter(s -> "HELD".equals(s.getState().name())
                          && bookingId.equals(s.getBookingId()))
                .count();
        if (heldCount != seatIds.size()) {
            log.error("CommitSeats row count mismatch — expected={} found={} bookingId={}",
                    seatIds.size(), heldCount, bookingId);
            return CommitResult.HOLD_NOT_FOUND;
        }

        for (final SeatStateEntity seat : seats) {
            seat.markBooked(committedAt);
            seatStateRepo.save(seat);

            // Update Redis: remove TTL by setting value to BOOKED sentinel (no EX)
            redis.opsForValue().set(
                    dev.stagepass.seatinventory.redis.RedisKeySchema
                            .seatHoldKey(eventId, seat.getSeatId()),
                    dev.stagepass.seatinventory.redis.RedisKeySchema.BOOKED_SENTINEL);

            // Audit trail
            transitionRepo.save(SeatStateTransitionEntity.create(
                    seat.getSeatId(), eventId, bookingId, seat.getCustomerId(),
                    "HELD", "BOOKED", "BOOKING_COMMITTED",
                    "BOOKING_SERVICE", null,
                    "STUB_MAC_" + seat.getSeatId())); // TODO: real HMAC

            // Outbox row for Notification Service seat map update
            outboxRepo.save(OutboxEntity.create(
                    UUID.randomUUID(), "seat.state-changed",
                    seat.getSeatId(), eventId,
                    buildCommitPayload(seat, bookingId, committedAt)));
        }

        // Update seat_holds header
        hold.markBooked();
        seatHoldRepo.save(hold);

        // Update seat_hold_seats rows
        final List<SeatHoldSeatEntity> holdSeats = seatHoldSeatRepo.findByHoldId(hold.getId());
        holdSeats.forEach(SeatHoldSeatEntity::markCommitted);
        seatHoldSeatRepo.saveAll(holdSeats);

        log.info("CommitSeats SUCCESS — bookingId={} seatCount={} committedAt={}",
                bookingId, seatIds.size(), committedAt);
        return CommitResult.SUCCESS;
    }

    private String buildCommitPayload(
            final SeatStateEntity seat, final UUID bookingId, final Instant committedAt) {
        // TODO Phase 4 impl: proper JSON per seat_async.yaml
        return String.format(
                "{\"messageId\":\"%s\",\"schemaVersion\":\"1.0.0\","
                + "\"eventType\":\"seat.state-changed\","
                + "\"seatId\":\"%s\",\"eventId\":\"%s\","
                + "\"fromState\":\"HELD\",\"toState\":\"BOOKED\","
                + "\"transitionReason\":\"BOOKING_COMMITTED\",\"bookingId\":\"%s\"}",
                UUID.randomUUID(), seat.getSeatId(), seat.getEventId(), bookingId);
    }
}
