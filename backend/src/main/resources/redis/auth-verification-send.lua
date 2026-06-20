-- KEYS[1] code key
-- KEYS[2] resend key
-- KEYS[3] hourly key
-- ARGV[1] code hash
-- ARGV[2] code ttl seconds
-- ARGV[3] resend ttl seconds
-- ARGV[4] hourly ttl seconds
-- ARGV[5] hourly send limit
if redis.call('EXISTS', KEYS[2]) == 1 then
    return 'RESEND_LIMITED'
end

local current = redis.call('INCR', KEYS[3])
if current == 1 then
    redis.call('EXPIRE', KEYS[3], tonumber(ARGV[4]))
end

if current > tonumber(ARGV[5]) then
    return 'HOURLY_LIMITED'
end

redis.call('SET', KEYS[1], ARGV[1], 'EX', tonumber(ARGV[2]))
redis.call('SET', KEYS[2], '1', 'EX', tonumber(ARGV[3]))
return 'SENT'
