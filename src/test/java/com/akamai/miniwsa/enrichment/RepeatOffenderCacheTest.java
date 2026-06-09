package com.akamai.miniwsa.enrichment;

import com.akamai.miniwsa.config.RepeatOffenderProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises RepeatOffenderCache against a real Redis instance — proving the actual
 * ZADD / ZREMRANGEBYSCORE / ZCARD sequence and sliding-window math, which a mocked
 * ZSetOperations cannot faithfully validate.
 */
@Testcontainers
class RepeatOffenderCacheTest {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    private static final RepeatOffenderProperties PROPERTIES = new RepeatOffenderProperties(10, 5, 900);
    private static final Instant BASE_TIME = Instant.parse("2026-06-07T10:00:00Z");

    private StringRedisTemplate redisTemplate;
    private RepeatOffenderCache cache;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory connectionFactory =
                new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        cache = new RepeatOffenderCache(redisTemplate, PROPERTIES);
    }

    @Test
    void firstEventFromFreshIpIsNotARepeatOffender() {
        boolean repeatOffender = cache.isRepeatOffender("203.0.113.1", "evt-1", BASE_TIME);
        assertThat(repeatOffender).isFalse();
    }

    @Test
    void sixthEventWithinWindowTriggersRepeatOffenderBonus() {
        String clientIp = "203.0.113.2";
        for (int i = 1; i <= 5; i++) {
            boolean result = cache.isRepeatOffender(clientIp, "evt-" + i, BASE_TIME.plusSeconds(i * 10L));
            assertThat(result).as("event #%d should not yet trigger the bonus", i).isFalse();
        }

        boolean sixth = cache.isRepeatOffender(clientIp, "evt-6", BASE_TIME.plusSeconds(60));
        assertThat(sixth).as("6th event (count > 5, inclusive) should trigger the bonus").isTrue();
    }

    @Test
    void eventsOutsideTheWindowAreEvicted() {
        String clientIp = "203.0.113.3";

        // 5 events far in the past -- outside the 10-minute window relative to `current`
        Instant stale = BASE_TIME;
        for (int i = 1; i <= 5; i++) {
            cache.isRepeatOffender(clientIp, "stale-" + i, stale.plusSeconds(i));
        }

        // a new event 20 minutes later: the stale ones should be evicted, leaving only this one
        Instant current = BASE_TIME.plus(20, ChronoUnit.MINUTES);
        boolean repeatOffender = cache.isRepeatOffender(clientIp, "fresh-1", current);

        assertThat(repeatOffender).as("stale entries should have been evicted, count should be 1").isFalse();
    }

    @Test
    void differentIpsAreCountedIndependently() {
        String ipA = "203.0.113.10";
        String ipB = "203.0.113.20";

        for (int i = 1; i <= 5; i++) {
            cache.isRepeatOffender(ipA, "a-" + i, BASE_TIME.plusSeconds(i));
        }

        // ipB's first event should not be affected by ipA's history
        boolean ipBFirstEvent = cache.isRepeatOffender(ipB, "b-1", BASE_TIME.plusSeconds(100));
        assertThat(ipBFirstEvent).isFalse();

        // ipA's 6th event should still trigger the bonus
        boolean ipASixthEvent = cache.isRepeatOffender(ipA, "a-6", BASE_TIME.plusSeconds(6));
        assertThat(ipASixthEvent).isTrue();
    }

    @Test
    void refreshesTtlOnEachWrite() {
        String key = "repeat-offender:ip:203.0.113.30";
        cache.isRepeatOffender("203.0.113.30", "evt-1", BASE_TIME);

        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttl).isNotNull();
        assertThat(ttl).isGreaterThan(0L);
        assertThat(ttl).isLessThanOrEqualTo(PROPERTIES.keyTtlSeconds());
    }
}
