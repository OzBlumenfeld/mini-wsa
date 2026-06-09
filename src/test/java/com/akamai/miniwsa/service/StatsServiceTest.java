package com.akamai.miniwsa.service;

import com.akamai.miniwsa.api.dto.stats.AttackerStats;
import com.akamai.miniwsa.api.dto.stats.CategoryStats;
import com.akamai.miniwsa.api.dto.stats.PathStats;
import com.akamai.miniwsa.api.dto.stats.StatsSummaryResponse;
import com.akamai.miniwsa.repository.StatsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatsServiceTest {

    @Mock
    private StatsRepository statsRepository;

    private StatsService statsService;

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-12-31T23:59:59Z");

    @BeforeEach
    void setUp() {
        statsService = new StatsService(statsRepository);
    }

    @Test
    void totalEventsSumsAllCategoryCounts() {
        when(statsRepository.byCategoryStats(14227L, FROM, TO)).thenReturn(Map.of(
                "INJECTION", new CategoryStats(450, 72.3),
                "BOT", new CategoryStats(380, 45.1)
        ));
        when(statsRepository.byActionStats(14227L, FROM, TO)).thenReturn(Map.of("DENY", 830L));
        when(statsRepository.topAttackers(14227L, FROM, TO)).thenReturn(List.of());
        when(statsRepository.topTargetedPaths(14227L, FROM, TO)).thenReturn(List.of());

        StatsSummaryResponse result = statsService.getSummary(14227L, FROM, TO);

        assertThat(result.totalEvents()).isEqualTo(830L); // 450 + 380
        assertThat(result.configId()).isEqualTo(14227L);
        assertThat(result.timeRange().from()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(result.timeRange().to()).isEqualTo("2026-12-31T23:59:59Z");
    }

    @Test
    void nullConfigIdIsPassedThroughToRepository() {
        when(statsRepository.byCategoryStats(null, FROM, TO)).thenReturn(Map.of());
        when(statsRepository.byActionStats(null, FROM, TO)).thenReturn(Map.of());
        when(statsRepository.topAttackers(null, FROM, TO)).thenReturn(List.of());
        when(statsRepository.topTargetedPaths(null, FROM, TO)).thenReturn(List.of());

        StatsSummaryResponse result = statsService.getSummary(null, FROM, TO);

        assertThat(result.configId()).isNull();
        assertThat(result.totalEvents()).isZero();
    }

    @Test
    void aggregationFieldsArePassedThroughFromRepository() {
        var attackers = List.of(new AttackerStats("1.2.3.4", 10, 55.0));
        var paths = List.of(new PathStats("/admin", 20));
        when(statsRepository.byCategoryStats(null, FROM, TO)).thenReturn(Map.of());
        when(statsRepository.byActionStats(null, FROM, TO)).thenReturn(Map.of("ALERT", 5L));
        when(statsRepository.topAttackers(null, FROM, TO)).thenReturn(attackers);
        when(statsRepository.topTargetedPaths(null, FROM, TO)).thenReturn(paths);

        StatsSummaryResponse result = statsService.getSummary(null, FROM, TO);

        assertThat(result.byAction()).containsEntry("ALERT", 5L);
        assertThat(result.topAttackers()).isEqualTo(attackers);
        assertThat(result.topTargetedPaths()).isEqualTo(paths);
    }
}
