package com.akamai.miniwsa.service;

import com.akamai.miniwsa.api.dto.GeoLocationRequest;
import com.akamai.miniwsa.api.dto.IngestionEventStatus;
import com.akamai.miniwsa.api.dto.IngestionResponse;
import com.akamai.miniwsa.api.dto.RuleRequest;
import com.akamai.miniwsa.api.dto.SecurityEventRequest;
import com.akamai.miniwsa.domain.Action;
import com.akamai.miniwsa.domain.EnrichedEvent;
import com.akamai.miniwsa.domain.GeoLocation;
import com.akamai.miniwsa.domain.Rule;
import com.akamai.miniwsa.domain.RuleCategory;
import com.akamai.miniwsa.domain.SecurityEvent;
import com.akamai.miniwsa.domain.Severity;
import com.akamai.miniwsa.enrichment.EnrichmentPipeline;
import com.akamai.miniwsa.repository.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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

    private IngestionService service;

    @BeforeEach
    void setUp() {
        service = new IngestionService(validator, enrichmentPipeline, eventRepository);
    }

    @Test
    void singleValidEventIsAcceptedAndPersisted() {
        when(validator.validate(any())).thenReturn(List.of());
        when(enrichmentPipeline.enrich(any())).thenReturn(enrichedEvent("evt-1"));

        IngestionResponse response = service.ingest(List.of(validRequest("evt-1")));

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
        when(validator.validate(argThatHasEventId("evt-ok"))).thenReturn(List.of());
        when(validator.validate(argThatHasEventId("evt-bad"))).thenReturn(List.of("path: must not be blank"));
        when(enrichmentPipeline.enrich(any())).thenReturn(enrichedEvent("evt-ok"));

        IngestionResponse response = service.ingest(List.of(validRequest("evt-ok"), validRequest("evt-bad")));

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
        when(validator.validate(any())).thenReturn(List.of("rule.severity: must not be null"));

        IngestionResponse response = service.ingest(List.of(validRequest("evt-bad")));

        assertThat(response.accepted()).isEqualTo(0);
        assertThat(response.rejected()).isEqualTo(1);
        verify(eventRepository, never()).insertBatch(any());
    }

    private SecurityEventRequest validRequest(String eventId) {
        RuleRequest rule = new RuleRequest("950001", "SQL_INJECTION", "SQL Injection Attack Detected",
                Severity.CRITICAL, RuleCategory.INJECTION);
        GeoLocationRequest geo = new GeoLocationRequest("CN", "Beijing");
        return new SecurityEventRequest(
                eventId,
                "2026-06-07T10:00:00Z",
                14227L,
                "pol_web1",
                "203.0.113.42",
                "www.example.com",
                "/api/v1/login",
                "POST",
                403,
                "test-agent",
                rule,
                Action.DENY,
                geo,
                1024,
                256
        );
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
