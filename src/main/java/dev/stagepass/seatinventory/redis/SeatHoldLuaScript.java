package dev.stagepass.seatinventory.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Loads and executes Lua scripts for seat hold operations.
 *
 * <p>Scripts are loaded from classpath:lua/ at construction time via ClassPathResource.
 * This keeps Lua code in version-controlled files rather than inline strings.
 *
 * <p>Spring Data Redis executes scripts via EVALSHA (with SHA-based caching) when
 * the script is registered as a DefaultRedisScript. This avoids sending the full
 * script on every call after the first.
 */
@Component
public class SeatHoldLuaScript {

    private static final Logger log = LoggerFactory.getLogger(SeatHoldLuaScript.class);

    private final StringRedisTemplate redis;

    // Scripts loaded at startup. DefaultRedisScript caches the SHA for EVALSHA.
    private final RedisScript<List> holdSeatsScript;
    private final RedisScript<Long>  extendHoldScript;
    private final RedisScript<Long>  releaseSeatsScript;

    public SeatHoldLuaScript(final StringRedisTemplate redis) {
        this.redis = redis;
        this.holdSeatsScript   = loadScript("lua/hold-seats.lua",    List.class);
        this.extendHoldScript  = loadScript("lua/extend-hold.lua",   Long.class);
        this.releaseSeatsScript = loadScript("lua/release-seats.lua", Long.class);
        log.info("Seat hold Lua scripts loaded from classpath:lua/");
    }

    private <T> RedisScript<T> loadScript(final String path, final Class<T> returnType) {
        final DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(path)));
        script.setResultType(returnType);
        return script;
    }

    /**
     * Execute the hold-seats Lua script.
     *
     * @param seatKeys  Redis keys for each seat (seat:{eventId}:{seatId})
     * @param bookingId Hold owner
     * @param ttlSeconds Hold duration (600s per NFR-REL-004)
     * @return Script result: first element is 1 (success) or 0 (failure).
     *         On failure, remaining elements are the unavailable key strings.
     */
    @SuppressWarnings("unchecked")
    public List<Object> executeHoldSeats(
            final List<String> seatKeys,
            final UUID bookingId,
            final long ttlSeconds) {
        return redis.execute(
                holdSeatsScript,
                seatKeys,
                bookingId.toString(),
                String.valueOf(ttlSeconds));
    }

    /**
     * Execute the extend-hold Lua script.
     *
     * @param seatKeys          Redis keys for each seat in this hold
     * @param bookingId         Hold owner
     * @param additionalSeconds Additional TTL (300s per NFR-AVAIL-003)
     */
    public void executeExtendHold(
            final List<String> seatKeys,
            final UUID bookingId,
            final long additionalSeconds) {
        redis.execute(
                extendHoldScript,
                seatKeys,
                bookingId.toString(),
                String.valueOf(additionalSeconds));
    }

    /**
     * Execute the release-seats Lua script.
     * Idempotent: releasing already-released seats returns success.
     *
     * @param seatKeys  Redis keys for each seat to release
     * @param bookingId Hold owner — only releases keys owned by this booking
     */
    public void executeReleaseSeats(
            final List<String> seatKeys,
            final UUID bookingId) {
        redis.execute(releaseSeatsScript, seatKeys, bookingId.toString());
    }
}
