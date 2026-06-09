package com.akamai.miniwsa.api.dto.samples;

import java.time.Instant;

public record EnrichedEventResponse(
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
        String ruleId,
        String ruleName,
        String ruleMessage,
        String ruleSeverity,
        String ruleCategory,
        String action,
        String geoCountry,
        String geoCity,
        Integer requestSize,
        Integer responseSize,
        String attackType,
        Integer threatScore
) {}
