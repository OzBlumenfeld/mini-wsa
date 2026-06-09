package com.akamai.miniwsa.api.dto;

import com.akamai.miniwsa.domain.Action;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Inbound wire format for a single security event (REQUIREMENTS.md domain model).
 *
 * {@code timestamp} is kept as a {@code String} here — only loosely pattern-checked —
 * because the authoritative {@code Instant.parse} happens per-event in the service layer.
 * Binding directly to {@code Instant} would let one malformed timestamp fail Jackson's
 * deserialization of the *entire* batch, which conflicts with per-event error isolation.
 */
public record SecurityEventRequest(
        @NotBlank String eventId,

        @NotBlank
        @Pattern(
                regexp = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})$",
                message = "must be a valid ISO-8601 timestamp"
        )
        String timestamp,

        @NotNull Long configId,
        @NotBlank String policyId,
        @NotBlank String clientIp,
        @NotBlank String hostname,
        @NotBlank String path,
        @NotBlank String method,

        @NotNull Integer statusCode,

        String userAgent,

        @Valid @NotNull RuleRequest rule,

        @NotNull Action action,

        @Valid GeoLocationRequest geoLocation,

        @NotNull @PositiveOrZero Integer requestSize,
        @NotNull @PositiveOrZero Integer responseSize
) {
}
