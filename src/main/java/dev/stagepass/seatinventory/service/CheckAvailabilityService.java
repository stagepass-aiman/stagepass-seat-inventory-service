package dev.stagepass.seatinventory.service;

import dev.stagepass.seatinventory.entity.SeatStateEnum;
import dev.stagepass.seatinventory.redis.RedisKeySchema;
import dev.stagepass.seatinventory.repository.SeatStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Read-only seat availability check (ADR-006 §3.4 TOCTOU analysis).
 *
 * <p><strong>This is NOT a correctness control.</strong> It is an optimisation:
 * fail-fast before creating a PENDING booking record, saving a DB write and giving
 * the customer early feedback. A AVAILABLE response here provides ZERO guarantee
 * about seat state when HoldSeats runs — another booking may hold the seat in
 * the TOCTOU window.
 *
 * <p>The correctness guarantee is provided exclusively by the Redis Lua SETNX
 * in HoldSeatsService (ADR-006 §3.4 — "The correctness guarantee is provided
 * entirely by the Redis Lua SETNX in HoldSeats").
 *
 * <p>Check order: Redis first (sub-ms), then PostgreSQL for BLOCKED state
 * (Redis has no key for BLOCKED seats — ADR-006 §3.9).
 */
@Service
public class CheckAvailabilityService {

    private static final Logger log = LoggerFactory.getLogger(CheckAvailabilityService.class);

    private final StringRedisTemplate redis;
    private final SeatStateRepository seatStateRepo;

    public CheckAvailabilityService(
            final StringRedisTemplate redis,
            final SeatStateRepository seatStateRepo) {
        this.redis = redis;
        this.seatStateRepo = seatStateRepo;
    }

    public record UnavailableSeat(UUID seatId, String currentState) {}

    public record AvailabilityResult(boolean allAvailable, List<UnavailableSeat> unavailable) {}

    /**
     * Check availability of requested seats. Read-only. Fast path.
     */
    public AvailabilityResult checkAvailability(final UUID eventId, final List<UUID> seatIds) {
        final List<UnavailableSeat> unavailable = new ArrayList<>();

        for (final UUID seatId : seatIds) {
            final String redisKey = RedisKeySchema.seatHoldKey(eventId, seatId);
            final String redisValue = redis.opsForValue().get(redisKey);

            if (redisValue != null) {
                // Key exists: seat is either HELD or BOOKED
                final String state = RedisKeySchema.BOOKED_SENTINEL.equals(redisValue)
                        ? "BOOKED" : "HELD";
                unavailable.add(new UnavailableSeat(seatId, state));
                continue;
            }

            // Redis key absent. Check PostgreSQL for BLOCKED state.
            // AVAILABLE seats have no Redis key and PG state = AVAILABLE — no PG query needed
            // unless we need to verify the seat exists and check for BLOCKED.
            seatStateRepo.findByEventIdAndSeatId(eventId, seatId).ifPresent(seat -> {
                if (SeatStateEnum.BLOCKED.equals(seat.getState())) {
                    unavailable.add(new UnavailableSeat(seatId, "BLOCKED"));
                }
            });
        }

        return new AvailabilityResult(unavailable.isEmpty(), unavailable);
    }
}
