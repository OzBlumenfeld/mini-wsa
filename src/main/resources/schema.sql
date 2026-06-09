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
ORDER BY (config_id, timestamp);
