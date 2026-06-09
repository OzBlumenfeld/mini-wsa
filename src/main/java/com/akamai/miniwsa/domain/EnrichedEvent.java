package com.akamai.miniwsa.domain;

import java.time.Instant;

/**
 * A {@link SecurityEvent} after classification and threat scoring — what gets persisted.
 */
public record EnrichedEvent(
        SecurityEvent original,
        String attackType,
        int threatScore,
        Instant receivedAt
) {
}
