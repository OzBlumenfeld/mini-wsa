# Akamai Mini WSA — Assignment Requirements

## Overview

Build a backend service called **"Mini WSA"** — a Java/Spring Boot security analytics pipeline that:

1. **Ingests** security event records via a REST API (or by consuming from a message queue)
2. **Processes** them by classifying, enriching, and aggregating the data
3. **Stores** them in a storage engine of your choice
4. **Exposes** analytics APIs for querying statistics and retrieving individual samples

---

## Domain Model

```json
{
  "eventId": "evt-00132",
  "timestamp": "2026-05-20T14:32:10Z",
  "configId": 14227,
  "policyId": "pol_web1",
  "clientIp": "203.0.113.42",
  "hostname": "www.example.com",
  "path": "/api/v1/login",
  "method": "POST",
  "statusCode": 403,
  "userAgent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
  "rule": {
    "id": "950001",
    "name": "SQL_INJECTION",
    "message": "SQL Injection Attack Detected",
    "severity": "CRITICAL",
    "category": "INJECTION"
  },
  "action": "DENY",
  "geoLocation": {
    "country": "CN",
    "city": "Beijing"
  },
  "requestSize": 1024,
  "responseSize": 256
}
```

**Valid enum values:**

- `rule.category`: `INJECTION`, `XSS`, `PROTOCOL_VIOLATION`, `DATA_LEAKAGE`, `BOT`, `DOS`, `RATE_LIMIT`
- `rule.severity`: `CRITICAL`, `HIGH`, `MEDIUM`, `LOW`
- `action`: `DENY`, `ALERT`, `MONITOR`

---

## Part 1 — Ingestion API

**Endpoint:** `POST /v1/events/ingest`

- Accepts a single event or a batch of events (array)
- Validates incoming events against the schema: required fields, valid enum values, and valid ISO 8601 `timestamp` format
- Returns `201 Created` on success, `400 Bad Request` with field-level error details on validation failure
- Assigns a server-side `receivedAt` timestamp on ingestion

---

## Part 2 — Classification & Enrichment

When an event is ingested, compute and store three additional fields:

### 2a. Attack Type Mapping

| `rule.category` | `attackType` |
|---|---|
| INJECTION | SQL/Command Injection |
| XSS | Cross-Site Scripting |
| PROTOCOL_VIOLATION | Protocol Anomaly |
| DATA_LEAKAGE | Data Exfiltration |
| BOT | Bot Activity |
| DOS | Denial of Service |
| RATE_LIMIT | Rate Limiting |

### 2b. Threat Score (0–100)

Start at 0, add up the following:

| Condition | Points |
|---|---|
| `rule.severity` = CRITICAL | +40 |
| `rule.severity` = HIGH | +30 |
| `rule.severity` = MEDIUM | +20 |
| `rule.severity` = LOW | +10 |
| `action` = DENY | +20 |
| `action` = ALERT | +10 |
| `action` = MONITOR | +0 |
| `path` contains `/admin` or `/login` | +15 |
| >5 events from same `clientIp` in last 10 min | +15 (repeat offender) |

**Cap at 100.**

### 2c. Stored Fields

Store the original event plus `attackType`, `threatScore`, and `receivedAt`.

---

## Part 3 — Statistics API

**Endpoint:** `GET /v1/stats/summary?configId={configId}&from={ISO8601}&to={ISO8601}`

- `configId` is optional — if omitted, aggregate across all configurations
- `from` and `to` filter by event `timestamp`

**Response shape:**

```json
{
  "configId": 14227,
  "timeRange": { "from": "...", "to": "..." },
  "totalEvents": 1523,
  "byCategory": {
    "INJECTION": { "count": 450, "avgThreatScore": 72.3 },
    "BOT": { "count": 380, "avgThreatScore": 45.1 }
  },
  "byAction": {
    "DENY": 890,
    "ALERT": 433,
    "MONITOR": 200
  },
  "topAttackers": [
    { "clientIp": "203.0.113.42", "count": 87, "avgThreatScore": 81.2 }
  ],
  "topTargetedPaths": [
    { "path": "/api/v1/login", "count": 234 }
  ]
}
```

- `topAttackers` → top 10 by event count
- `topTargetedPaths` → top 10 by event count

---

## Part 4 — Samples API

**Endpoint:**
```
GET /v1/events/samples
  ?configId={configId}
  &from={ISO8601}
  &to={ISO8601}
  &category={category}
  &action={action}
  &limit={limit}
  &offset={offset}
```

- Returns individual enriched event records matching the filters
- All filter parameters are **optional**
- Supports pagination via `limit` (default 20, max 100) and `offset`
- Results sorted by `timestamp` **descending** (newest first)
- Response includes a `total` count of matching events (for pagination)

---

## Part 5 — Data Generation

Create a data generator script or module that:

- Generates realistic-looking random security events (randomized IPs, paths, rules, geo, etc.)
- Simulates **attack waves** — bursts of events from the same IP hitting the same path
- Can generate a configurable number of events (e.g., `--count 10000`)
- Outputs events in a format that can be fed into the ingestion API

---

## Technical Requirements

- **Language:** Java with Spring Boot for the REST APIs
- **Storage:** any storage engine of your choice — this system handles big-data scale in the real world, so justify your choice in the README
- **Build & Run:** provide clear instructions to build and run the project; at minimum it must be possible to build and run by following the README. A `docker-compose up` setup that brings up the full stack (app + dependencies) is a **bonus**, not required
- **Testing:** unit tests for the classification and enrichment logic, plus at least one integration test for the API
- **API Documentation:** README with clear setup instructions, API documentation (curl examples are fine), and an architecture description

---

## Submission

- Create a **public GitHub repository** for the assignment
- Use meaningful commit messages — make small, logical commits that show how the work was broken down (not one giant commit at the end)
- Tag milestones as you go (e.g. `v0.1-ingestion`, `v0.2-enrichment`, `v0.3-stats`, …)
- Include a `README.md` with:
  - How to build and run the project
  - Architecture overview (a simple diagram is a plus)
  - Storage choice justification
  - What you'd improve with more time
  - Which parts you found challenging and how you solved them

---

## Bonus Challenges (pick 1–2)

### 1. Alerting Endpoint
- `POST /v1/alerts/define` — define a rule: "if more than N events of category X happen within Y minutes, trigger an alert"
- `GET /v1/alerts/evaluate` — checks current data against all defined rules, returns which alerts are currently firing

### 2. Streaming Ingestion
- Consume events from a Kafka topic, instead of (or in addition to) the REST endpoint
- Use Docker Compose to set up Kafka, and include a producer script to publish events to the topic

### 3. Time-Series Dashboard Data
```
GET /v1/stats/timeseries?configId={configId}&from={}&to={}&interval={1m|5m|1h}
```
Returns event counts bucketed by the specified interval — suitable for drawing a line chart.

### 4. Rate Limiting
- Add rate limiting to the stats and samples APIs (e.g. 100 requests/minute per client IP)
- Returns `429 Too Many Requests` when exceeded

---

## Interview Prep Checklist

- [ ] Walk through your architecture and design decisions as if presenting to the team
- [ ] Explain your storage choice — why you picked it, what alternatives you considered, and what trade-offs you accepted
- [ ] Deep-dive into the enrichment logic — the threat score calculation, how the repeat-offender check works, and its performance implications
- [ ] Discuss scaling — how the system would handle 10x or 100x the current load, and what you would change
- [ ] Walk through your testing strategy — what's covered and what you'd add with more time
- [ ] Be ready to discuss one thing you learned during the assignment and how you approached learning it

---

## Notes

- AI tools (Claude, ChatGPT, Copilot, etc.) are allowed and considered a normal part of modern development — but you must understand everything in your code; be ready to explain implementation details, modify code live, and discuss alternatives in the interview
- Focus on **working software over perfection** — a running system with 80% of the features beats a non-running system with 100%
- If you get stuck on something, document what you tried and move on — problem-solving approach is valued over completeness
