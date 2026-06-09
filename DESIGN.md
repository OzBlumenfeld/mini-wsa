# Mini WSA — Architecture & Design

## Table of Contents
1. [System Overview](#system-overview)
2. [Architecture Diagram](#architecture-diagram)
3. [Layer Breakdown](#layer-breakdown)
   - [API Layer](#1-api-layer)
   - [Service Layer](#2-service-layer)
   - [Enrichment Pipeline](#3-enrichment-pipeline)
   - [Persistence Layer](#4-persistence-layer)
   - [Data Generator](#5-data-generator)
4. [Key Components](#key-components)
5. [Data Flow](#data-flow)
6. [Storage Design](#storage-design)
7. [Duplicate Event Handling](#duplicate-event-handling)
8. [Package Structure](#package-structure)

---

## System Overview

Mini WSA is a simplified security analytics pipeline modeled after Akamai's Web Security Analytics (WSA) platform. It ingests raw security events (DLRs), enriches them in real time, persists them to ClickHouse, and exposes REST APIs for analytics and event sampling.

**Core tech stack:**
- **Runtime:** Java 21 + Spring Boot 3.x (development should follow SKILL.md in spring-boot-backend for writing java and spring boot code based on the guidelines)
- **Primary store:** ClickHouse (OLAP — append-only event storage + analytics)
- **In-memory cache:** Redis (repeat offender sliding window)
- **Build:** Maven
- **Containerization:** Docker + Docker Compose

---

## Architecture Diagram

```
                        ┌─────────────────────────────────────────────────┐
                        │                  Mini WSA Service                │
                        │                                                  │
  REST Client           │  ┌──────────────┐     ┌──────────────────────┐  │
      │                 │  │  API Layer   │     │   Service Layer      │  │
      │  POST /ingest   │  │              │     │                      │  │
      ├────────────────►│  │  Ingestion   │────►│  EnrichmentService   │  │
      │                 │  │  Controller  │     │  ClassificationSvc   │  │
      │  GET /stats     │  │              │     │  ThreatScoreCalc     │  │
      ├────────────────►│  │  Stats       │────►│  StatsService        │  │
      │                 │  │  Controller  │     │  SamplesService      │  │
      │  GET /samples   │  │              │     │                      │  │
      ├────────────────►│  │  Samples     │────►│                      │  │
      │                 │  │  Controller  │     └──────────┬───────────┘  │
                        │  └──────────────┘                │              │
                        │                        ┌─────────▼───────────┐  │
                        │                        │  Persistence Layer  │  │
                        │                        │                     │  │
                        │                        │  EventRepository    │  │
                        │                        │  StatsRepository    │  │
                        │                        └──────┬──────┬───────┘  │
                        └─────────────────────────────-─┼──────┼──────────┘
                                                         │      │
                                              ┌──────────▼─┐  ┌▼──────────┐
                                              │ ClickHouse │  │  Redis    │
                                              │ (Events +  │  │ (Sliding  │
                                              │  Analytics)│  │  Window)  │
                                              └────────────┘  └───────────┘
```

---

## Layer Breakdown

### 1. API Layer

Responsibility: HTTP boundary. Validates input, maps to/from DTOs, delegates to services, returns standardized responses.

**Controllers:**

| Controller | Endpoints | Responsibility |
|---|---|---|
| `IngestionController` | `POST /v1/events/ingest` | Accept a JSON array of up to 500 events, trigger enrichment pipeline |
| `StatsController` | `GET /v1/stats/summary` | Return aggregated analytics for a configId + time range |
| `SamplesController` | `GET /v1/events/samples` | Return paginated, filtered enriched events |

- `GET /v1/events/samples` should have configId, from, to, category, action, limit and offset as optional parameters to filter based on them. from and to should be compatible to ISO8601.
-  `GET /v1/stats/summary` from and to should be compatible to ISO8601.

**Key design decisions:**
- Controllers are thin — no business logic, only request/response mapping.
- Validation handled by `@Valid` + Bean Validation annotations on DTOs.
- Input must be validated, if input isn't from the same type or any mismatch should be `400 Bad Request` and state the issue.
- A global `@ControllerAdvice` (`GlobalExceptionHandler`) translates validation failures to structured 400 responses with field-level error details. If rate limiting will be implemented as the bonus part returns 429 Too Many Requests for a specific exception class for ratelimiting.
- All timestamps validated as ISO 8601; invalid formats return 400 with a clear message.
- `POST /v1/events/ingest` accepts a JSON array of up to 500 events and returns
  `201 Created` whenever the array is parseable — even if some/all individual events fail
  validation — with per-event results in the response body. `400 Bad Request` is returned
  for: malformed JSON, a non-array body, an empty array, a batch exceeding 500 events, or
  a type/enum mismatch in any event (Jackson fails the whole request before the controller
  method runs in those cases). Bean Validation errors (missing fields, blank strings) are
  still reported per-event.
- Have Swaggert as a nice edition and for documentation.
**DTOs (Request/Response):**

```
IngestionRequest        →  List<SecurityEventRequest>
SecurityEventRequest    →  maps the incoming DLR JSON schema
IngestionResponse       →  { accepted: N, rejected: N,
                             results: [ { eventId, status: "accepted"|"rejected", errors?: [...] } ] }
StatsSummaryResponse    →  { configId, timeRange, totalEvents, byCategory, byAction,
                             topAttackers, topTargetedPaths }
SamplesResponse         →  { total, events: [...] }          ← no limit/offset in response body
EnrichedEventResponse   →  flat record — all 23 security_events columns
```

Stats and samples DTOs live in feature sub-packages (`api/dto/stats/`, `api/dto/samples/`) rather than a flat `api/dto/` directory, so ingestion and analytics DTOs don't collide.

---

### 2. Service Layer

Responsibility: Business logic orchestration. Coordinates enrichment, delegates persistence, computes statistics.

**Services:**

**`IngestionService`**
- Entry point for all ingest requests.
- Iterates events, calls `EnrichmentPipeline` per event, then batch-inserts to ClickHouse.
- Processes each event independently; collects a per-eventId result (`accepted` or
  `rejected` with field-level validation errors) and returns the full result list —
  even when some events are rejected, the overall response is `201 Created`.

**`StatsService`**
- Queries `StatsRepository` with configId + time range filters.
- Assembles `StatsSummaryResponse`: total count, byCategory aggregation, byAction counts, top 10 attackers, top 10 targeted paths.
- When `configId` is omitted, queries across all configs.

**`SamplesService`**
- Delegates to `EventRepository` with all optional filters (configId, category, action, time range).
- Handles default/max limit enforcement (default 20, max 100).
- Returns total count alongside the page of results for frontend pagination.

---

### 3. Enrichment Pipeline

Responsibility: Stateless classification + stateful threat scoring. This is the core domain logic.

**Components:**

**`EnrichmentPipeline`** _(orchestrator)_
- Calls `AttackClassifier` → `ThreatScoreCalculator` → stamps `receivedAt`.
- Returns an `EnrichedEvent` ready for persistence.
- Designed to be easily extensible (add more enrichment steps without touching other components).

**`AttackClassifier`**
- Pure function: maps `rule.category` enum → `attackType` human-readable string.
- Implemented as an immutable `EnumMap<RuleCategory, String>` — no branching, O(1) lookup.
- Called at write time; `attackType` is stored alongside `rule_category` in ClickHouse.

```java
INJECTION         → "SQL/Command Injection"
XSS               → "Cross-Site Scripting"
PROTOCOL_VIOLATION→ "Protocol Anomaly"
DATA_LEAKAGE      → "Data Exfiltration"
BOT               → "Bot Activity"
DOS               → "Denial of Service"
RATE_LIMIT        → "Rate Limiting"
```

**`ThreatScoreCalculator`**
- Computes an integer score 0–100 from four additive factors:

```
severityScore  = CRITICAL→40, HIGH→30, MEDIUM→20, LOW→10
actionScore    = DENY→20, ALERT→10, MONITOR→0
pathBonus      = path contains "/admin" or "/login" → +15
repeatBonus    = clientIp seen >5 times in last 10 min → +15
threatScore    = min(severityScore + actionScore + pathBonus + repeatBonus, 100)
```

- The `repeatBonus` check delegates to `RepeatOffenderCache`.
- if repeatBonus happens - log as debug the client ip and current count of requests in the last 10 minutes

**`RepeatOffenderCache`** _(Redis-backed)_
- Uses a Redis sorted set per `clientIp` with the event timestamp as the score.
- On each event: `ZADD ip:<clientIp> <timestamp> <eventId>`, then `ZREMRANGEBYSCORE` to evict entries older than 10 minutes, then `ZCARD` to get the current count.
- TTL on the key auto-expires idle IPs.
- This avoids querying ClickHouse at write time — could delay ingestion.

> **Why Redis here?** ClickHouse is column-oriented and optimized for bulk reads/aggregations, not for sub-millisecond point lookups at write time. A Redis sorted set gives O(log N) insert + range eviction and sub-millisecond reads, making it the right tool for this sliding window pattern. And more out of the shelf request limiter by nature.
---    

### 4. Persistence Layer

Responsibility: All reads and writes to ClickHouse.

**Repositories:**

**`EventRepository`** _(write-only)_
- `insertBatch(List<EnrichedEvent>)` — bulk insert via ClickHouse JDBC batch API.
- Read operations live in separate interfaces; this keeps the write path decoupled (CQRS-style).

**`StatsRepository`** _(read-only analytics)_
- Runs **4 separate aggregation queries** — `byCategoryStats`, `byActionStats`, `topAttackers`, `topTargetedPaths` — rather than one monolithic `GROUPING SETS` query. Each query is independently readable and testable.
- All aggregations execute inside ClickHouse (`count()`, `avg()`, `ORDER BY cnt DESC LIMIT 10`).
- `totalEvents` is derived in `StatsService` by summing the category counts — no extra `COUNT(*)` round-trip.

**`SamplesRepository`** _(read-only event retrieval)_
- `querySamples(...)` — paginated `SELECT *` with `ORDER BY timestamp DESC LIMIT ? OFFSET ?`.
- `countSamples(...)` — separate `SELECT count(*)` for the pagination total.
- Both methods build WHERE clauses dynamically: `timestamp >= ? AND timestamp <= ?` is always present; `config_id`, `rule_category`, and `action` filters are appended only when non-null. Parameters flow through JDBC prepared statements — no string concatenation.

**Shared JDBC pattern (both read repositories):**

```java
List<Object> params = new ArrayList<>();
var sql = new StringBuilder("... WHERE timestamp >= ? AND timestamp <= ?");
params.add(Timestamp.from(from));   // DateTime64 binding
params.add(Timestamp.from(to));
if (configId != null) { sql.append(" AND config_id = ?"); params.add(configId); }
// ... additional optional filters
jdbcTemplate.query(sql.toString(), rowMapper, params.toArray());
```

Both `ClickHouseStatsRepository` and `ClickHouseSamplesRepository` inject the same `JdbcTemplate` bean as the write repository — no additional DataSource configuration needed.

**ClickHouse Table Schema:**

```sql
CREATE TABLE security_events
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
```

**Schema rationale:**
- `ORDER BY (config_id, timestamp)` — matches the most common query pattern (filter by configId, range by timestamp).
- `LowCardinality(String)` on enum-like fields (method, severity, category, action, country) — ClickHouse stores these as dictionary-encoded integers internally, significantly reducing storage and improving filter performance.
- `PARTITION BY toYYYYMM(timestamp)` — enables partition pruning for long time-range queries and efficient reads by date.
- No update/delete operations needed — events are immutable once written.
- I went with merge tree as the engine, because I would enforce no duplications by the application level rather
then replacingMergeTree which is not going to work if i apply timestamp to the order by (i.e. if i was putting event id as well and want to see no same event id in the DB)
- Schema is managed manually via the CREATE TABLE statement in schema.sql. In production I'd use Flyway to version DDL migrations alongside application code
---

### 5. Data Generator

Responsibility: Produce realistic synthetic security events for testing and seeding.

**`DataGeneratorRunner`** _(Script in Python 3 under /scripts)_

Configuration values are hardcoded at the top of the script — edit them directly before running:
```python
TARGET_URL   = "http://localhost:8080/v1/events/ingest"
EVENT_COUNT  = 10000
ATTACK_WAVES = 5
WAVE_SIZE    = 200
BATCH_SIZE   = 100
IPS          = []   # leave empty to use the built-in pool, or set e.g. ["1.2.3.4", "5.6.7.8"] to round-robin
```

CLI arguments may be added later if stress-testing scenarios require it.

**Generation strategy:**
- Pool of realistic client IPs, hostnames, paths, user agents.
- **Attack waves:** pick a random IP + path, generate `WAVE_SIZE` events from that IP to that path within a short time window — simulates coordinated attack campaigns and triggers the repeat offender bonus.
- If `IPS` is set, round-robin through that list for every event (both wave and background) instead of the built-in pool.
- Events outside waves are uniformly random across the IP pool.
- Sends events in batches of `BATCH_SIZE` to the ingestion endpoint.
- Prints a summary: total sent, total accepted, total rejected.


### 6. Integration Test
- Have an Integration test based on the docker-compose suite where you bring it up, you can store the time and then as t1 and then run the script for generating the events and then test the stats summary endpoint and see that the numbers are as expected i.e. top attackers, top targeted paths by action and so on make sense, see the threat scores as well accordingly. in corporate different config ids and without config id

---

## Key Components Summary

| Component | Package | Role |
|---|---|---|
| `IngestionController` | `api.controller` | HTTP ingestion endpoint |
| `StatsController` | `api.controller` | HTTP stats endpoint |
| `SamplesController` | `api.controller` | HTTP samples endpoint |
| `GlobalExceptionHandler` | `api.exception` | Centralized error handling |
| `SecurityEventRequest` | `api.dto` | Inbound event DTO + validation |
| `IngestionService` | `service` | Batch ingestion orchestration |
| `StatsService` | `service` | Stats assembly |
| `SamplesService` | `service` | Paginated event retrieval |
| `EnrichmentPipeline` | `enrichment` | Enrichment orchestrator |
| `AttackClassifier` | `enrichment` | Category → attackType mapping |
| `ThreatScoreCalculator` | `enrichment` | Score computation |
| `RepeatOffenderCache` | `enrichment` | Redis sliding window |
| `EventRepository` | `repository` | ClickHouse bulk insert (write-only) |
| `StatsRepository` | `repository` | ClickHouse aggregation query interface (4 methods) |
| `ClickHouseStatsRepository` | `repository` | ClickHouse aggregation query implementation |
| `SamplesRepository` | `repository` | ClickHouse paginated event-read interface |
| `ClickHouseSamplesRepository` | `repository` | ClickHouse paginated SELECT implementation |
| `EnrichedEvent` | `domain` | Core domain model (post-enrichment) |
| `DataGeneratorRunner` | `generator` | Synthetic event generation |

---

## Data Flow

### Ingestion Flow

```
POST /v1/events/ingest
       │
       ▼
IngestionController
  │  validates request DTOs (@Valid)
  │  maps to domain objects
       │
       ▼
IngestionService
  │  iterates events
       │
       ▼  (per event)
EnrichmentPipeline
  ├── AttackClassifier        → attackType
  ├── ThreatScoreCalculator
  │     ├── severity score
  │     ├── action score
  │     ├── path bonus
  │     └── RepeatOffenderCache (Redis ZADD + ZCARD)  → repeat bonus
  └── stamps receivedAt
       │
       ▼
EventRepository.insertBatch(enrichedEvents)
  └── ClickHouse bulk INSERT
       │
       ▼
201 Created  { accepted: N, rejected: M,
               results: [ { eventId, status, errors? }, ... ] }

(400 Bad Request for: malformed JSON, non-array body, empty array,
 batch exceeding 500 events, or type/enum mismatch in any event)
```

### Stats Query Flow

```
GET /v1/stats/summary?configId=X&from=T1&to=T2
       │
       ▼
StatsController
       │
       ▼
StatsService
       │
       ▼
StatsRepository
  └── ClickHouse analytical query
      SELECT
        count(),
        countIf(category='INJECTION'),
        avg(threat_score),
        topK(10)(client_ip),
        topK(10)(path),
        countIf(action='DENY'), ...
      WHERE config_id = X AND timestamp BETWEEN T1 AND T2
       │
       ▼
StatsService assembles StatsSummaryResponse
       │
       ▼
200 OK  { configId, timeRange, totalEvents, byCategory, byAction, topAttackers, ... }
```

---

## Storage Design

### Why ClickHouse

| Requirement | ClickHouse fit |
|---|---|
| Append-only security events | Native — MergeTree engine is optimized for immutable time-series data |
| GROUP BY analytics at scale | Columnar storage + vectorized execution — aggregations are extremely fast |
| Time-range filtering | Partition pruning by month + primary key ordering by timestamp |
| Top-N queries (topAttackers, topPaths) | Built-in `topK()` aggregate function |
| 10M+ events | Designed for this scale; compresses repeated enum values via `LowCardinality` |

### Why Redis (alongside ClickHouse)

The repeat offender check requires knowing — at write time — how many events a given IP produced in the last 10 minutes. Querying ClickHouse for this on every ingest event would add significant latency and query load. Redis sorted sets solve this with:
- O(log N) insert
- O(log N) range eviction (sliding window cleanup)
- O(1) count read
- Sub-millisecond latency

### What would be added with more time

- **ClickHouse materialized views** for pre-aggregated `byCategory` and `byAction` stats, avoiding full scans on the summary endpoint.
- **Connection pooling** for the ClickHouse JDBC client.
- **Kafka ingestion** (Bonus #2) — ClickHouse has a native Kafka engine that can consume directly from topics.
- **Schema migrations** managed via Flyway or Liquibase.

---

## Duplicate Event Handling

### The gap

The current implementation has no deduplication at any layer:
- `SecurityEventRequest` validates `eventId` only with `@NotBlank` — no uniqueness check.
- `IngestionService` performs no duplicate check before storing.
- `ClickHouseEventRepository.insertBatch` issues a plain `INSERT` with no conflict handling.
- The ClickHouse table uses `MergeTree()` — no engine-level dedup.

Submitting the same `eventId` twice writes two rows. This matters because duplicates would:
1. Inflate `byCategory`, `byAction`, and `topAttackers` stats.
2. Double-count events in the Redis repeat-offender sliding window, potentially triggering the `repeatBonus` for a legitimate client IP.

### Candidate approaches

| Approach | How it works | Pros | Cons |
|---|---|---|---|
| **Redis Set (TTL-bounded)** | On ingest, check a Redis Set for the `eventId`; reject if present; write to Redis only after a successful ClickHouse insert | Sub-millisecond check; uses existing Redis infra | Dedup window bounded by TTL (≤1 day practical); stale retries past TTL can re-enter |
| **ReplacingMergeTree** | Change engine to `ReplacingMergeTree` with a `ver` column; include `event_id` in ORDER BY for row-level dedup | Engine handles dedup automatically | `event_id` in ORDER BY conflicts with current `(config_id, timestamp)` sort optimized for analytics; dedup is eventual (happens at merge time, not at write time); reads require `SELECT FINAL` for strong consistency |
| **ClickHouse pre-check per batch** | Before inserting a batch, `SELECT event_id FROM security_events WHERE event_id IN (...)` and drop already-existing IDs | True permanent idempotency; no TTL expiry gap | Adds a read-before-write per batch; `event_id` is not in the primary key so the lookup is a full scan unless a separate lookup table or secondary index is maintained |

### Why nothing is implemented yet

The right choice depends on questions worth answering before committing:
- What is the realistic maximum retry delay? If retries always happen within a few hours, a 1-day Redis TTL is effectively permanent.
- What is the write-latency budget? A ClickHouse pre-check adds a network round-trip per batch.
- Can the ORDER BY be restructured? ReplacingMergeTree needs `event_id` in the key, which breaks the analytics query pattern unless a secondary materialized view is added.

### Write ordering note (Redis approach)

The eventId must be written to Redis **after** a successful ClickHouse insert. Writing before means a failed ClickHouse insert "consumes" the ID in Redis — a subsequent legitimate client retry would be rejected, losing the event permanently.

---

## Package Structure

```
com.akamai.miniwsa/
├── api/
│   ├── controller/
│   │   ├── IngestionController.java
│   │   ├── StatsController.java
│   │   └── SamplesController.java
│   ├── dto/
│   │   ├── SecurityEventRequest.java       ← ingestion DTOs stay at this level
│   │   ├── RuleRequest.java
│   │   ├── GeoLocationRequest.java
│   │   ├── IngestionResponse.java
│   │   ├── IngestionEventStatus.java
│   │   ├── stats/                          ← analytics DTOs in sub-package
│   │   │   ├── StatsSummaryResponse.java
│   │   │   ├── CategoryStats.java
│   │   │   ├── AttackerStats.java
│   │   │   └── PathStats.java
│   │   └── samples/                        ← samples DTOs in sub-package
│   │       ├── SamplesResponse.java
│   │       └── EnrichedEventResponse.java
│   └── exception/
│       ├── GlobalExceptionHandler.java
│       └── MalformedIngestionRequestException.java
├── domain/
│   ├── SecurityEvent.java
│   ├── EnrichedEvent.java
│   ├── Rule.java
│   ├── GeoLocation.java
│   ├── RuleCategory.java
│   ├── Action.java
│   └── Severity.java
├── enrichment/
│   ├── EnrichmentPipeline.java
│   ├── AttackClassifier.java
│   ├── ThreatScoreCalculator.java
│   └── RepeatOffenderCache.java
├── service/
│   ├── IngestionService.java
│   ├── SecurityEventValidator.java
│   ├── StatsService.java
│   └── SamplesService.java
├── repository/
│   ├── EventRepository.java               ← write-only interface
│   ├── ClickHouseEventRepository.java     ← write implementation
│   ├── StatsRepository.java               ← read interface (4 aggregation methods)
│   ├── ClickHouseStatsRepository.java     ← aggregation implementation
│   ├── SamplesRepository.java             ← read interface (query + count)
│   └── ClickHouseSamplesRepository.java   ← paginated SELECT implementation
├── config/
│   ├── ClickHouseConfig.java
│   ├── RepeatOffenderProperties.java
│   └── ClockConfig.java
└── generator/
    └── DataGeneratorRunner.java            ← Python script under /scripts/
```