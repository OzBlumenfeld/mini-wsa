package com.akamai.miniwsa.api.controller;

import com.akamai.miniwsa.api.dto.stats.AttackerStats;
import com.akamai.miniwsa.api.dto.stats.CategoryStats;
import com.akamai.miniwsa.api.dto.stats.PathStats;
import com.akamai.miniwsa.api.dto.stats.StatsSummaryResponse;
import com.akamai.miniwsa.api.dto.stats.StatsSummaryResponse.TimeRange;
import com.akamai.miniwsa.service.StatsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StatsController.class)
class StatsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private StatsService statsService;

    @Test
    void validRequestReturns200WithSummary() throws Exception {
        var response = new StatsSummaryResponse(
                14227L,
                new TimeRange("2026-01-01T00:00:00Z", "2026-12-31T23:59:59Z"),
                1523L,
                Map.of("INJECTION", new CategoryStats(450, 72.3)),
                Map.of("DENY", 890L),
                List.of(new AttackerStats("203.0.113.42", 87, 81.2)),
                List.of(new PathStats("/api/v1/login", 234))
        );
        when(statsService.getSummary(any(), any(), any())).thenReturn(response);

        mockMvc.perform(get("/v1/stats/summary")
                        .param("configId", "14227")
                        .param("from", "2026-01-01T00:00:00Z")
                        .param("to", "2026-12-31T23:59:59Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configId").value(14227))
                .andExpect(jsonPath("$.totalEvents").value(1523))
                .andExpect(jsonPath("$.byCategory.INJECTION.count").value(450))
                .andExpect(jsonPath("$.byCategory.INJECTION.avgThreatScore").value(72.3))
                .andExpect(jsonPath("$.byAction.DENY").value(890))
                .andExpect(jsonPath("$.topAttackers[0].clientIp").value("203.0.113.42"))
                .andExpect(jsonPath("$.topAttackers[0].count").value(87))
                .andExpect(jsonPath("$.topTargetedPaths[0].path").value("/api/v1/login"))
                .andExpect(jsonPath("$.topTargetedPaths[0].count").value(234));
    }

    @Test
    void omittedConfigIdPassesNullToService() throws Exception {
        when(statsService.getSummary(isNull(), any(), any())).thenReturn(
                new StatsSummaryResponse(null, new TimeRange("2026-01-01T00:00:00Z", "2026-12-31T23:59:59Z"),
                        0L, Map.of(), Map.of(), List.of(), List.of()));

        mockMvc.perform(get("/v1/stats/summary")
                        .param("from", "2026-01-01T00:00:00Z")
                        .param("to", "2026-12-31T23:59:59Z"))
                .andExpect(status().isOk());

        verify(statsService).getSummary(null,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-12-31T23:59:59Z"));
    }

    @Test
    void missingFromReturns400() throws Exception {
        mockMvc.perform(get("/v1/stats/summary")
                        .param("to", "2026-12-31T23:59:59Z"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingToReturns400() throws Exception {
        mockMvc.perform(get("/v1/stats/summary")
                        .param("from", "2026-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }
}
