package com.akamai.miniwsa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the repeat-offender sliding-window check (REQUIREMENTS.md Part 2b:
 * "+15 if more than `threshold` events from the same clientIp in the last `windowMinutes`").
 *
 * {@code keyTtlSeconds} should comfortably exceed {@code windowMinutes} so a Redis key isn't
 * evicted mid-window during a quiet period, while still bounding how long idle IPs linger.
 */
@ConfigurationProperties(prefix = "miniwsa.repeat-offender")
public record RepeatOffenderProperties(
        int windowMinutes,
        int threshold,
        long keyTtlSeconds
) {
}
