-- extend-hold.lua
-- Extend the TTL of an existing hold by additionalTtlSeconds.
-- Called when Payment Service is unavailable (NFR-AVAIL-003: extend by 300s).
--
-- KEYS: seat hold keys for this booking
-- ARGV[1]: bookingId — only extend if WE hold the seat
-- ARGV[2]: additionalTtlSeconds (typically 300)
--
-- Returns: 1 = success (including idempotent: hold doesn't exist)
-- Idempotent: if a seat key doesn't exist (hold expired), that seat is skipped.
-- The TTL is extended from the CURRENT remaining TTL, not reset to additionalTtlSeconds.

local bookingId = ARGV[1]
local additionalTtl = tonumber(ARGV[2])

for i = 1, #KEYS do
    local current = redis.call('GET', KEYS[i])
    if current == bookingId then
        -- We hold this seat. Extend from current TTL.
        local currentTtl = redis.call('TTL', KEYS[i])
        if currentTtl > 0 then
            redis.call('EXPIRE', KEYS[i], currentTtl + additionalTtl)
        end
        -- If currentTtl == -1 (no TTL) or -2 (key gone), do nothing.
        -- -1 means BOOKED (no TTL set), so we don't extend it.
        -- -2 means key is already gone (expired), nothing to extend.
    end
    -- If current != bookingId, another booking holds it (or BOOKED by someone else).
    -- Skip silently — idempotent.
end

return 1
