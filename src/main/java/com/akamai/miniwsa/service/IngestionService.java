package com.akamai.miniwsa.service;

import com.akamai.miniwsa.api.dto.GeoLocationRequest;
import com.akamai.miniwsa.api.dto.IngestionResponse;
import com.akamai.miniwsa.api.dto.IngestionResponse.EventResult;
import com.akamai.miniwsa.api.dto.RuleRequest;
import com.akamai.miniwsa.api.dto.SecurityEventRequest;
import com.akamai.miniwsa.domain.EnrichedEvent;
import com.akamai.miniwsa.domain.GeoLocation;
import com.akamai.miniwsa.domain.Rule;
import com.akamai.miniwsa.domain.SecurityEvent;
import com.akamai.miniwsa.enrichment.EnrichmentPipeline;
import com.akamai.miniwsa.repository.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the ingestion flow: validate and enrich each event independently,
 * persist the accepted ones in a single batch, and assemble a per-eventId result list.
 *
 * Events are processed sequentially — required both for correct repeat-offender counting
 * within a batch (see RepeatOffenderCache) and to keep results in input order.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final SecurityEventValidator validator;
    private final EnrichmentPipeline enrichmentPipeline;
    private final EventRepository eventRepository;

    public IngestionService(SecurityEventValidator validator,
                            EnrichmentPipeline enrichmentPipeline,
                            EventRepository eventRepository) {
        this.validator = validator;
        this.enrichmentPipeline = enrichmentPipeline;
        this.eventRepository = eventRepository;
    }

    public IngestionResponse ingest(List<SecurityEventRequest> events) {
        log.info("Ingestion request received: {} candidate event(s)", events.size());

        List<EventResult> results = new ArrayList<>(events.size());
        List<EnrichedEvent> toStore = new ArrayList<>(events.size());
        int acceptedCount = 0;

        for (SecurityEventRequest request : events) {
            List<String> errors = new ArrayList<>(validator.validate(request));
            Instant timestamp = parseTimestamp(request.timestamp(), errors);

            if (!errors.isEmpty()) {
                log.warn("Event rejected: eventId={} errors={}", request.eventId(), errors);
                results.add(EventResult.rejected(request.eventId(), errors));
                continue;
            }

            SecurityEvent domainEvent = toDomainEvent(request, timestamp);
            EnrichedEvent enriched = enrichmentPipeline.enrich(domainEvent);
            toStore.add(enriched);
            results.add(EventResult.accepted(domainEvent.eventId()));
            acceptedCount++;
        }

        if (!toStore.isEmpty()) {
            eventRepository.insertBatch(toStore);
        }

        log.info("Ingestion complete: accepted={} rejected={}", acceptedCount, results.size() - acceptedCount);
        return new IngestionResponse(acceptedCount, results.size() - acceptedCount, results);
    }

    private Instant parseTimestamp(String rawTimestamp, List<String> errorsAccumulator) {
        if (rawTimestamp == null) {
            return null;
        }
        try {
            return Instant.parse(rawTimestamp);
        } catch (DateTimeParseException e) {
            errorsAccumulator.add("timestamp: must be a valid ISO-8601 instant");
            return null;
        }
    }

    private SecurityEvent toDomainEvent(SecurityEventRequest request, Instant timestamp) {
        RuleRequest ruleRequest = request.rule();
        Rule rule = new Rule(ruleRequest.id(), ruleRequest.name(), ruleRequest.message(),
                ruleRequest.severity(), ruleRequest.category());

        GeoLocationRequest geoRequest = request.geoLocation();
        GeoLocation geoLocation = (geoRequest == null) ? null : new GeoLocation(geoRequest.country(), geoRequest.city());

        return new SecurityEvent(
                request.eventId(),
                timestamp,
                request.configId(),
                request.policyId(),
                request.clientIp(),
                request.hostname(),
                request.path(),
                request.method(),
                request.statusCode(),
                request.userAgent(),
                rule,
                request.action(),
                geoLocation,
                request.requestSize(),
                request.responseSize()
        );
    }
}
