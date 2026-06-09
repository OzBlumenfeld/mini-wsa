package com.akamai.miniwsa.enrichment;

import com.akamai.miniwsa.config.RepeatOffenderProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Redis-backed sliding-window counter answering "how many events has this clientIp produced
 * in the last N minutes?" — too hot-path-latency-sensitive to query ClickHouse for on every
 * ingest (see DESIGN.md "Why Redis here?").
 *
 * Maintains one sorted set per clientIp, scored by the event's own timestamp. Per event:
 * ZADD (write first — the current event counts toward its own threshold, i.e. inclusive
 * counting), then ZREMRANGEBYSCORE to evict entries outside the window, then ZCARD to read
 * the count, then refresh the key's TTL so idle IPs auto-expire.
 *
 * Must be invoked sequentially per event (not in parallel) so that multiple events from the
 * same IP within a single ingest batch accumulate correctly.
 */
@Component
public class RepeatOffenderCache {

    private static final Logger log = LoggerFactory.getLogger(RepeatOffenderCache.class);
    private static final String KEY_PREFIX = "repeat-offender:ip:";

    private final StringRedisTemplate redisTemplate;
    private final RepeatOffenderProperties properties;

    public RepeatOffenderCache(StringRedisTemplate redisTemplate, RepeatOffenderProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * Records this event and returns whether the clientIp is now a "repeat offender" —
     * i.e. has produced more than {@code threshold} events (including this one) within
     * the trailing {@code windowMinutes} window, measured from the event's own timestamp.
     */
    public RepeatOffenderResult isRepeatOffender(String clientIp, String eventId, Instant eventTimestamp) {
        String key = KEY_PREFIX + clientIp;
        ZSetOperations<String, String> zSetOps = redisTemplate.opsForZSet();
        double score = eventTimestamp.toEpochMilli();

        zSetOps.add(key, eventId, score);

        long windowStartMillis = eventTimestamp.minus(properties.windowMinutes(), ChronoUnit.MINUTES).toEpochMilli();
        zSetOps.removeRangeByScore(key, Double.NEGATIVE_INFINITY, windowStartMillis - 1);

        Long count = zSetOps.zCard(key);
        redisTemplate.expire(key, Duration.ofSeconds(properties.keyTtlSeconds()));

        long currentCount = (count == null) ? 0L : count;
        boolean repeatOffender = currentCount > properties.threshold();
        log.debug("Repeat-offender check: clientIp={} count={} threshold={} -> {}",
                clientIp, currentCount, properties.threshold(), repeatOffender);
        return new RepeatOffenderResult(repeatOffender, currentCount);
    }
}
