package com.akamai.miniwsa.repository;

import com.akamai.miniwsa.api.dto.stats.AttackerStats;
import com.akamai.miniwsa.api.dto.stats.CategoryStats;
import com.akamai.miniwsa.api.dto.stats.PathStats;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface StatsRepository {

    Map<String, CategoryStats> byCategoryStats(Long configId, Instant from, Instant to);

    Map<String, Long> byActionStats(Long configId, Instant from, Instant to);

    List<AttackerStats> topAttackers(Long configId, Instant from, Instant to);

    List<PathStats> topTargetedPaths(Long configId, Instant from, Instant to);
}
