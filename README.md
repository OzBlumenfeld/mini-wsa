# Mini WSA

A simplified **Web Security Analytics** pipeline, modeled after Akamai's WSA platform. It ingests raw security events, enriches them in real time (attack classification + threat scoring), persists them to ClickHouse, and exposes REST APIs for analytics and event sampling.

See [REQUIREMENTS.md](REQUIREMENTS.md) for the full assignment spec, and [DESIGN.md](DESIGN.md) for architecture decisions and trade-offs.

---

## How to Build and Run

### Prerequisites

- Java 21+
- Maven (or use the included `./mvnw` wrapper)
- Docker + Docker Compose

### 1. Start dependencies

```bash
docker compose up -d
```

This brings up:
- **ClickHouse** on ports `8123` (HTTP) and `9000` (native) — schema is auto-applied from [src/main/resources/schema.sql](src/main/resources/schema.sql)
- **Redis** on port `6379`

### 2. Run the application

```bash
./mvnw spring-boot:run
```

The app starts on `http://localhost:8080`.

### 3. Run tests

```bash
./mvnw test
```

---

## API Reference

### POST /v1/events/ingest

Accepts a single event object or a JSON array of events.

```bash
curl -X POST http://localhost:8080/v1/events/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-001",
    "timestamp": "2026-05-20T14:32:10Z",
    "configId": 14227,
    "policyId": "pol_web1",
    "clientIp": "203.0.113.42",
    "hostname": "www.example.com",
    "path": "/api/v1/login",
    "method": "POST",
    "statusCode": 403,
    "userAgent": "Mozilla/5.0",
    "rule": {
      "id": "950001",
      "name": "SQL_INJECTION",
      "message": "SQL Injection Attack Detected",
      "severity": "CRITICAL",
      "category": "INJECTION"
    },
    "action": "DENY",
    "geoLocation": { "country": "CN", "city": "Beijing" },
    "requestSize": 1024,
    "responseSize": 256
  }'
```

**Response `201 Created`:**
```json
{
  "accepted": 1,
  "rejected": 0,
  "results": [
    { "eventId": "evt-001", "status": "accepted" }
  ]
}
```

Returns `201` even when individual events are rejected — per-event status is in `results`. Returns `400` only when the request body itself cannot be parsed.

### GET /v1/stats/summary

```bash
curl "http://localhost:8080/v1/stats/summary?configId=14227&from=2026-05-20T00:00:00Z&to=2026-05-21T00:00:00Z"
```

`configId` is optional — omit to aggregate across all configs.

### GET /v1/events/samples

```bash
curl "http://localhost:8080/v1/events/samples?configId=14227&category=INJECTION&limit=20&offset=0"
```

All parameters optional. `limit` defaults to 20, max 100. Results sorted by `timestamp` descending.

---

## Architecture Overview

```
  REST Client
      │
      ├── POST /v1/events/ingest ──► IngestionController
      │                                     │
      │                               IngestionService
      │                                     │
      │                           (per event) EnrichmentPipeline
      │                             ├── AttackClassifier
      │                             └── ThreatScoreCalculator
      │                                     └── RepeatOffenderCache (Redis)
      │                                     │
      │                           ClickHouseEventRepository (bulk INSERT)
      │
      ├── GET /v1/stats/summary ───► StatsController → StatsService → StatsRepository
      │
      └── GET /v1/events/samples ──► SamplesController → SamplesService → EventRepository
```

See [DESIGN.md](DESIGN.md) for the full architecture diagram, data flow, and package structure.

---

## Key Source Files

### Entry Points

| File | Role |
|---|---|
| [MiniWsaApplication.java](src/main/java/com/akamai/miniwsa/MiniWsaApplication.java) | Spring Boot main class |
| [src/main/resources/application.yml](src/main/resources/application.yml) | App config — ports, ClickHouse URL, Redis, repeat-offender tuning |
| [src/main/resources/schema.sql](src/main/resources/schema.sql) | ClickHouse DDL — auto-applied by Docker Compose on first start |
| [docker-compose.yml](docker-compose.yml) | Brings up ClickHouse + Redis |

### API Layer

| File | Role |
|---|---|
| [IngestionController.java](src/main/java/com/akamai/miniwsa/api/controller/IngestionController.java) | `POST /v1/events/ingest` — accepts single or batch events |
| [SecurityEventRequest.java](src/main/java/com/akamai/miniwsa/api/dto/SecurityEventRequest.java) | Inbound event DTO with `@Valid` Bean Validation annotations |
| [IngestionResponse.java](src/main/java/com/akamai/miniwsa/api/dto/IngestionResponse.java) | Response shape: `accepted`, `rejected`, per-event `results` |
| [GlobalExceptionHandler.java](src/main/java/com/akamai/miniwsa/api/exception/GlobalExceptionHandler.java) | `@ControllerAdvice` — maps validation failures to structured 400s |

### Service Layer

| File | Role |
|---|---|
| [IngestionService.java](src/main/java/com/akamai/miniwsa/service/IngestionService.java) | Iterates events, calls enrichment pipeline, batch-inserts to ClickHouse |
| [SecurityEventValidator.java](src/main/java/com/akamai/miniwsa/service/SecurityEventValidator.java) | Programmatic field-level validation (beyond Bean Validation) |

### Enrichment Pipeline (core domain logic)

| File | Role |
|---|---|
| [EnrichmentPipeline.java](src/main/java/com/akamai/miniwsa/enrichment/EnrichmentPipeline.java) | Orchestrator: classifier → threat score → `receivedAt` stamp |
| [AttackClassifier.java](src/main/java/com/akamai/miniwsa/enrichment/AttackClassifier.java) | Maps `rule.category` enum → human-readable `attackType` string via `EnumMap` |
| [ThreatScoreCalculator.java](src/main/java/com/akamai/miniwsa/enrichment/ThreatScoreCalculator.java) | Computes 0–100 score from severity + action + path bonus + repeat offender bonus |
| [RepeatOffenderCache.java](src/main/java/com/akamai/miniwsa/enrichment/RepeatOffenderCache.java) | Redis sorted-set sliding window: tracks events per `clientIp` over last 10 min |

### Domain Model

| File | Role |
|---|---|
| [EnrichedEvent.java](src/main/java/com/akamai/miniwsa/domain/EnrichedEvent.java) | Core domain object — original event fields + `attackType`, `threatScore`, `receivedAt` |
| [SecurityEvent.java](src/main/java/com/akamai/miniwsa/domain/SecurityEvent.java) | Pre-enrichment domain object |
| [RuleCategory.java](src/main/java/com/akamai/miniwsa/domain/RuleCategory.java) | Enum: `INJECTION`, `XSS`, `PROTOCOL_VIOLATION`, `DATA_LEAKAGE`, `BOT`, `DOS`, `RATE_LIMIT` |
| [Severity.java](src/main/java/com/akamai/miniwsa/domain/Severity.java) | Enum: `CRITICAL`, `HIGH`, `MEDIUM`, `LOW` |
| [Action.java](src/main/java/com/akamai/miniwsa/domain/Action.java) | Enum: `DENY`, `ALERT`, `MONITOR` |

### Persistence Layer

| File | Role |
|---|---|
| [EventRepository.java](src/main/java/com/akamai/miniwsa/repository/EventRepository.java) | Interface: `insertBatch`, `findSamples` |
| [ClickHouseEventRepository.java](src/main/java/com/akamai/miniwsa/repository/ClickHouseEventRepository.java) | ClickHouse JDBC implementation — bulk INSERT via batch API |

### Config

| File | Role |
|---|---|
| [ClickHouseConfig.java](src/main/java/com/akamai/miniwsa/config/ClickHouseConfig.java) | Wires the ClickHouse JDBC `DataSource` |
| [ClockConfig.java](src/main/java/com/akamai/miniwsa/config/ClockConfig.java) | Exposes a `Clock` bean (injectable for testability) |
| [RepeatOffenderProperties.java](src/main/java/com/akamai/miniwsa/config/RepeatOffenderProperties.java) | `@ConfigurationProperties` for `miniwsa.repeat-offender.*` tuning knobs |

### Tests

| File | What it covers |
|---|---|
| [AttackClassifierTest.java](src/test/java/com/akamai/miniwsa/enrichment/AttackClassifierTest.java) | All 7 category → attackType mappings |
| [ThreatScoreCalculatorTest.java](src/test/java/com/akamai/miniwsa/enrichment/ThreatScoreCalculatorTest.java) | Score components, cap at 100, path/repeat bonuses |
| [RepeatOffenderCacheTest.java](src/test/java/com/akamai/miniwsa/enrichment/RepeatOffenderCacheTest.java) | Sliding window logic with mocked Redis |
| [IngestionServiceTest.java](src/test/java/com/akamai/miniwsa/service/IngestionServiceTest.java) | Batch ingestion, per-event accepted/rejected results |
| [IngestionControllerTest.java](src/test/java/com/akamai/miniwsa/api/controller/IngestionControllerTest.java) | Integration test — full HTTP request/response via `MockMvc` |

---

## What's Built So Far

| Part | Status |
|---|---|
| Part 1 — Ingestion API (`POST /v1/events/ingest`) | Done |
| Part 2 — Classification & Enrichment (attackType, threatScore, repeatOffender) | Done |
| Part 3 — Statistics API (`GET /v1/stats/summary`) | Designed, not yet coded |
| Part 4 — Samples API (`GET /v1/events/samples`) | Designed, not yet coded |
| Part 5 — Data Generator script | Designed, not yet coded |
| Docker Compose (ClickHouse + Redis) | Done |

---

## Storage Choice

**ClickHouse** for events, **Redis** for the repeat-offender sliding window.

ClickHouse is purpose-built for append-only time-series data with `GROUP BY` analytics at scale. The `MergeTree` engine partitioned by month and ordered by `(config_id, timestamp)` maps directly onto the two most common query patterns: filter by config, range by time. Built-in `topK()` aggregates handle top-attacker and top-path queries without any in-memory work. `LowCardinality(String)` on enum columns (severity, category, action, country) gives dictionary encoding for free.

Redis handles the repeat-offender check because it requires a sub-millisecond count at write time — querying ClickHouse on every ingest event would add round-trip latency and fan out query load onto the OLAP store. A sorted set per `clientIp` gives O(log N) insert + sliding window eviction and O(1) count reads.

Full justification with trade-off analysis: [DESIGN.md — Storage Design](DESIGN.md#storage-design).

---

## What I'd Improve with More Time

- **ClickHouse materialized views** for pre-aggregated `byCategory`/`byAction` stats — eliminates full scans on the summary endpoint.
- **Deduplication** — Redis Set check on `eventId` at ingest time (write to Redis only after successful ClickHouse insert to avoid losing legitimate retries on DB failure). Full analysis in [DESIGN.md — Duplicate Event Handling](DESIGN.md#duplicate-event-handling).
- **Schema migrations via Flyway** — currently the `CREATE TABLE` is applied raw by Docker Compose.
- **ClickHouse connection pooling** — the current JDBC setup creates a single `DataSource` with no pool config.
- **Kafka ingestion** — ClickHouse has a native Kafka engine that can consume topics directly; would remove the HTTP ingestion bottleneck at high scale.

---

## Challenging Parts

**Threat score + repeat-offender at write time:** The score must be computed synchronously during ingestion, but the repeat-offender check needs a recent event count for the client IP. Querying ClickHouse at insert time would serialize ingestion on an OLAP query. Using Redis sorted sets with a sliding window (`ZADD` + `ZREMRANGEBYSCORE` + `ZCARD`) keeps the check under a millisecond and scales independently of the event store.

**Per-event result semantics for batch ingestion:** The spec returns `201 Created` even when some events in a batch are rejected — but `400` when the request body itself is unparseable. This required separating request-level parsing failures (handled by `GlobalExceptionHandler`) from field-level validation failures (handled by `IngestionService` per event), which drove the `MalformedIngestionRequestException` + `GlobalExceptionHandler` split.
