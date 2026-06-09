package com.akamai.miniwsa.service;

import com.akamai.miniwsa.api.dto.GeoLocationRequest;
import com.akamai.miniwsa.api.dto.IngestionResponse;
import com.akamai.miniwsa.api.dto.IngestionResponse.EventResult;
import com.akamai.miniwsa.api.dto.RuleRequest;
import com.akamai.miniwsa.api.dto.SecurityEventRequest;
import com.akamai.miniwsa.api.exception.MalformedIngestionRequestException;
import com.akamai.miniwsa.domain.EnrichedEvent;
import com.akamai.miniwsa.domain.GeoLocation;
import com.akamai.miniwsa.domain.Rule;
import com.akamai.miniwsa.domain.SecurityEvent;
import com.akamai.miniwsa.enrichment.EnrichmentPipeline;
import com.akamai.miniwsa.repository.EventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Orchestrates the ingestion flow: normalize the request body (single object or array),
 * validate and enrich each event independently, persist the accepted ones in a single
 * batch, and assemble a per-eventId result list.
 *
 * Events are processed sequentially — required both for correct repeat-offender counting
 * within a batch (see RepeatOffenderCache) and to keep results in input order.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final ObjectMapper objectMapper;
    private final SecurityEventValidator validator;
    private final EnrichmentPipeline enrichmentPipeline;
    private final EventRepository eventRepository;

    public IngestionService(ObjectMapper objectMapper,
                            SecurityEventValidator validator,
                            EnrichmentPipeline enrichmentPipeline,
                            EventRepository eventRepository) {
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.enrichmentPipeline = enrichmentPipeline;
        this.eventRepository = eventRepository;
    }

    public IngestionResponse ingest(JsonNode body) {
        List<JsonNode> elements = normalize(body);
        log.info("Ingestion request received: {} candidate event(s)", elements.size());

        List<EventResult> results = new ArrayList<>(elements.size());
        List<EnrichedEvent> toStore = new ArrayList<>(elements.size());
        int acceptedCount = 0;

        for (JsonNode element : elements) {
            String probableEventId = element.path("eventId").asText(null);

            SecurityEventRequest request;
            try {
                request = objectMapper.treeToValue(element, SecurityEventRequest.class);
            } catch (JsonProcessingException e) {
                log.warn("Event rejected (malformed): eventId={} reason='{}'", probableEventId, e.getOriginalMessage());
                results.add(EventResult.rejected(probableEventId, List.of("malformed event: " + e.getOriginalMessage())));
                continue;
            }

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

    /**
     * Normalizes the request body into a list of candidate event nodes. A single JSON
     * object becomes a singleton list; a JSON array is streamed element by element.
     * Anything else (scalar, null, missing body) is a request-level parse failure.
     */
    private List<JsonNode> normalize(JsonNode body) {
        if (body == null || body.isMissingNode() || body.isNull()) {
            throw new MalformedIngestionRequestException(
                    "Request body must be a single event object or an array of event objects");
        }
        if (body.isObject()) {
            return List.of(body);
        }
        if (body.isArray()) {
            if (body.isEmpty()) {
                throw new MalformedIngestionRequestException("Request body array must not be empty");
            }
            List<JsonNode> elements = new ArrayList<>(body.size());
            for (Iterator<JsonNode> it = body.elements(); it.hasNext(); ) {
                elements.add(it.next());
            }
            return elements;
        }
        throw new MalformedIngestionRequestException(
                "Request body must be a single event object or an array of event objects");
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
