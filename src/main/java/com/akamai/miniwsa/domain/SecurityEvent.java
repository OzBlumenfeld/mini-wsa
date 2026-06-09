package com.akamai.miniwsa.domain;

import java.time.Instant;

/**
 * Validated, parsed representation of an inbound security event — the pipeline's internal
 * source of truth, decoupled from the wire format (api.dto.SecurityEventRequest).
 */
public record SecurityEvent(
        String eventId,
        Instant timestamp,
        Long configId,
        String policyId,
        String clientIp,
        String hostname,
        String path,
        String method,
        Integer statusCode,
        String userAgent,
        Rule rule,
        Action action,
        GeoLocation geoLocation,
        Integer requestSize,
        Integer responseSize
) {
}
