# Mini WSA

A simplified **Web Security Analytics** pipeline, modeled after Akamai's WSA platform. It ingests raw security events, enriches them in real time (attack classification + threat scoring), persists them to ClickHouse, and exposes REST APIs for analytics and event sampling.

See [REQUIREMENTS.md](REQUIREMENTS.md) for the full assignment spec, and [DESIGN.md](DESIGN.md) for architecture decisions and trade-offs.

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

## What's Built So Far

| Part | Status |
|---|---|
| Part 1 — Ingestion API (`POST /v1/events/ingest`) | Done |
| Part 2 — Classification & Enrichment (attackType, threatScore, repeatOffender) | Done |
| Part 3 — Statistics API (`GET /v1/stats/summary`) | Designed, not yet coded |
| Part 4 — Samples API (`GET /v1/events/samples`) | Designed, not yet coded |
| Part 5 — Data Generator script | Designed, not yet coded |
| Docker Compose (ClickHouse + Redis) | Done |

