package com.akamai.miniwsa.service;

import com.akamai.miniwsa.api.dto.samples.EnrichedEventResponse;
import com.akamai.miniwsa.api.dto.samples.SamplesResponse;
import com.akamai.miniwsa.repository.SamplesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SamplesServiceTest {

    @Mock
    private SamplesRepository samplesRepository;

    private SamplesService samplesService;

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-12-31T23:59:59Z");

    @BeforeEach
    void setUp() {
        samplesService = new SamplesService(samplesRepository);
    }

    @Test
    void returnsTotalAndEventsFromRepository() {
        var event = sampleEvent("evt-1");
        when(samplesRepository.countSamples(null, FROM, TO, null, null)).thenReturn(42L);
        when(samplesRepository.querySamples(null, FROM, TO, null, null, 20, 0))
                .thenReturn(List.of(event));

        SamplesResponse result = samplesService.getSamples(null, FROM, TO, null, null, 20, 0);

        assertThat(result.total()).isEqualTo(42L);
        assertThat(result.events()).hasSize(1);
        assertThat(result.events().get(0).eventId()).isEqualTo("evt-1");
    }

    @Test
    void filtersAreForwardedToRepository() {
        when(samplesRepository.countSamples(14227L, FROM, TO, "INJECTION", "DENY")).thenReturn(3L);
        when(samplesRepository.querySamples(14227L, FROM, TO, "INJECTION", "DENY", 10, 5))
                .thenReturn(List.of());

        SamplesResponse result = samplesService.getSamples(14227L, FROM, TO, "INJECTION", "DENY", 10, 5);

        assertThat(result.total()).isEqualTo(3L);
        assertThat(result.events()).isEmpty();
    }

    private EnrichedEventResponse sampleEvent(String eventId) {
        return new EnrichedEventResponse(
                eventId, Instant.parse("2026-06-01T10:00:00Z"), 14227L, "pol_web1",
                "203.0.113.42", "www.example.com", "/api/v1/login", "POST", 403,
                "test-agent", "950001", "SQL_INJECTION", "SQL Injection Attack Detected",
                "CRITICAL", "INJECTION", "DENY", "CN", "Beijing", 1024, 256,
                "SQL/Command Injection", 75);
    }
}
