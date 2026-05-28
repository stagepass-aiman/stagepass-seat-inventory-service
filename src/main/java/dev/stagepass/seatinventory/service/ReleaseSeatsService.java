package dev.stagepass.seatinventory.service;

import dev.stagepass.seatinventory.redis.RedisKeySchema;
import dev.stagepass.seatinventory.redis.SeatHoldLuaScript;
import dev.stagepass.seatinventory.repository.OutboxRepository;
import dev.stagepass.seatinventory.repository.SeatHoldRepository;
import dev.stagepass.seatinventory.repository.SeatHoldSeatRepository;
import dev.stagepass.seatinventory.repository.SeatStateRepository;
import dev.stagepass.seatinventory.repository.SeatStateTransitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Releases held seats back to AVAILABLE (saga compensation, ADR-005 §3.6).
 *
 * <p>Uses READ COMMITTED isolation (not SERIALIZABLE) — there is no race
 * to prevent: either the hold exists (release it) or it doesn't (idempotent success).
 * ADR-006 §3.7: SERIALIZABLE applies to CommitSeats only.
 *
 * <p>TODO Phase 4 impl: implement full release logic.
 */
@Service
public class ReleaseSeatsService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseSeatsService.class);

    private final SeatHoldLuaScript luaScript;
    private final SeatStateRepository seatStateRepo;
    private final SeatHoldRepository seatHoldRepo;
    private final SeatHoldSeatRepository seatHoldSeatRepo;
    private final SeatStateTransitionRepository transitionRepo;
    private final OutboxRepository outboxRepo;

    public ReleaseSeatsService(
            final SeatHoldLuaScript luaScript,
            final SeatStateRepository seatStateRepo,
            final SeatHoldRepository seatHoldRepo,
            final SeatHoldSeatRepository seatHoldSeatRepo,
            final SeatStateTransitionRepository transitionRepo,
            final OutboxRepository outboxRepo) {
        this.luaScript = luaScript;
        this.seatStateRepo = seatStateRepo;
        this.seatHoldRepo = seatHoldRepo;
        this.seatHoldSeatRepo = seatHoldSeatRepo;
        this.transitionRepo = transitionRepo;
        this.outboxRepo = outboxRepo;
    }

    public enum ReleaseResult { SUCCESS, ALREADY_RELEASED }

    /**
     * Release seats for a booking. Idempotent.
     * TODO Phase 4 impl: full implementation.
     */
    @Transactional
    public ReleaseResult releaseSeats(
            final UUID bookingId,
            final UUID eventId,
            final List<UUID> seatIds,
            final String reason) {
        // Phase 1: Redis DEL via Lua (only deletes keys owned by this bookingId)
        final List<String> seatKeys = seatIds.stream()
                .map(seatId -> RedisKeySchema.seatHoldKey(eventId, seatId))
                .toList();
        luaScript.executeReleaseSeats(seatKeys, bookingId);

        // Phase 2: PostgreSQL update
        // TODO Phase 4 impl: update seat_state, seat_holds, seat_hold_seats,
        //   seat_state_transitions, outbox in one @Transactional
        log.info("ReleaseSeats SUCCESS (stub) — bookingId={} seatCount={}",
                bookingId, seatIds.size());
        return ReleaseResult.SUCCESS;
    }
}
