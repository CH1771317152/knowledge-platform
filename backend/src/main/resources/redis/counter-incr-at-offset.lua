-- counter-incr-at-offset.lua
-- Atomically increment one or more 8-byte little-endian int64 counters within a CountInt blob.
-- Lua 5.1 (Redis embedded): NO string.pack; use the `bit` library for LE int64 packing.
--   KEYS[1] = cnt:{etype}:{eid} blob key
--   ARGV    = [offset, delta, offset, delta, ...]
-- Returns 1 on success.

local function unpackLE(s)
  -- s is the GETRANGE result: a Lua string of exactly 8 bytes (or shorter if blob too short).
  local low = 0
  local high = 0
  for i = 0, 3 do
    local b = string.byte(s, i + 1) or 0
    low = low + b * (2 ^ (i * 8))
  end
  for i = 0, 3 do
    local b = string.byte(s, i + 5) or 0
    high = high + b * (2 ^ (i * 8))
  end
  return high * (2 ^ 32) + low
end

local function packLE(val)
  if val < 0 then val = 0 end
  local low = val % (2 ^ 32)
  local high = math.floor(val / (2 ^ 32)) % (2 ^ 32)
  local b = {}
  for i = 0, 3 do
    b[#b + 1] = string.char(bit.band(bit.rshift(low, i * 8), 255))
  end
  for i = 0, 3 do
    b[#b + 1] = string.char(bit.band(bit.rshift(high, i * 8), 255))
  end
  return table.concat(b)
end

local key = KEYS[1]
for i = 1, #ARGV, 2 do
  local off = tonumber(ARGV[i])
  local delta = tonumber(ARGV[i + 1])
  local raw = redis.call('GETRANGE', key, off, off + 7)
  local val = 0
  if string.len(raw) == 8 then val = unpackLE(raw) end
  val = val + delta
  if val < 0 then val = 0 end
  redis.call('SETRANGE', key, off, packLE(val))
end
return 1
