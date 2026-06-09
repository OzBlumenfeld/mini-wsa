package com.akamai.miniwsa.integration;

import com.akamai.miniwsa.api.dto.GeoLocationRequest;
import com.akamai.miniwsa.api.dto.IngestionResponse;
import com.akamai.miniwsa.api.dto.RuleRequest;
import com.akamai.miniwsa.api.dto.SecurityEventRequest;
import com.akamai.miniwsa.api.dto.samples.EnrichedEventResponse;
import com.akamai.miniwsa.api.dto.samples.SamplesResponse;
import com.akamai.miniwsa.api.dto.stats.AttackerStats;
import com.akamai.miniwsa.api.dto.stats.CategoryStats;
import com.akamai.miniwsa.api.dto.stats.PathStats;
import com.akamai.miniwsa.api.dto.stats.StatsSummaryResponse;
import com.akamai.miniwsa.domain.Action;
import com.akamai.miniwsa.domain.RuleCategory;
import com.akamai.miniwsa.domain.Severity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * End-to-end integration test: ingests a deterministic dataset via POST /v1/events/ingest
 * and asserts exact expected values from /v1/stats/summary and /v1/events/samples.
 *
 * ── Dataset ──────────────────────────────────────────────────────────────────
 * BASE = 2026-06-09T10:00:00Z. Events are 1 minute apart (T+N minutes).
 *
 * configId=100 — 12 events:
 *   e01 (1.1.1.1, T+0,  CRITICAL/DENY,    /api/data,    INJECTION)          score=60
 *   e02 (1.1.1.1, T+1,  HIGH/ALERT,       /api/data,    XSS)                score=40
 *   e03 (1.1.1.1, T+2,  MEDIUM/DENY,      /api/data,    INJECTION)          score=40
 *   e04 (1.1.1.1, T+3,  LOW/MONITOR,      /login,       BOT)                score=25
 *   e05 (1.1.1.1, T+4,  HIGH/DENY,        /login,       INJECTION)          score=65
 *   e06 (1.1.1.1, T+5,  CRITICAL/ALERT,   /api/data,    DOS)   +repeat      score=65
 *   e07 (2.2.2.2, T+6,  CRITICAL/DENY,    /admin,       INJECTION)          score=75
 *   e08 (2.2.2.2, T+7,  HIGH/ALERT,       /admin,       XSS)                score=55
 *   e09 (2.2.2.2, T+8,  MEDIUM/DENY,      /login,       PROTOCOL_VIOLATION) score=55
 *   e10 (3.3.3.3, T+9,  LOW/MONITOR,      /api/data,    BOT)                score=10
 *   e11 (3.3.3.3, T+10, HIGH/DENY,        /api/data,    DATA_LEAKAGE)       score=50
 *   e12 (4.4.4.4, T+11, CRITICAL/DENY,    /api/secret,  RATE_LIMIT)         score=60
 *
 * configId=200 — 3 events (placed at T+13–T+15 so configId=100 queries at T+12 are unaffected):
 *   e13 (5.5.5.5, T+13, HIGH/DENY,        /api/v2/data, XSS)                score=50
 *   e14 (5.5.5.5, T+14, CRITICAL/DENY,    /login,       INJECTION)          score=75
 *   e15 (5.5.5.5, T+15, LOW/MONITOR,      /api/v2/data, BOT)                score=10
 *
 * Score rules: severity(CRITICAL=40, HIGH=30, MEDIUM=20, LOW=10)
 *            + action(DENY=20, ALERT=10, MONITOR=0)
 *            + pathBonus(+15 if /admin or /login)
 *            + repeatBonus(+15 if 6th+ event from same IP in 10-min window), capped at 100.
 * e06 is the 6th event from 1.1.1.1 within 5 minutes → repeat bonus applies.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnalyticsEndpointIntegrationTest {

    private static final Instant BASE       = Instant.parse("2026-06-09T10:00:00Z");
    private static final long    CONFIG_ID  = 100L;
    private static final long    CONFIG_ID2 = 200L;

    // Containers are started eagerly in a static initializer so they are guaranteed
    // to be running before @DynamicPropertySource resolves mapped ports — otherwise
    // Spring Boot's property binding runs before @Testcontainers.beforeAll() fires.
    @SuppressWarnings("resource")
    static final GenericContainer<?> CLICKHOUSE =
            new GenericContainer<>("clickhouse/clickhouse-server:24.5-alpine")
                    .withExposedPorts(8123)
                    .waitingFor(Wait.forHttp("/ping").forPort(8123));

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine")
                    .withExposedPorts(6379);

    static {
        CLICKHOUSE.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("miniwsa.clickhouse.url", () ->
                "jdbc:ch://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123) + "/default");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    TestRestTemplate rest;

    @BeforeAll
    void ingestTestData() {
        ResponseEntity<IngestionResponse> resp =
                rest.postForEntity("/v1/events/ingest", buildDataset(), IngestionResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Stats — configId=100 (primary dataset, 12 events in T+0..T+11)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void stats_totalEvents_is12() {
        assertThat(fetchStats100().totalEvents()).isEqualTo(12);
    }

    @Test
    void stats_byCategory_countsAndAverageThreatScores() {
        Map<String, CategoryStats> byCategory = fetchStats100().byCategory();

        assertCategory(byCategory, "INJECTION",          4, 60.00); // (60+40+65+75)/4
        assertCategory(byCategory, "XSS",                2, 47.50); // (40+55)/2
        assertCategory(byCategory, "BOT",                2, 17.50); // (25+10)/2
        assertCategory(byCategory, "DOS",                1, 65.00);
        assertCategory(byCategory, "PROTOCOL_VIOLATION", 1, 55.00);
        assertCategory(byCategory, "DATA_LEAKAGE",       1, 50.00);
        assertCategory(byCategory, "RATE_LIMIT",         1, 60.00);
    }

    @Test
    void stats_byAction_counts() {
        Map<String, Long> byAction = fetchStats100().byAction();

        assertThat(byAction)
                .containsEntry("DENY",    7L)  // e01,e03,e05,e07,e09,e11,e12
                .containsEntry("ALERT",   3L)  // e02,e06,e08
                .containsEntry("MONITOR", 2L); // e04,e10
    }

    @Test
    void stats_topAttackers_orderedByCountDesc() {
        List<AttackerStats> attackers = fetchStats100().topAttackers();

        assertThat(attackers).hasSize(4);
        assertAttacker(attackers.get(0), "1.1.1.1", 6, 295.0 / 6); // 60+40+40+25+65+65
        assertAttacker(attackers.get(1), "2.2.2.2", 3, 185.0 / 3); // 75+55+55
        assertAttacker(attackers.get(2), "3.3.3.3", 2, 30.0);      // (10+50)/2
        assertAttacker(attackers.get(3), "4.4.4.4", 1, 60.0);
    }

    @Test
    void stats_topTargetedPaths_orderedByCountDesc() {
        List<PathStats> paths = fetchStats100().topTargetedPaths();

        assertThat(paths).hasSize(4);
        assertPath(paths.get(0), "/api/data",   6); // e01,e02,e03,e06,e10,e11
        assertPath(paths.get(1), "/login",      3); // e04,e05,e09
        assertPath(paths.get(2), "/admin",      2); // e07,e08
        assertPath(paths.get(3), "/api/secret", 1); // e12
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Stats — configId isolation & cross-configId aggregation
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void stats_configId200_totalIs3AndAggregatesCorrectly() {
        // configId=200 has e13(XSS,50) e14(INJECTION,75) e15(BOT,10) — all from 5.5.5.5
        StatsSummaryResponse s = fetchStats(CONFIG_ID2);

        assertThat(s.totalEvents()).isEqualTo(3);
        assertCategory(s.byCategory(), "XSS",       1, 50.0);
        assertCategory(s.byCategory(), "INJECTION",  1, 75.0);
        assertCategory(s.byCategory(), "BOT",        1, 10.0);
        assertThat(s.byAction()).containsEntry("DENY", 2L).containsEntry("MONITOR", 1L);
        assertThat(s.topAttackers()).hasSize(1);
        assertAttacker(s.topAttackers().get(0), "5.5.5.5", 3, 45.0); // (50+75+10)/3
        assertThat(s.topTargetedPaths().get(0).path()).isEqualTo("/api/v2/data"); // 2 hits
        assertThat(s.topTargetedPaths().get(1).path()).isEqualTo("/login");       // 1 hit
    }

    @Test
    void stats_noConfigId_aggregatesAllEvents() {
        // 12 (configId=100) + 3 (configId=200) = 15
        StatsSummaryResponse s = fetchStats(null);
        assertThat(s.totalEvents()).isEqualTo(15);
    }

    @Test
    void stats_unknownConfigId_returnsEmptyAggregations() {
        StatsSummaryResponse s = fetchStats(999L);

        assertThat(s.totalEvents()).isZero();
        assertThat(s.byCategory()).isEmpty();
        assertThat(s.byAction()).isEmpty();
        assertThat(s.topAttackers()).isEmpty();
        assertThat(s.topTargetedPaths()).isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Samples — full-dataset pagination
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void samples_page1_returnsNewestFourEventsDescending() {
        SamplesResponse page = fetchSamples100("&limit=4&offset=0");

        assertThat(page.total()).isEqualTo(12);
        assertThat(page.events()).hasSize(4);
        assertDescendingTimestamps(page.events());
        assertThat(page.events().get(0).timestamp()).isEqualTo(BASE.plusSeconds(11 * 60));
        assertThat(page.events().get(0).clientIp()).isEqualTo("4.4.4.4");
    }

    @Test
    void samples_page2_returnsMiddleFourEventsDescending() {
        SamplesResponse page = fetchSamples100("&limit=4&offset=4");

        assertThat(page.total()).isEqualTo(12);
        assertThat(page.events()).hasSize(4);
        assertDescendingTimestamps(page.events());
        assertThat(page.events().get(0).timestamp()).isEqualTo(BASE.plusSeconds(7 * 60)); // e08
        assertThat(page.events().get(3).timestamp()).isEqualTo(BASE.plusSeconds(4 * 60)); // e05
    }

    @Test
    void samples_page3_returnsOldestFourEventsDescending() {
        SamplesResponse page = fetchSamples100("&limit=4&offset=8");

        assertThat(page.total()).isEqualTo(12);
        assertThat(page.events()).hasSize(4);
        assertDescendingTimestamps(page.events());
        assertThat(page.events().get(3).timestamp()).isEqualTo(BASE);
        assertThat(page.events().get(3).clientIp()).isEqualTo("1.1.1.1");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Samples — limit / offset edge cases
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void samples_limit1_returnsOnlyMostRecentEvent() {
        SamplesResponse page = fetchSamples100("&limit=1&offset=0");

        assertThat(page.total()).isEqualTo(12);
        assertThat(page.events()).hasSize(1);
        assertThat(page.events().get(0).timestamp()).isEqualTo(BASE.plusSeconds(11 * 60));
    }

    @Test
    void samples_offsetBeyondTotal_returnsEmptyList() {
        SamplesResponse page = fetchSamples100("&limit=10&offset=12");

        assertThat(page.total()).isEqualTo(12);
        assertThat(page.events()).isEmpty();
    }

    @Test
    void samples_unknownConfigId_returnsTotalZeroAndEmptyList() {
        SamplesResponse page = fetchSamplesRaw("?configId=999&limit=10");

        assertThat(page.total()).isZero();
        assertThat(page.events()).isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Samples — time range filter
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void samples_timeRangeFilter_returnsOnlyEventsWithinBounds() {
        // T+6 to T+11 covers e07–e12 exactly (6 events)
        Instant from = BASE.plusSeconds(6 * 60);
        Instant to   = BASE.plusSeconds(11 * 60);
        SamplesResponse page = fetchSamplesRaw(
                "?configId=" + CONFIG_ID + "&from=" + from + "&to=" + to + "&limit=10");

        assertThat(page.total()).isEqualTo(6);
        assertThat(page.events()).hasSize(6);
        assertDescendingTimestamps(page.events());
        assertThat(page.events().get(0).timestamp()).isEqualTo(to);   // newest: e12@T+11
        assertThat(page.events().get(5).timestamp()).isEqualTo(from); // oldest: e07@T+6
    }

    @Test
    void samples_noTimeRange_returnsAllEventsForConfigId() {
        // No from/to → the endpoint treats them as optional; all 12 configId=100 events returned
        SamplesResponse page = fetchSamplesRaw("?configId=" + CONFIG_ID + "&limit=20");

        assertThat(page.total()).isEqualTo(12);
        assertThat(page.events()).hasSize(12);
        assertDescendingTimestamps(page.events());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Samples — single-filter queries
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void samples_filteredByInjection_returns4EventsDescending() {
        SamplesResponse page = fetchSamples100("&category=INJECTION&limit=10&offset=0");

        assertThat(page.total()).isEqualTo(4);
        assertThat(page.events()).hasSize(4);
        assertDescendingTimestamps(page.events());
        assertThat(page.events().get(0).timestamp()).isEqualTo(BASE.plusSeconds(6 * 60)); // e07
        assertThat(page.events().get(3).timestamp()).isEqualTo(BASE);                     // e01
    }

    @Test
    void samples_filteredByInjection_paginatesCorrectly() {
        SamplesResponse page = fetchSamples100("&category=INJECTION&limit=2&offset=2");

        assertThat(page.total()).isEqualTo(4);
        assertThat(page.events()).hasSize(2);
        // offset=2 skips e07+e05, returns [e03@T+2, e01@T+0]
        assertThat(page.events().get(0).timestamp()).isEqualTo(BASE.plusSeconds(2 * 60));
        assertThat(page.events().get(1).timestamp()).isEqualTo(BASE);
    }

    @Test
    void samples_filteredByMonitor_returns2EventsDescending() {
        SamplesResponse page = fetchSamples100("&action=MONITOR&limit=10&offset=0");

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.events()).hasSize(2);
        assertThat(page.events().get(0).timestamp()).isEqualTo(BASE.plusSeconds(9 * 60)); // e10
        assertThat(page.events().get(1).timestamp()).isEqualTo(BASE.plusSeconds(3 * 60)); // e04
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Samples — combined filter (category AND action)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void samples_combinedCategoryAndActionFilter_returnsIntersectionOnly() {
        // BOT + MONITOR: only e04@T+3 and e10@T+9 (not other BOT events if any had different action)
        SamplesResponse page = fetchSamples100("&category=BOT&action=MONITOR&limit=10");

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.events()).hasSize(2);
        assertThat(page.events().get(0).timestamp()).isEqualTo(BASE.plusSeconds(9 * 60)); // e10
        assertThat(page.events().get(1).timestamp()).isEqualTo(BASE.plusSeconds(3 * 60)); // e04
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Samples — enriched field values (attackType + threatScore per event)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void samples_injectionEvent_hasCorrectAttackTypeAndThreatScore() {
        // e07: CRITICAL/DENY + /admin(+15) = 75; INJECTION → "SQL/Command Injection"
        Instant t6 = BASE.plusSeconds(6 * 60);
        SamplesResponse page = fetchSamplesRaw(
                "?configId=" + CONFIG_ID + "&from=" + t6 + "&to=" + t6
                + "&category=INJECTION&limit=1");

        assertThat(page.total()).isEqualTo(1);
        EnrichedEventResponse e = page.events().get(0);
        assertThat(e.clientIp()).isEqualTo("2.2.2.2");
        assertThat(e.threatScore()).isEqualTo(75);
        assertThat(e.attackType()).isEqualTo("SQL/Command Injection");
        assertThat(e.action()).isEqualTo("DENY");
        assertThat(e.path()).isEqualTo("/admin");
    }

    @Test
    void samples_repeatOffenderEvent_hasRepeatBonusInThreatScore() {
        // e06: CRITICAL(40) + ALERT(10) + repeat-offender bonus(15) = 65; DOS → "Denial of Service"
        // category=DOS is unique in the dataset so no extra filter needed
        SamplesResponse page = fetchSamples100("&category=DOS&limit=1");

        assertThat(page.total()).isEqualTo(1);
        EnrichedEventResponse e = page.events().get(0);
        assertThat(e.clientIp()).isEqualTo("1.1.1.1");
        assertThat(e.threatScore()).isEqualTo(65);
        assertThat(e.attackType()).isEqualTo("Denial of Service");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Samples — configId=200 isolation
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void samples_configId200_returnsOnly3EventsDescending() {
        // No from/to so we don't need to know the exact timestamp range
        SamplesResponse page = fetchSamplesRaw("?configId=" + CONFIG_ID2 + "&limit=10");

        assertThat(page.total()).isEqualTo(3);
        assertThat(page.events()).hasSize(3);
        assertDescendingTimestamps(page.events());
        // All 3 events are from 5.5.5.5
        page.events().forEach(ev -> assertThat(ev.clientIp()).isEqualTo("5.5.5.5"));
        // Most recent is e15@T+15
        assertThat(page.events().get(0).timestamp()).isEqualTo(BASE.plusSeconds(15 * 60));
        // Oldest is e13@T+13
        assertThat(page.events().get(2).timestamp()).isEqualTo(BASE.plusSeconds(13 * 60));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    /** Stats for configId=100 over the full 12-minute dataset window. */
    private StatsSummaryResponse fetchStats100() {
        return fetchStats(CONFIG_ID);
    }

    /** Stats for a given configId (null = no filter) over a 16-minute window covering all events. */
    private StatsSummaryResponse fetchStats(Long configId) {
        String url = "/v1/stats/summary?from=" + BASE + "&to=" + BASE.plusSeconds(16 * 60)
                + (configId != null ? "&configId=" + configId : "");
        ResponseEntity<StatsSummaryResponse> resp =
                rest.getForEntity(url, StatsSummaryResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    /** Samples for configId=100 with from=T and to=T+12min, plus any extra params. */
    private SamplesResponse fetchSamples100(String extraParams) {
        String url = "/v1/events/samples?configId=" + CONFIG_ID
                + "&from=" + BASE
                + "&to=" + BASE.plusSeconds(12 * 60)
                + extraParams;
        ResponseEntity<SamplesResponse> resp =
                rest.getForEntity(url, SamplesResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    /** Samples with a fully custom query string (caller controls all params). */
    private SamplesResponse fetchSamplesRaw(String query) {
        ResponseEntity<SamplesResponse> resp =
                rest.getForEntity("/v1/events/samples" + query, SamplesResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private static void assertCategory(Map<String, CategoryStats> map,
                                       String key, long expectedCount, double expectedAvg) {
        assertThat(map).containsKey(key);
        CategoryStats cs = map.get(key);
        assertThat(cs.count()).isEqualTo(expectedCount);
        assertThat(cs.avgThreatScore()).isCloseTo(expectedAvg, within(0.01));
    }

    private static void assertAttacker(AttackerStats actual, String ip,
                                       long count, double avgScore) {
        assertThat(actual.clientIp()).isEqualTo(ip);
        assertThat(actual.count()).isEqualTo(count);
        assertThat(actual.avgThreatScore()).isCloseTo(avgScore, within(0.01));
    }

    private static void assertPath(PathStats actual, String path, long count) {
        assertThat(actual.path()).isEqualTo(path);
        assertThat(actual.count()).isEqualTo(count);
    }

    private static void assertDescendingTimestamps(List<EnrichedEventResponse> events) {
        for (int i = 0; i < events.size() - 1; i++) {
            assertThat(events.get(i).timestamp())
                    .as("events[%d] must be newer than events[%d]", i, i + 1)
                    .isAfter(events.get(i + 1).timestamp());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Dataset
    // ═══════════════════════════════════════════════════════════════════════════

    private static List<SecurityEventRequest> buildDataset() {
        return List.of(
                // ── configId=100 ─────────────────────────────────────────────
                // 1.1.1.1 — 6 events within 5-minute window; event 6 triggers repeat-offender bonus
                ev("e01", CONFIG_ID,  "1.1.1.1",  0, Severity.CRITICAL, Action.DENY,    "/api/data",    RuleCategory.INJECTION),
                ev("e02", CONFIG_ID,  "1.1.1.1",  1, Severity.HIGH,     Action.ALERT,   "/api/data",    RuleCategory.XSS),
                ev("e03", CONFIG_ID,  "1.1.1.1",  2, Severity.MEDIUM,   Action.DENY,    "/api/data",    RuleCategory.INJECTION),
                ev("e04", CONFIG_ID,  "1.1.1.1",  3, Severity.LOW,      Action.MONITOR, "/login",       RuleCategory.BOT),
                ev("e05", CONFIG_ID,  "1.1.1.1",  4, Severity.HIGH,     Action.DENY,    "/login",       RuleCategory.INJECTION),
                ev("e06", CONFIG_ID,  "1.1.1.1",  5, Severity.CRITICAL, Action.ALERT,   "/api/data",    RuleCategory.DOS),
                // 2.2.2.2
                ev("e07", CONFIG_ID,  "2.2.2.2",  6, Severity.CRITICAL, Action.DENY,    "/admin",       RuleCategory.INJECTION),
                ev("e08", CONFIG_ID,  "2.2.2.2",  7, Severity.HIGH,     Action.ALERT,   "/admin",       RuleCategory.XSS),
                ev("e09", CONFIG_ID,  "2.2.2.2",  8, Severity.MEDIUM,   Action.DENY,    "/login",       RuleCategory.PROTOCOL_VIOLATION),
                // 3.3.3.3 and 4.4.4.4
                ev("e10", CONFIG_ID,  "3.3.3.3",  9, Severity.LOW,      Action.MONITOR, "/api/data",    RuleCategory.BOT),
                ev("e11", CONFIG_ID,  "3.3.3.3", 10, Severity.HIGH,     Action.DENY,    "/api/data",    RuleCategory.DATA_LEAKAGE),
                ev("e12", CONFIG_ID,  "4.4.4.4", 11, Severity.CRITICAL, Action.DENY,    "/api/secret",  RuleCategory.RATE_LIMIT),
                // ── configId=200 (T+13..T+15, outside the T+12 ceiling used by configId=100 tests) ──
                ev("e13", CONFIG_ID2, "5.5.5.5", 13, Severity.HIGH,     Action.DENY,    "/api/v2/data", RuleCategory.XSS),
                ev("e14", CONFIG_ID2, "5.5.5.5", 14, Severity.CRITICAL, Action.DENY,    "/login",       RuleCategory.INJECTION),
                ev("e15", CONFIG_ID2, "5.5.5.5", 15, Severity.LOW,      Action.MONITOR, "/api/v2/data", RuleCategory.BOT)
        );
    }

    private static SecurityEventRequest ev(String id, long configId, String ip, int minuteOffset,
                                           Severity severity, Action action,
                                           String path, RuleCategory category) {
        String timestamp = BASE.plusSeconds(minuteOffset * 60L).toString();
        return new SecurityEventRequest(
                id, timestamp, configId, "pol-1", ip, "host.example.com",
                path, "GET", 200, "TestAgent/1.0",
                new RuleRequest("rule-" + id, "Rule " + id, "Test rule " + id, severity, category),
                action, new GeoLocationRequest("US", "New York"), 512, 1024);
    }
}
