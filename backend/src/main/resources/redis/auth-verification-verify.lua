-- KEYS[1] code key
-- KEYS[2] verify-rate key
-- ARGV[1] presented code hash
-- ARGV[2] max failed attempts
-- ARGV[3] verify-rate ttl seconds
local stored = redis.call('GET', KEYS[1])
if not stored then
    return 'MISSING'
end

local failed = redis.call('GET', KEYS[2])
if failed and tonumber(failed) >= tonumber(ARGV[2]) then
    redis.call('DEL', KEYS[1])
    return 'LOCKED'
end

if stored == ARGV[1] then
    redis.call('DEL', KEYS[1])
    redis.call('DEL', KEYS[2])
    return 'MATCHED'
end

local attempts = redis.call('INCR', KEYS[2])
if attempts == 1 then
    redis.call('EXPIRE', KEYS[2], tonumber(ARGV[3]))
end

if attempts >= tonumber(ARGV[2]) then
    redis.call('DEL', KEYS[1])
    return 'LOCKED'
end

return 'MISMATCHED'
