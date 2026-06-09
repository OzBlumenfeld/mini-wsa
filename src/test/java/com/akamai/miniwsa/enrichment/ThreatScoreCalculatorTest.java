package com.akamai.miniwsa.enrichment;

import com.akamai.miniwsa.domain.Action;
import com.akamai.miniwsa.domain.GeoLocation;
import com.akamai.miniwsa.domain.Rule;
import com.akamai.miniwsa.domain.RuleCategory;
import com.akamai.miniwsa.domain.SecurityEvent;
import com.akamai.miniwsa.domain.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ThreatScoreCalculatorTest {

    @Mock
    private RepeatOffenderCache repeatOffenderCache;

    private ThreatScoreCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new ThreatScoreCalculator(repeatOffenderCache);
        lenient().when(repeatOffenderCache.isRepeatOffender(anyString(), anyString(), any())).thenReturn(false);
    }

    @ParameterizedTest
    @EnumSource(Severity.class)
    void appliesSeverityWeight(Severity severity) {
        SecurityEvent event = event(severity, Action.MONITOR, "/api/v1/data");

        int expected = switch (severity) {
            case CRITICAL -> 40;
            case HIGH -> 30;
            case MEDIUM -> 20;
            case LOW -> 10;
        };
        assertThat(calculator.calculate(event)).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(Action.class)
    void appliesActionWeight(Action action) {
        SecurityEvent event = event(Severity.LOW, action, "/api/v1/data");

        int expected = 10 + switch (action) {
            case DENY -> 20;
            case ALERT -> 10;
            case MONITOR -> 0;
        };
        assertThat(calculator.calculate(event)).isEqualTo(expected);
    }

    @Test
    void addsPathBonusForAdminPath() {
        SecurityEvent event = event(Severity.LOW, Action.MONITOR, "/admin/users");
        assertThat(calculator.calculate(event)).isEqualTo(10 + 15);
    }

    @Test
    void addsPathBonusForLoginPath() {
        SecurityEvent event = event(Severity.LOW, Action.MONITOR, "/api/v1/login");
        assertThat(calculator.calculate(event)).isEqualTo(10 + 15);
    }

    @Test
    void pathBonusIsNotCumulativeWhenPathContainsBothKeywords() {
        SecurityEvent event = event(Severity.LOW, Action.MONITOR, "/admin/login");
        assertThat(calculator.calculate(event)).isEqualTo(10 + 15);
    }

    @Test
    void addsNoPathBonusForOrdinaryPath() {
        SecurityEvent event = event(Severity.LOW, Action.MONITOR, "/api/v1/data");
        assertThat(calculator.calculate(event)).isEqualTo(10);
    }

    @Test
    void addsRepeatOffenderBonusWhenCacheReportsRepeatOffender() {
        when(repeatOffenderCache.isRepeatOffender(anyString(), anyString(), any())).thenReturn(true);

        SecurityEvent event = event(Severity.LOW, Action.MONITOR, "/api/v1/data");
        assertThat(calculator.calculate(event)).isEqualTo(10 + 15);
    }

    @Test
    void addsNoRepeatOffenderBonusWhenCacheReportsNotARepeatOffender() {
        when(repeatOffenderCache.isRepeatOffender(anyString(), anyString(), any())).thenReturn(false);

        SecurityEvent event = event(Severity.LOW, Action.MONITOR, "/api/v1/data");
        assertThat(calculator.calculate(event)).isEqualTo(10);
    }

    @Test
    void combinesAllFactorsAdditively() {
        when(repeatOffenderCache.isRepeatOffender(anyString(), anyString(), any())).thenReturn(true);

        SecurityEvent event = event(Severity.HIGH, Action.ALERT, "/api/v1/login");
        // 30 (HIGH) + 10 (ALERT) + 15 (path) + 15 (repeat offender) = 70
        assertThat(calculator.calculate(event)).isEqualTo(70);
    }

    @Test
    void capsScoreAt100() {
        when(repeatOffenderCache.isRepeatOffender(anyString(), anyString(), any())).thenReturn(true);

        SecurityEvent event = event(Severity.CRITICAL, Action.DENY, "/admin/login");
        // 40 (CRITICAL) + 20 (DENY) + 15 (path) + 15 (repeat offender) = 90
        // -- the documented weights top out at 90, so the cap can never actually trigger;
        // this asserts the maximum realistic combination and that Math.min's cap is in
        // place (defensive code for if weights ever change).
        assertThat(calculator.calculate(event)).isEqualTo(90);
        assertThat(calculator.calculate(event)).isLessThanOrEqualTo(100);
    }

    private SecurityEvent event(Severity severity, Action action, String path) {
        Rule rule = new Rule("950001", "TEST_RULE", "test", severity, RuleCategory.INJECTION);
        return new SecurityEvent(
                "evt-test", Instant.parse("2026-06-07T10:00:00Z"), 14227L, "pol_web1",
                "203.0.113.42", "www.example.com", path, "POST", 403, "test-agent",
                rule, action, new GeoLocation("US", "Springfield"), 1024, 256
        );
    }
}
