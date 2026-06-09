package com.akamai.miniwsa.service;

import com.akamai.miniwsa.api.dto.IngestionEventStatus;
import com.akamai.miniwsa.api.dto.IngestionResponse;
import com.akamai.miniwsa.api.exception.MalformedIngestionRequestException;
import com.akamai.miniwsa.domain.Action;
import com.akamai.miniwsa.domain.EnrichedEvent;
import com.akamai.miniwsa.domain.GeoLocation;
import com.akamai.miniwsa.domain.Rule;
import com.akamai.miniwsa.domain.RuleCategory;
import com.akamai.miniwsa.domain.SecurityEvent;
import com.akamai.miniwsa.domain.Severity;
import com.akamai.miniwsa.enrichment.EnrichmentPipeline;
import com.akamai.miniwsa.repository.EventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionServiceTest {

    @Mock
    private SecurityEventValidator validator;
    @Mock
    private EnrichmentPipeline enrichmentPipeline;
    @Mock
    private EventRepository eventRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private IngestionService service;

    @BeforeEach
    void setUp() {
        service = new IngestionService(objectMapper, validator, enrichmentPipeline, eventRepository);
    }

    @Test
    void singleValidEventIsAcceptedAndPersisted() {
        ObjectNode event = validEventNode("evt-1");
        when(validator.validate(any())).thenReturn(List.of());
        when(enrichmentPipeline.enrich(any())).thenReturn(enrichedEvent("evt-1"));

        IngestionResponse response = service.ingest(event);

        assertThat(response.accepted()).isEqualTo(1);
        assertThat(response.rejected()).isEqualTo(0);
        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).eventId()).isEqualTo("evt-1");
        assertThat(response.results().get(0).status()).isEqualTo(IngestionEventStatus.ACCEPTED);
        assertThat(response.results().get(0).errors()).isNull();

        verify(eventRepository).insertBatch(List.of(enrichedEvent("evt-1")));
    }

    @Test
    void mixedBatchReportsPerEventResultsAndOnlyPersistsValidOnes() {
        ObjectNode valid = validEventNode("evt-ok");
        ObjectNode invalid = validEventNode("evt-bad");

        ArrayNode batch = objectMapper.createArrayNode();
        batch.add(valid);
        batch.add(invalid);

        when(validator.validate(argThatHasEventId("evt-ok"))).thenReturn(List.of());
        when(validator.validate(argThatHasEventId("evt-bad"))).thenReturn(List.of("path: must not be blank"));
        when(enrichmentPipeline.enrich(any())).thenReturn(enrichedEvent("evt-ok"));

        IngestionResponse response = service.ingest(batch);

        assertThat(response.accepted()).isEqualTo(1);
        assertThat(response.rejected()).isEqualTo(1);
        assertThat(response.results()).hasSize(2);

        IngestionResponse.EventResult okResult = response.results().get(0);
        assertThat(okResult.eventId()).isEqualTo("evt-ok");
        assertThat(okResult.status()).isEqualTo(IngestionEventStatus.ACCEPTED);

        IngestionResponse.EventResult badResult = response.results().get(1);
        assertThat(badResult.eventId()).isEqualTo("evt-bad");
        assertThat(badResult.status()).isEqualTo(IngestionEventStatus.REJECTED);
        assertThat(badResult.errors()).contains("path: must not be blank");

        verify(eventRepository).insertBatch(List.of(enrichedEvent("evt-ok")));
        verify(enrichmentPipeline, never()).enrich(argThat(e -> e.eventId().equals("evt-bad")));
    }

    @Test
    void allInvalidBatchNeverCallsRepository() {
        ObjectNode invalid = validEventNode("evt-bad");
        when(validator.validate(any())).thenReturn(List.of("rule.severity: must not be null"));

        IngestionResponse response = service.ingest(invalid);

        assertThat(response.accepted()).isEqualTo(0);
        assertThat(response.rejected()).isEqualTo(1);
        verify(eventRepository, never()).insertBatch(any());
    }

    @Test
    void malformedEventElementIsRejectedWithoutFailingTheBatch() {
        ObjectNode malformed = objectMapper.createObjectNode();
        malformed.put("eventId", "evt-malformed");
        malformed.put("timestamp", "2026-06-07T10:00:00Z");
        malformed.put("configId", 1);
        malformed.put("policyId", "pol");
        malformed.put("clientIp", "1.2.3.4");
        malformed.put("hostname", "h");
        malformed.put("path", "/p");
        malformed.put("method", "GET");
        malformed.put("statusCode", 200);
        ObjectNode rule = objectMapper.createObjectNode();
        rule.put("id", "1");
        rule.put("name", "n");
        rule.put("message", "m");
        rule.put("severity", "NOT_A_REAL_SEVERITY"); // invalid enum -> JsonProcessingException
        rule.put("category", "BOT");
        malformed.set("rule", rule);
        malformed.put("action", "MONITOR");
        malformed.put("requestSize", 1);
        malformed.put("responseSize", 1);

        IngestionResponse response = service.ingest(malformed);

        assertThat(response.accepted()).isEqualTo(0);
        assertThat(response.rejected()).isEqualTo(1);
        assertThat(response.results().get(0).eventId()).isEqualTo("evt-malformed");
        assertThat(response.results().get(0).status()).isEqualTo(IngestionEventStatus.REJECTED);
        assertThat(response.results().get(0).errors().get(0)).contains("malformed event");
        verify(eventRepository, never()).insertBatch(any());
    }

    @Test
    void nonObjectNonArrayBodyIsARequestLevelFailure() {
        JsonNode scalar = objectMapper.valueToTree("just a string");

        assertThatThrownBy(() -> service.ingest(scalar))
                .isInstanceOf(MalformedIngestionRequestException.class);
        verify(eventRepository, never()).insertBatch(any());
    }

    @Test
    void emptyArrayBodyIsARequestLevelFailure() {
        ArrayNode empty = objectMapper.createArrayNode();

        assertThatThrownBy(() -> service.ingest(empty))
                .isInstanceOf(MalformedIngestionRequestException.class);
    }

    private ObjectNode validEventNode(String eventId) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("eventId", eventId);
        node.put("timestamp", "2026-06-07T10:00:00Z");
        node.put("configId", 14227);
        node.put("policyId", "pol_web1");
        node.put("clientIp", "203.0.113.42");
        node.put("hostname", "www.example.com");
        node.put("path", "/api/v1/login");
        node.put("method", "POST");
        node.put("statusCode", 403);
        node.put("userAgent", "test-agent");
        ObjectNode rule = objectMapper.createObjectNode();
        rule.put("id", "950001");
        rule.put("name", "SQL_INJECTION");
        rule.put("message", "SQL Injection Attack Detected");
        rule.put("severity", "CRITICAL");
        rule.put("category", "INJECTION");
        node.set("rule", rule);
        node.put("action", "DENY");
        ObjectNode geo = objectMapper.createObjectNode();
        geo.put("country", "CN");
        geo.put("city", "Beijing");
        node.set("geoLocation", geo);
        node.put("requestSize", 1024);
        node.put("responseSize", 256);
        return node;
    }

    private com.akamai.miniwsa.api.dto.SecurityEventRequest argThatHasEventId(String eventId) {
        return org.mockito.ArgumentMatchers.argThat(req -> req != null && req.eventId().equals(eventId));
    }

    private SecurityEvent argThat(java.util.function.Predicate<SecurityEvent> predicate) {
        return org.mockito.ArgumentMatchers.argThat(predicate::test);
    }

    private EnrichedEvent enrichedEvent(String eventId) {
        Rule rule = new Rule("950001", "SQL_INJECTION", "SQL Injection Attack Detected", Severity.CRITICAL, RuleCategory.INJECTION);
        SecurityEvent securityEvent = new SecurityEvent(
                eventId, Instant.parse("2026-06-07T10:00:00Z"), 14227L, "pol_web1",
                "203.0.113.42", "www.example.com", "/api/v1/login", "POST", 403, "test-agent",
                rule, Action.DENY, new GeoLocation("CN", "Beijing"), 1024, 256
        );
        return new EnrichedEvent(securityEvent, "SQL/Command Injection", 75, Instant.parse("2026-06-07T10:00:01Z"));
    }
}
