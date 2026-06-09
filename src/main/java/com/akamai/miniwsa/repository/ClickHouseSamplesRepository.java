package com.akamai.miniwsa.repository;

import com.akamai.miniwsa.api.dto.samples.EnrichedEventResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Repository
public class ClickHouseSamplesRepository implements SamplesRepository {

    private static final RowMapper<EnrichedEventResponse> EVENT_ROW_MAPPER = (rs, rowNum) ->
            new EnrichedEventResponse(
                    rs.getString("event_id"),
                    rs.getTimestamp("timestamp").toInstant(),
                    rs.getLong("config_id"),
                    rs.getString("policy_id"),
                    rs.getString("client_ip"),
                    rs.getString("hostname"),
                    rs.getString("path"),
                    rs.getString("method"),
                    rs.getInt("status_code"),
                    rs.getString("user_agent"),
                    rs.getString("rule_id"),
                    rs.getString("rule_name"),
                    rs.getString("rule_message"),
                    rs.getString("rule_severity"),
                    rs.getString("rule_category"),
                    rs.getString("action"),
                    rs.getString("geo_country"),
                    rs.getString("geo_city"),
                    rs.getInt("request_size"),
                    rs.getInt("response_size"),
                    rs.getString("attack_type"),
                    rs.getInt("threat_score")
            );

    private final JdbcTemplate jdbcTemplate;

    public ClickHouseSamplesRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<EnrichedEventResponse> querySamples(Long configId, Instant from, Instant to,
                                                     String category, String action,
                                                     int limit, int offset) {
        var params = new ArrayList<>();
        var sql = new StringBuilder("SELECT * FROM security_events WHERE 1=1");
        appendAllFilters(sql, params, from, to, configId, category, action);
        sql.append(" ORDER BY timestamp DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        return jdbcTemplate.query(sql.toString(), EVENT_ROW_MAPPER, params.toArray());
    }

    @Override
    public long countSamples(Long configId, Instant from, Instant to,
                              String category, String action) {
        var params = new ArrayList<>();
        var sql = new StringBuilder("SELECT count(*) FROM security_events WHERE 1=1");
        appendAllFilters(sql, params, from, to, configId, category, action);

        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, params.toArray());
        return count != null ? count : 0L;
    }

    private static final DateTimeFormatter CH_TS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private static String toClickHouseTs(Instant instant) {
        return CH_TS.format(instant);
    }

    private void appendAllFilters(StringBuilder sql, List<Object> params,
                                   Instant from, Instant to,
                                   Long configId, String category, String action) {
        if (from != null) {
            sql.append(" AND timestamp >= ?");
            params.add(toClickHouseTs(from));
        }
        if (to != null) {
            sql.append(" AND timestamp <= ?");
            params.add(toClickHouseTs(to));
        }
        if (configId != null) {
            sql.append(" AND config_id = ?");
            params.add(configId);
        }
        if (category != null && !category.isBlank()) {
            sql.append(" AND rule_category = ?");
            params.add(category);
        }
        if (action != null && !action.isBlank()) {
            sql.append(" AND action = ?");
            params.add(action);
        }
    }
}
