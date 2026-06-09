package com.akamai.miniwsa.repository;

import com.akamai.miniwsa.domain.EnrichedEvent;
import com.akamai.miniwsa.domain.GeoLocation;
import com.akamai.miniwsa.domain.SecurityEvent;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

@Repository
public class ClickHouseEventRepository implements EventRepository {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseEventRepository.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS security_events
            (
                event_id        String,
                received_at     DateTime64(3, 'UTC'),
                timestamp       DateTime64(3, 'UTC'),
                config_id       UInt32,
                policy_id       String,
                client_ip       String,
                hostname        String,
                path            String,
                method          LowCardinality(String),
                status_code     UInt16,
                user_agent      String,
                rule_id         String,
                rule_name       String,
                rule_message    String,
                rule_severity   LowCardinality(String),
                rule_category   LowCardinality(String),
                action          LowCardinality(String),
                geo_country     LowCardinality(String),
                geo_city        String,
                request_size    UInt32,
                response_size   UInt32,
                attack_type     String,
                threat_score    UInt8
            )
            ENGINE = MergeTree()
            PARTITION BY toYYYYMM(timestamp)
            ORDER BY (config_id, timestamp)
            """;

    private static final String INSERT_SQL = """
            INSERT INTO security_events (
                event_id, received_at, timestamp, config_id, policy_id, client_ip,
                hostname, path, method, status_code, user_agent,
                rule_id, rule_name, rule_message, rule_severity, rule_category,
                action, geo_country, geo_city, request_size, response_size,
                attack_type, threat_score
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public ClickHouseEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initSchema() {
        jdbcTemplate.execute(CREATE_TABLE);
        log.info("ClickHouse security_events table ready");
    }

    @Override
    public void insertBatch(List<EnrichedEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                EnrichedEvent e = events.get(i);
                SecurityEvent s = e.original();
                GeoLocation geo = s.geoLocation();
                int idx = 1;
                ps.setString(idx++, s.eventId());
                ps.setTimestamp(idx++, Timestamp.from(e.receivedAt()));
                ps.setTimestamp(idx++, Timestamp.from(s.timestamp()));
                ps.setLong(idx++, s.configId() != null ? s.configId() : 0L);
                ps.setString(idx++, s.policyId());
                ps.setString(idx++, s.clientIp());
                ps.setString(idx++, s.hostname());
                ps.setString(idx++, s.path());
                ps.setString(idx++, s.method());
                ps.setInt(idx++, s.statusCode() != null ? s.statusCode() : 0);
                ps.setString(idx++, s.userAgent() != null ? s.userAgent() : "");
                ps.setString(idx++, s.rule().id());
                ps.setString(idx++, s.rule().name());
                ps.setString(idx++, s.rule().message());
                ps.setString(idx++, s.rule().severity().name());
                ps.setString(idx++, s.rule().category().name());
                ps.setString(idx++, s.action().name());
                ps.setString(idx++, geo != null && geo.country() != null ? geo.country() : "");
                ps.setString(idx++, geo != null && geo.city() != null ? geo.city() : "");
                ps.setInt(idx++, s.requestSize() != null ? s.requestSize() : 0);
                ps.setInt(idx++, s.responseSize() != null ? s.responseSize() : 0);
                ps.setString(idx++, e.attackType());
                ps.setInt(idx, e.threatScore());
            }

            @Override
            public int getBatchSize() {
                return events.size();
            }
        });
        log.info("Inserted {} event(s) into ClickHouse", events.size());
    }
}
