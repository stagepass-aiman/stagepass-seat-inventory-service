-- hold-seats.lua
-- Atomic all-or-nothing seat hold via Redis SETNX.
-- ADR-006 §3.2 (normal path) and §3.3 (flash sale path).
-- ADR-006 §3.8 (idempotency-aware — handles retries correctly).
--
-- KEYS: seat hold keys  e.g. ["seat:eventId:seatId1", "seat:eventId:seatId2"]
-- ARGV[1]: bookingId (UUID string) — the hold owner
-- ARGV[2]: ttl (integer seconds, typically 600)
--
-- Returns:
--   1  = all seats acquired (or already held by this bookingId — idempotent success)
--   0  = one or more seats unavailable (held by another booking or BOOKED)
--       Returns table: {0, "seatKey1", "seatKey2"} where seatKey* are the unavailable keys.
--
-- Atomicity guarantee: Redis executes Lua scripts atomically on a single thread.
-- No other command runs between any two steps in this script.

local bookingId = ARGV[1]
local ttl = tonumber(ARGV[2])

-- Track which keys we acquired IN THIS CALL (not on a prior retry).
-- Used for rollback if a later seat is unavailable.
local acquiredThisCall = {}
local unavailable = {}

for i = 1, #KEYS do
    local existing = redis.call('GET', KEYS[i])

    if existing == false then
        -- Seat is AVAILABLE (key does not exist). Acquire it.
        redis.call('SET', KEYS[i], bookingId, 'EX', ttl)
        table.insert(acquiredThisCall, KEYS[i])

    elseif existing == bookingId then
        -- Seat is already HELD by US (retry scenario).
        -- ADR-006 §3.8: DO NOT reset TTL on retry — idempotency requirement.
        -- Count as success but do not add to acquiredThisCall.

    else
        -- Seat is held by another booking or is BOOKED ("BOOKED" sentinel).
        -- Record as unavailable and rollback keys acquired in THIS call only.
        table.insert(unavailable, KEYS[i])
        for _, key in ipairs(acquiredThisCall) do
            -- Only delete keys we set in this call (value == bookingId and
            -- we know we just set it — safe to delete).
            local v = redis.call('GET', key)
            if v == bookingId then
                redis.call('DEL', key)
            end
        end
        -- Return failure. First element is status code, rest are unavailable keys.
        local result = {0}
        for _, k in ipairs(unavailable) do
            table.insert(result, k)
        end
        return result
    end
end

return {1}
