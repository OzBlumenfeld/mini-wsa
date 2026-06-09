package com.akamai.miniwsa.api.dto.stats;

import java.util.List;
import java.util.Map;

public record StatsSummaryResponse(
        Long configId,
        TimeRange timeRange,
        long totalEvents,
        Map<String, CategoryStats> byCategory,
        Map<String, Long> byAction,
        List<AttackerStats> topAttackers,
        List<PathStats> topTargetedPaths
) {
    public record TimeRange(String from, String to) {}
}
