package com.akamai.miniwsa.api.dto;

import java.util.List;

/**
 * Response body for {@code POST /v1/events/ingest}.
 *
 * Always returned with {@code 201 Created} as long as the request body could be parsed
 * into candidate event(s) — even when some/all individual events are rejected. Per-event
 * outcomes are reported in {@code results} so callers can tell exactly which events landed.
 */
public record IngestionResponse(
        int accepted,
        int rejected,
        List<EventResult> results
) {
    public record EventResult(
            String eventId,
            IngestionEventStatus status,
            List<String> errors
    ) {
        public static EventResult accepted(String eventId) {
            return new EventResult(eventId, IngestionEventStatus.ACCEPTED, null);
        }

        public static EventResult rejected(String eventId, List<String> errors) {
            return new EventResult(eventId, IngestionEventStatus.REJECTED, errors);
        }
    }
}
