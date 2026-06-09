package com.akamai.miniwsa.api.controller;

import com.akamai.miniwsa.api.dto.samples.EnrichedEventResponse;
import com.akamai.miniwsa.api.dto.samples.SamplesResponse;
import com.akamai.miniwsa.service.SamplesService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SamplesController.class)
class SamplesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SamplesService samplesService;

    @Test
    void validRequestReturns200WithEvents() throws Exception {
        var event = new EnrichedEventResponse(
                "evt-1", Instant.parse("2026-06-01T10:00:00Z"), 14227L, "pol_web1",
                "203.0.113.42", "www.example.com", "/api/v1/login", "POST", 403,
                "test-agent", "950001", "SQL_INJECTION", "SQL Injection Attack Detected",
                "CRITICAL", "INJECTION", "DENY", "CN", "Beijing", 1024, 256,
                "SQL/Command Injection", 75);
        when(samplesService.getSamples(any(), any(), any(), any(), any(), eq(5), eq(0)))
                .thenReturn(new SamplesResponse(1L, List.of(event)));

        mockMvc.perform(get("/v1/events/samples")
                        .param("from", "2026-01-01T00:00:00Z")
                        .param("to", "2026-12-31T23:59:59Z")
                        .param("limit", "5")
                        .param("offset", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.events[0].eventId").value("evt-1"))
                .andExpect(jsonPath("$.events[0].clientIp").value("203.0.113.42"))
                .andExpect(jsonPath("$.events[0].attackType").value("SQL/Command Injection"))
                .andExpect(jsonPath("$.events[0].threatScore").value(75));
    }

    @Test
    void defaultLimitAndOffsetApplied() throws Exception {
        when(samplesService.getSamples(isNull(), any(), any(), isNull(), isNull(), eq(20), eq(0)))
                .thenReturn(new SamplesResponse(0L, List.of()));

        mockMvc.perform(get("/v1/events/samples")
                        .param("from", "2026-01-01T00:00:00Z")
                        .param("to", "2026-12-31T23:59:59Z"))
                .andExpect(status().isOk());

        verify(samplesService).getSamples(null,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-12-31T23:59:59Z"),
                null, null, 20, 0);
    }

    @Test
    void missingFromReturns400() throws Exception {
        mockMvc.perform(get("/v1/events/samples")
                        .param("to", "2026-12-31T23:59:59Z"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void limitAbove100Returns400() throws Exception {
        mockMvc.perform(get("/v1/events/samples")
                        .param("from", "2026-01-01T00:00:00Z")
                        .param("to", "2026-12-31T23:59:59Z")
                        .param("limit", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void limitBelowOneReturns400() throws Exception {
        mockMvc.perform(get("/v1/events/samples")
                        .param("from", "2026-01-01T00:00:00Z")
                        .param("to", "2026-12-31T23:59:59Z")
                        .param("limit", "0"))
                .andExpect(status().isBadRequest());
    }
}
