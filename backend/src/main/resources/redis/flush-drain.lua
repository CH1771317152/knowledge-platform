-- flush-drain.lua
-- Atomically drain a per-entity aggregate hash (HINCRBY staging area) into the CountInt blob.
-- Lua 5.1 (Redis embedded): NO string.pack; use the `bit` library for LE int64 packing.
--   KEYS[1] = agg:{etype}:{eid}   (the staging hash)
--   KEYS[2] = cnt:{etype}:{eid}   (the persistent blob)
--   ARGV    = [metric, offset, metric, offset, ...]  (schema offsets for this etype, built in Java)
-- Atomically: HGETALL agg -> DEL agg -> for each (metric,offset) present in agg,
--   GETRANGE cnt at offset -> +delta (clamp 0) -> SETRANGE. Returns 1 if drained, 0 if agg was empty.

local function unpackLE(s)
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

local aggKey = KEYS[1]
local cntKey = KEYS[2]
local fields = redis.call('HGETALL', aggKey)
if #fields == 0 then return 0 end
redis.call('DEL', aggKey)

-- Build metric -> offset map from ARGV (schema offsets for this etype).
local offsets = {}
for i = 1, #ARGV, 2 do
  offsets[ARGV[i]] = tonumber(ARGV[i + 1])
end

for j = 1, #fields, 2 do
  local metric = fields[j]
  local delta = tonumber(fields[j + 1])
  local off = offsets[metric]
  if off ~= nil then
    local raw = redis.call('GETRANGE', cntKey, off, off + 7)
    local val = 0
    if string.len(raw) == 8 then val = unpackLE(raw) end
    val = val + delta
    if val < 0 then val = 0 end
    redis.call('SETRANGE', cntKey, off, packLE(val))
  end
end
return 1
