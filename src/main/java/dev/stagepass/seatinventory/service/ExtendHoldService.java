package dev.stagepass.seatinventory.service;

import dev.stagepass.seatinventory.redis.RedisKeySchema;
import dev.stagepass.seatinventory.redis.SeatHoldLuaScript;
import dev.stagepass.seatinventory.repository.SeatHoldRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Extends an existing hold TTL (NFR-AVAIL-003: extend by 300s on Payment failure).
 * TODO Phase 4 impl: implement with seat_holds.expires_at update.
 */
@Service
public class ExtendHoldService {

    private static final Logger log = LoggerFactory.getLogger(ExtendHoldService.class);

    private final SeatHoldLuaScript luaScript;
    private final SeatHoldRepository seatHoldRepo;

    @Value("${stagepass.redis.payment-extend-ttl-seconds:300}")
    private long extendTtlSeconds;

    public ExtendHoldService(
            final SeatHoldLuaScript luaScript,
            final SeatHoldRepository seatHoldRepo) {
        this.luaScript = luaScript;
        this.seatHoldRepo = seatHoldRepo;
    }

    public record ExtendResult(boolean success, Instant heldUntil) {}

    /**
     * Extend hold TTL. Idempotent: if hold no longer exists, returns success.
     * TODO Phase 4 impl: update seat_holds.expires_at + Redis EXPIRE.
     */
    public ExtendResult extendHold(
            final UUID bookingId,
            final UUID eventId,
            final List<UUID> seatIds) {
        final List<String> seatKeys = seatIds.stream()
                .map(seatId -> RedisKeySchema.seatHoldKey(eventId, seatId))
                .toList();
        luaScript.executeExtendHold(seatKeys, bookingId, extendTtlSeconds);
        final Instant newExpiry = Instant.now().plusSeconds(extendTtlSeconds);
        log.info("ExtendHold — bookingId={} extendedTo={}", bookingId, newExpiry);
        return new ExtendResult(true, newExpiry);
    }
}
