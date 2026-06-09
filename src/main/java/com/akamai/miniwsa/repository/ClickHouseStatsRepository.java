package com.akamai.miniwsa.repository;

import com.akamai.miniwsa.api.dto.stats.AttackerStats;
import com.akamai.miniwsa.api.dto.stats.CategoryStats;
import com.akamai.miniwsa.api.dto.stats.PathStats;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ClickHouseStatsRepository implements StatsRepository {

    private static final String BY_CATEGORY_SQL = """
            SELECT rule_category, count(*) AS cnt, avg(threat_score) AS avg_score
            FROM security_events
            WHERE timestamp >= ? AND timestamp <= ?
            """;

    private static final String BY_ACTION_SQL = """
            SELECT action, count(*) AS cnt
            FROM security_events
            WHERE timestamp >= ? AND timestamp <= ?
            """;

    private static final String TOP_ATTACKERS_SQL = """
            SELECT client_ip, count(*) AS cnt, avg(threat_score) AS avg_score
            FROM security_events
            WHERE timestamp >= ? AND timestamp <= ?
            """;

    private static final String TOP_PATHS_SQL = """
            SELECT path, count(*) AS cnt
            FROM security_events
            WHERE timestamp >= ? AND timestamp <= ?
            """;

    private static final String CONFIG_FILTER = " AND config_id = ?";
    private static final String GROUP_BY_CATEGORY = " GROUP BY rule_category";
    private static final String GROUP_BY_ACTION = " GROUP BY action";
    private static final String GROUP_BY_CLIENT = " GROUP BY client_ip ORDER BY cnt DESC LIMIT 10";
    private static final String GROUP_BY_PATH = " GROUP BY path ORDER BY cnt DESC LIMIT 10";

    private final JdbcTemplate jdbcTemplate;

    public ClickHouseStatsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<String, CategoryStats> byCategoryStats(Long configId, Instant from, Instant to) {
        var params = baseParams(from, to);
        var sql = new StringBuilder(BY_CATEGORY_SQL);
        appendConfigFilter(sql, params, configId);
        sql.append(GROUP_BY_CATEGORY);

        Map<String, CategoryStats> result = new LinkedHashMap<>();
        jdbcTemplate.query(sql.toString(), rs -> {
            result.put(rs.getString("rule_category"),
                    new CategoryStats(rs.getLong("cnt"), rs.getDouble("avg_score")));
        }, params.toArray());
        return result;
    }

    @Override
    public Map<String, Long> byActionStats(Long configId, Instant from, Instant to) {
        var params = baseParams(from, to);
        var sql = new StringBuilder(BY_ACTION_SQL);
        appendConfigFilter(sql, params, configId);
        sql.append(GROUP_BY_ACTION);

        Map<String, Long> result = new LinkedHashMap<>();
        jdbcTemplate.query(sql.toString(), rs -> {
            result.put(rs.getString("action"), rs.getLong("cnt"));
        }, params.toArray());
        return result;
    }

    @Override
    public List<AttackerStats> topAttackers(Long configId, Instant from, Instant to) {
        var params = baseParams(from, to);
        var sql = new StringBuilder(TOP_ATTACKERS_SQL);
        appendConfigFilter(sql, params, configId);
        sql.append(GROUP_BY_CLIENT);

        return jdbcTemplate.query(sql.toString(),
                (rs, rowNum) -> new AttackerStats(
                        rs.getString("client_ip"),
                        rs.getLong("cnt"),
                        rs.getDouble("avg_score")),
                params.toArray());
    }

    @Override
    public List<PathStats> topTargetedPaths(Long configId, Instant from, Instant to) {
        var params = baseParams(from, to);
        var sql = new StringBuilder(TOP_PATHS_SQL);
        appendConfigFilter(sql, params, configId);
        sql.append(GROUP_BY_PATH);

        return jdbcTemplate.query(sql.toString(),
                (rs, rowNum) -> new PathStats(rs.getString("path"), rs.getLong("cnt")),
                params.toArray());
    }

    private static final DateTimeFormatter CH_TS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private List<Object> baseParams(Instant from, Instant to) {
        List<Object> params = new ArrayList<>();
        params.add(CH_TS.format(from));
        params.add(CH_TS.format(to));
        return params;
    }

    private void appendConfigFilter(StringBuilder sql, List<Object> params, Long configId) {
        if (configId != null) {
            sql.append(CONFIG_FILTER);
            params.add(configId);
        }
    }
}
