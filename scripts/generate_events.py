#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = ["requests"]
# ///
"""
Mini WSA data generator — sends synthetic security events to the ingestion endpoint.

Run with:
  uv run scripts/generate_events.py

uv automatically creates an isolated venv and installs 'requests' on first run.
"""

import random
import time
from datetime import datetime, timedelta, timezone

import requests

# ---------------------------------------------------------------------------
# Configuration — edit these before running
# ---------------------------------------------------------------------------

TARGET_URL   = "http://localhost:8080/v1/events/ingest"
EVENT_COUNT  = 15000   # total background (non-wave) events
ATTACK_WAVES = 3        # number of coordinated attack wave campaigns
WAVE_SIZE    = 5000      # events per wave (must be >5 to trigger repeat-offender bonus)
BATCH_SIZE   = 500      # events per HTTP POST (max 500 per API contract)
WAVE_DELAY_S = 5       # seconds to pause between consecutive waves (0 = no delay)
IPS          = []       # override IP pool: leave empty to use built-in pool,
                        # or set e.g. ["1.2.3.4", "5.6.7.8"] to round-robin all events

# ---------------------------------------------------------------------------
# Data pools
# ---------------------------------------------------------------------------

CLIENT_IPS = [
    "203.0.113.42",  "198.51.100.17",  "192.0.2.88",    "45.33.32.156",
    "104.21.56.22",  "172.67.180.110", "64.233.160.0",  "66.249.64.10",
    "185.220.101.5", "195.154.107.41", "91.108.4.200",  "77.88.55.70",
    "52.84.0.100",   "13.107.42.14",   "151.101.1.69",  "31.13.64.35",
    "157.240.2.35",  "216.58.210.46",  "8.8.8.8",       "1.1.1.1",
]

HOSTNAMES = [
    "api.example.com",
    "shop.acme.io",
    "portal.corp.net",
    "auth.services.io",
    "app.startup.dev",
    "cdn.media-corp.com",
    "payments.fintech.io",
    "internal.enterprise.org",
]

# Paths include high-value targets to exercise the +15 path bonus
PATHS = [
    "/admin",
    "/admin/users",
    "/admin/settings",
    "/login",
    "/auth/login",
    "/api/v1/users",
    "/api/v1/orders",
    "/api/v1/payments",
    "/api/v2/tokens",
    "/api/search",
    "/api/upload",
    "/dashboard",
    "/profile",
    "/checkout",
    "/health",
    "/metrics",
    "/.env",
    "/wp-admin/admin-ajax.php",
    "/cgi-bin/test.cgi",
    "/actuator/env",
]

USER_AGENTS = [
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15",
    "Mozilla/5.0 (X11; Linux x86_64; rv:125.0) Gecko/20100101 Firefox/125.0",
    "python-requests/2.31.0",
    "curl/8.4.0",
    "Go-http-client/2.0",
]

# One entry per category; severity is assigned at generation time
RULE_TEMPLATES = {
    "INJECTION": {
        "id": "950001",
        "name": "SQL_INJECTION",
        "message": "SQL Injection Attack Detected",
    },
    "XSS": {
        "id": "941100",
        "name": "XSS_ATTACK",
        "message": "XSS Attack Detected via libinjection",
    },
    "PROTOCOL_VIOLATION": {
        "id": "920100",
        "name": "INVALID_REQUEST_LINE",
        "message": "Invalid HTTP Request Line",
    },
    "DATA_LEAKAGE": {
        "id": "970900",
        "name": "DATA_LEAKAGE",
        "message": "Sensitive Data in HTTP Response",
    },
    "BOT": {
        "id": "990001",
        "name": "BOT_DETECTED",
        "message": "Known Bot/Scanner User-Agent Detected",
    },
    "DOS": {
        "id": "912100",
        "name": "DOS_ATTACK",
        "message": "Denial of Service Attack Detected",
    },
    "RATE_LIMIT": {
        "id": "912120",
        "name": "RATE_LIMIT_EXCEEDED",
        "message": "Client Request Rate Limit Exceeded",
    },
}

GEO_DATA = [
    {"country": "CN", "city": "Beijing"},
    {"country": "RU", "city": "Moscow"},
    {"country": "US", "city": "New York"},
    {"country": "DE", "city": "Frankfurt"},
    {"country": "BR", "city": "São Paulo"},
    {"country": "IN", "city": "Mumbai"},
    {"country": "NL", "city": "Amsterdam"},
    {"country": "GB", "city": "London"},
    {"country": "KR", "city": "Seoul"},
    {"country": "IR", "city": "Tehran"},
]

SEVERITIES = ["CRITICAL", "HIGH", "MEDIUM", "LOW"]
ACTIONS     = ["DENY", "ALERT", "MONITOR"]
STATUS_CODES = [200, 400, 403, 404, 429, 500]

# Round-robin index for IPS override
_ip_rr_index = 0


def _pick_ip(preferred_ip: str) -> str:
    """Return the next IP: round-robin from IPS override if set, else use preferred_ip."""
    global _ip_rr_index
    if IPS:
        ip = IPS[_ip_rr_index % len(IPS)]
        _ip_rr_index += 1
        return ip
    return preferred_ip


def _fmt_ts(dt: datetime) -> str:
    """Format a datetime as ISO 8601 with Z suffix (UTC)."""
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


def generate_event(ip: str, path: str, ts: datetime, event_id: str) -> dict:
    category = random.choice(list(RULE_TEMPLATES.keys()))
    template = RULE_TEMPLATES[category]
    severity = random.choice(SEVERITIES)

    event = {
        "eventId":     event_id,
        "timestamp":   _fmt_ts(ts),
        "configId":    random.choice([14227, 14228, 14229]),
        "policyId":    random.choice(["pol_web1", "pol_web2", "pol_api1"]),
        "clientIp":    _pick_ip(ip),
        "hostname":    random.choice(HOSTNAMES),
        "path":        path,
        "method":      random.choice(["GET", "POST", "PUT", "DELETE", "PATCH"]),
        "statusCode":  random.choice(STATUS_CODES),
        "userAgent":   random.choice(USER_AGENTS),
        "rule": {
            "id":       template["id"],
            "name":     template["name"],
            "message":  template["message"],
            "severity": severity,
            "category": category,
        },
        "action":        random.choice(ACTIONS),
        "requestSize":   random.randint(0, 8192),
        "responseSize":  random.randint(0, 4096),
    }

    if random.random() < 0.8:
        event["geoLocation"] = random.choice(GEO_DATA)

    return event


def make_wave_events(wave_index: int, ip: str, path: str, count: int, now: datetime) -> list:
    """
    Generate `count` events from `ip` → `path` clustered within the last 9 minutes
    relative to `now`. This ensures >5 hits in the 10-min sliding window and fires
    the repeat-offender +15 bonus in ThreatScoreCalculator.
    """
    window_seconds = 9 * 60
    events = []
    for i in range(count):
        offset_s = random.randint(0, window_seconds)
        ts = now - timedelta(seconds=offset_s)
        event_id = f"wave-{wave_index}-{i}"
        events.append(generate_event(ip, path, ts, event_id))
    return events


def make_background_events(count: int, now: datetime) -> list:
    """Generate `count` random events spread across the last 24 hours."""
    window_seconds = 24 * 3600
    events = []
    for i in range(count):
        offset_s = random.randint(0, window_seconds)
        ts = now - timedelta(seconds=offset_s)
        ip   = random.choice(CLIENT_IPS)
        path = random.choice(PATHS)
        events.append(generate_event(ip, path, ts, f"bg-{i}"))
    return events


def send_batch(session: requests.Session, batch: list) -> tuple[int, int]:
    """POST one batch; return (accepted, rejected)."""
    try:
        resp = session.post(TARGET_URL, json=batch, timeout=30)
        if resp.status_code == 201:
            body = resp.json()
            return body.get("accepted", 0), body.get("rejected", 0)
        print(f"\n[HTTP {resp.status_code}] {resp.text[:200]}")
        return 0, len(batch)
    except requests.RequestException as exc:
        print(f"\n[ERROR] {exc}")
        return 0, len(batch)


def _send_all(session: requests.Session, events: list, totals: dict) -> None:
    """Chunk events into BATCH_SIZE batches and send each."""
    for i in range(0, len(events), BATCH_SIZE):
        batch = events[i : i + BATCH_SIZE]
        acc, rej = send_batch(session, batch)
        totals["sent"]     += len(batch)
        totals["accepted"] += acc
        totals["rejected"] += rej
        print(".", end="", flush=True)


def main() -> None:
    # future: argparse can be wired here if CLI flags are needed
    totals = {"sent": 0, "accepted": 0, "rejected": 0}

    with requests.Session() as session:
        # --- Attack waves (sent as distinct sequential blocks) ---
        for w in range(ATTACK_WAVES):
            now  = datetime.now(tz=timezone.utc).replace(tzinfo=None)
            ip   = random.choice(CLIENT_IPS)
            path = random.choice([p for p in PATHS if "/admin" in p or "/login" in p] or PATHS)
            events = make_wave_events(w, ip, path, WAVE_SIZE, now)
            _send_all(session, events, totals)
            print(f"  Wave {w + 1}/{ATTACK_WAVES} done  ({ip} → {path})")
            if w < ATTACK_WAVES - 1 and WAVE_DELAY_S > 0:
                time.sleep(WAVE_DELAY_S)

        # --- Background traffic (shuffled) ---
        print(f"\nSending {EVENT_COUNT:,} background events...")
        bg_events = make_background_events(EVENT_COUNT, datetime.now(tz=timezone.utc).replace(tzinfo=None))
        random.shuffle(bg_events)
        _send_all(session, bg_events, totals)

    print(f"\n\nDone.")
    print(f"  Total sent:  {totals['sent']:>8,}")
    print(f"  Accepted:    {totals['accepted']:>8,}")
    print(f"  Rejected:    {totals['rejected']:>8,}")


if __name__ == "__main__":
    main()
