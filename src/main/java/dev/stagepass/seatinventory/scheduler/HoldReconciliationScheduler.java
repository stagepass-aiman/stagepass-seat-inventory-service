package dev.stagepass.seatinventory.scheduler;

import dev.stagepass.seatinventory.entity.OutboxEntity;
import dev.stagepass.seatinventory.entity.SeatHoldEntity;
import dev.stagepass.seatinventory.entity.SeatStateEntity;
import dev.stagepass.seatinventory.entity.SeatStateTransitionEntity;
import dev.stagepass.seatinventory.repository.OutboxRepository;
import dev.stagepass.seatinventory.repository.SeatHoldRepository;
import dev.stagepass.seatinventory.repository.SeatStateRepository;
import dev.stagepass.seatinventory.repository.SeatStateTransitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reconciles PostgreSQL with Redis TTL expiry.
 *
 * <p>Problem: Redis TTL expiry does NOT automatically update PostgreSQL (ADR-006 §3.6).
 * When a seat hold TTL expires, Redis atomically deletes the key — the seat is now
 * AVAILABLE in Redis. But seat_state.state and seat_holds.status still show HELD.
 *
 * <p>This scheduler runs every 5 minutes, finds expired HELD records in PostgreSQL,
 * and updates them to AVAILABLE. It also publishes a SeatHoldExpired event via the
 * Outbox so the Notification Service can push the "checkout window expired" WebSocket
 * message to the customer.
 *
 * <p>Why 5 minutes is acceptable: PostgreSQL is NOT in the seat hold creation path.
 * Only Redis is consulted for HoldSeats decisions. A seat that appears HELD in PostgreSQL
 * but has an expired Redis key will correctly fail the Lua SETNX and allow a new booking.
 * The 5-minute PostgreSQL lag affects only: (a) initial seat map load from PostgreSQL,
 * (b) CheckSeatAvailability BLOCKED state check, (c) reporting queries.
 */
@Component
public class HoldReconciliationScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(HoldReconciliationScheduler.class);

    private final SeatHoldRepository seatHoldRepo;
    private final SeatStateRepository seatStateRepo;
    private final SeatStateTransitionRepository transitionRepo;
    private final OutboxRepository outboxRepo;

    public HoldReconciliationScheduler(
            final SeatHoldRepository seatHoldRepo,
            final SeatStateRepository seatStateRepo,
            final SeatStateTransitionRepository transitionRepo,
            final OutboxRepository outboxRepo) {
        this.seatHoldRepo = seatHoldRepo;
        this.seatStateRepo = seatStateRepo;
        this.transitionRepo = transitionRepo;
        this.outboxRepo = outboxRepo;
    }

    @Scheduled(fixedDelayString = "${stagepass.reconciliation.interval-ms:300000}")
    @Transactional
    public void reconcileExpiredHolds() {
        final Instant now = Instant.now();
        final List<SeatHoldEntity> expiredHolds = seatHoldRepo.findExpiredHeldHolds(now);

        if (expiredHolds.isEmpty()) {
            return;
        }

        log.info("HoldReconciliation: processing {} expired holds", expiredHolds.size());

        for (final SeatHoldEntity hold : expiredHolds) {
            try {
                hold.markExpired();
                seatHoldRepo.save(hold);

                // Update each seat_state row: HELD → AVAILABLE
                final List<SeatStateEntity> seats =
                        seatStateRepo.findByBookingId(hold.getBookingId());
                for (final SeatStateEntity seat : seats) {
                    if ("HELD".equals(seat.getState().name())) {
                        seat.markAvailable();
                        seatStateRepo.save(seat);

                        // Audit trail
                        transitionRepo.save(SeatStateTransitionEntity.create(
                                seat.getSeatId(), seat.getEventId(),
                                hold.getBookingId(), hold.getCustomerId(),
                                "HELD", "AVAILABLE", "HOLD_EXPIRED",
                                "SEAT_INVENTORY_SCHEDULER", null,
                                "STUB_MAC_" + seat.getSeatId())); // TODO: real HMAC

                        // Outbox: SeatHoldExpired event for Notification Service
                        outboxRepo.save(OutboxEntity.create(
                                UUID.randomUUID(), "seat.hold-expired",
                                seat.getSeatId(), seat.getEventId(),
                                buildHoldExpiredPayload(seat, hold)));
                    }
                }

                log.info("HoldReconciliation: expired bookingId={} seatCount={}",
                        hold.getBookingId(), seats.size());
            } catch (final Exception e) {
                log.error("HoldReconciliation: failed for bookingId={} cause={}",
                        hold.getBookingId(), e.getMessage(), e);
            }
        }
    }

    private String buildHoldExpiredPayload(
            final SeatStateEntity seat, final SeatHoldEntity hold) {
        // TODO Phase 4 impl: proper JSON per seat_async.yaml SeatHoldExpiredPayload
        return String.format(
                "{\"messageId\":\"%s\",\"schemaVersion\":\"1.0.0\","
                + "\"eventType\":\"seat.hold-expired\","
                + "\"seatId\":\"%s\",\"eventId\":\"%s\","
                + "\"bookingId\":\"%s\",\"customerId\":\"%s\",\"expiredAt\":\"%s\"}",
                UUID.randomUUID(), seat.getSeatId(), seat.getEventId(),
                hold.getBookingId(), hold.getCustomerId(), Instant.now());
    }
}
