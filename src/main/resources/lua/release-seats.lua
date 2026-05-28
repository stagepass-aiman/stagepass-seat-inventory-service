-- release-seats.lua
-- Release held seats back to AVAILABLE by deleting their Redis keys.
-- Called by ReleaseSeats gRPC (saga compensation, ADR-005 §3.6).
-- Idempotent: if a key doesn't exist or belongs to another booking, skip it.
--
-- KEYS: seat hold keys for this booking
-- ARGV[1]: bookingId — only release if WE hold the seat
--
-- Returns: 1 always (idempotent — releasing an already-released seat is success).

local bookingId = ARGV[1]

for i = 1, #KEYS do
    local current = redis.call('GET', KEYS[i])
    if current == bookingId then
        -- We hold this seat. Release it.
        redis.call('DEL', KEYS[i])
    end
    -- If current != bookingId, either:
    --   - Key doesn't exist (already released / expired) — idempotent success
    --   - Key has a different bookingId — another booking; do NOT release
    --   - Key has "BOOKED" sentinel — already committed; do NOT release
end

return 1
