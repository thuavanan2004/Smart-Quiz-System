-- Sliding window rate limiter. Design auth-service §9.1.
-- KEYS[1] = bucket key
-- ARGV[1] = now (ms since epoch)
-- ARGV[2] = window (ms)
-- ARGV[3] = limit
-- ARGV[4] = unique id (caller-provided, tránh ZADD member trùng khi cùng ms)
-- Returns: 0 if allowed, else retry-after in seconds (>=1)

local key    = KEYS[1]
local now    = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit  = tonumber(ARGV[3])
local member = ARGV[1] .. ':' .. ARGV[4]

redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
local count = redis.call('ZCARD', key)
if count < limit then
    redis.call('ZADD', key, now, member)
    redis.call('PEXPIRE', key, window)
    return 0
end

local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
local wait_ms = (tonumber(oldest[2]) + window) - now
if wait_ms < 1000 then wait_ms = 1000 end
return math.ceil(wait_ms / 1000)
