package com.akamai.miniwsa.service;

import com.akamai.miniwsa.api.dto.stats.AttackerStats;
import com.akamai.miniwsa.api.dto.stats.CategoryStats;
import com.akamai.miniwsa.api.dto.stats.PathStats;
import com.akamai.miniwsa.api.dto.stats.StatsSummaryResponse;
import com.akamai.miniwsa.api.dto.stats.StatsSummaryResponse.TimeRange;
import com.akamai.miniwsa.repository.StatsRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class StatsService {

    private final StatsRepository statsRepository;

    public StatsService(StatsRepository statsRepository) {
        this.statsRepository = statsRepository;
    }

    public StatsSummaryResponse getSummary(Long configId, Instant from, Instant to) {
        Map<String, CategoryStats> byCategory = statsRepository.byCategoryStats(configId, from, to);
        Map<String, Long> byAction = statsRepository.byActionStats(configId, from, to);
        List<AttackerStats> topAttackers = statsRepository.topAttackers(configId, from, to);
        List<PathStats> topTargetedPaths = statsRepository.topTargetedPaths(configId, from, to);

        long totalEvents = byCategory.values().stream().mapToLong(CategoryStats::count).sum();

        return new StatsSummaryResponse(
                configId,
                new TimeRange(from.toString(), to.toString()),
                totalEvents,
                byCategory,
                byAction,
                topAttackers,
                topTargetedPaths
        );
    }
}
