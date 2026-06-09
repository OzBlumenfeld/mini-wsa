# Mini WSA

A simplified **Web Security Analytics** pipeline, modeled after Akamai's WSA platform. It ingests raw security events, enriches them in real time (attack classification + threat scoring), persists them to ClickHouse, and exposes REST APIs for analytics and event sampling.

See [REQUIREMENTS.md](REQUIREMENTS.md) for the full assignment spec.

**Two-document structure:** This README covers setup, API reference, and the assignment-required summary sections. [DESIGN.md](DESIGN.md) is the full technical reference — architecture diagram, layer-by-layer breakdown, ClickHouse schema rationale, data flow, package structure, and trade-off analysis. Keeping them separate means the README stays scannable for a first-time reader while deep implementation decisions live in one authoritative place.

---

## How to Build and Run

### Option A: Docker only (no Java required)

The entire stack — app, ClickHouse, and Redis — runs via Docker Compose. No local JDK or Maven needed.

```bash
docker compose up
```

On first run this builds the app image from the local `Dockerfile`. If the image already exists and you've changed source code, force a rebuild:

```bash
docker compose up --build
```

This brings up:
- **App** on `http://localhost:8080` — built from local `src/` via the multi-stage Dockerfile
- **ClickHouse** on ports `8123` (HTTP) and `9000` (native) — schema is auto-applied from [src/main/resources/schema.sql](src/main/resources/schema.sql)
- **Redis** on port `6379`
- **Redis Insight UI** on `http://localhost:5540`

### Option B: Local development (Java required)

Prerequisites: Java 21+, Docker + Docker Compose

**1. Start dependencies only**

```bash
docker compose up -d redis clickhouse
```

**2. Run the application**

```bash
./mvnw spring-boot:run
```

The app starts on `http://localhost:8080`.

### Run tests

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
| [docker-compose.yml](docker-compose.yml) | Brings up the full stack — app (built from Dockerfile), ClickHouse, Redis |

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

## What's Built

| Part | Status |
|---|---|
| Part 1 — Ingestion API (`POST /v1/events/ingest`) | Done |
| Part 2 — Classification & Enrichment (attackType, threatScore, repeatOffender) | Done |
| Part 3 — Statistics API (`GET /v1/stats/summary`) | Done |
| Part 4 — Samples API (`GET /v1/events/samples`) | Done |
| Part 5 — Data Generator script | Done |
| Docker Compose (ClickHouse + Redis) | Done |

---

## Storage Design Choices

**ClickHouse** is used as the primary event store. Security analytics workloads — counting by category, top-N attackers, time-range aggregations — map directly to its columnar storage and vectorized GROUP BY execution. Specific design choices:
- `MergeTree` engine with `ORDER BY (config_id, timestamp)` matches the dominant query pattern (filter by config, range by time)
- `LowCardinality(String)` on enum-like fields (severity, category, action, country) gives dictionary encoding, reducing storage and improving filter scan speed
- `PARTITION BY toYYYYMM(timestamp)` enables partition pruning for long time-range queries
- Events are immutable once written — no UPDATE/DELETE needed

**Redis** handles the repeat-offender sliding window. The threat-score calculation needs to know, at write time, how many events a given IP produced in the last 10 minutes. Querying ClickHouse on every ingest would add significant latency; ClickHouse is optimized for bulk reads, not sub-millisecond point lookups. A Redis sorted set gives O(log N) insert + range eviction and sub-millisecond reads, keeping the ingestion path fast.

See [DESIGN.md § Storage Design](DESIGN.md#storage-design) for the full rationale and trade-off table.

---

## What I Would Improve with More Time

- **Connection pooling** — the ClickHouse JDBC client currently creates connections on demand; a pool (HikariCP or ClickHouse's own pooling) would reduce latency under concurrent load.
- **Schema migrations via Flyway** — the DDL is currently applied manually from `schema.sql`; versioned migrations would make schema evolution safe in production.
- **Kafka ingestion** — ClickHouse has a native Kafka connector engine that can consume directly from topics, removing the HTTP ingestion hop for high-throughput pipelines.
- **Event deduplication** — currently the same `eventId` can be inserted twice. The cleanest approach given the existing schema is a Redis Set check (per eventId, written after a successful ClickHouse insert) with a TTL matching the expected retry window. See [DESIGN.md § Duplicate Event Handling](DESIGN.md#duplicate-event-handling) for the full trade-off analysis.
- **Rate limiting** - The Bonus feature as it using Redis and same sliding window pattern based on the api path of sample/stats
- **More Tests** - I have only the baseline - in order to trust all of the current features there need to be much more integration tests cases. + even more unit testing with mockings.
---

## Challenges and How I Solved Them

**1. Per-event accepted/rejected reporting inside a single 201 response**

The assignment requires that a batch request returns `201 Created` even when some events fail validation, with per-event error details. Standard Spring `@Valid` on a list short-circuits at the first failure and returns 400 for the whole batch. The solution was to remove Bean Validation from the list elements entirely and delegate to a `SecurityEventValidator` called programmatically per event inside `IngestionService`, collecting field-level errors into a per-event result list. Jackson-level failures (malformed JSON, type mismatches) still produce 400 at the controller boundary, which is the correct behavior per spec.

**2. Repeat-offender check at write time without blocking ingestion**

The threat score needs a real-time count of how many events a given IP produced in the last 10 minutes. Querying ClickHouse for this on every event would add a synchronous read to the write path — ClickHouse is column-oriented and optimized for bulk analytical reads, not sub-millisecond point lookups. The solution was a Redis sorted set per client IP: `ZADD` the event, `ZREMRANGEBYSCORE` to evict entries older than 10 minutes, `ZCARD` to get the count — all in a single pipeline under 1 ms.

**3. MergeTree ORDER BY vs deduplication**

`ReplacingMergeTree` provides engine-level deduplication but requires `event_id` in the `ORDER BY`, which would break the `(config_id, timestamp)` sort order that makes analytics queries fast. Switching the sort key for dedup correctness would either require a secondary materialized view to restore query performance, or accepting slow aggregation queries. The decision was to stay with plain `MergeTree` and enforce uniqueness at the application layer, with the trade-off documented explicitly.

**4. Treating design documentation as a contract, not a post-hoc write-up**

 The challenge was to write `REQUIREMENTS.md` and `DESIGN.md` as authoritative ground truth *before* implementing each layer, with every trade-off and rejected alternative captured there. Having the reasoning written down before either option was committed to code made both decisions faster and kept the final implementation fully traceable back to its motivation.
 In the AI era good documentation and letting the agents/people know what are your key decisions and consideration means a lot and it's very different than the classical engineering that you just have design where it's very high level.

