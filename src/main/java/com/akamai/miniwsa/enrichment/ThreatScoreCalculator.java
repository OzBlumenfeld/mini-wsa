package com.akamai.miniwsa.enrichment;

import com.akamai.miniwsa.domain.Action;
import com.akamai.miniwsa.domain.SecurityEvent;
import com.akamai.miniwsa.domain.Severity;
import org.springframework.stereotype.Component;

/**
 * Computes the 0-100 threat score by summing additive factors and capping the total
 * (REQUIREMENTS.md Part 2b).
 */
@Component
public class ThreatScoreCalculator {

    private static final int MAX_SCORE = 100;
    private static final int PATH_BONUS = 15;
    private static final int REPEAT_OFFENDER_BONUS = 15;

    private final RepeatOffenderCache repeatOffenderCache;

    public ThreatScoreCalculator(RepeatOffenderCache repeatOffenderCache) {
        this.repeatOffenderCache = repeatOffenderCache;
    }

    public int calculate(SecurityEvent event) {
        int score = severityScore(event.rule().severity())
                + actionScore(event.action())
                + pathBonus(event.path())
                + repeatOffenderBonus(event);
        return Math.min(score, MAX_SCORE);
    }

    private int severityScore(Severity severity) {
        return switch (severity) {
            case CRITICAL -> 40;
            case HIGH -> 30;
            case MEDIUM -> 20;
            case LOW -> 10;
        };
    }

    private int actionScore(Action action) {
        return switch (action) {
            case DENY -> 20;
            case ALERT -> 10;
            case MONITOR -> 0;
        };
    }

    private int pathBonus(String path) {
        return (path.contains("/admin") || path.contains("/login")) ? PATH_BONUS : 0;
    }

    private int repeatOffenderBonus(SecurityEvent event) {
        boolean repeatOffender = repeatOffenderCache.isRepeatOffender(
                event.clientIp(), event.eventId(), event.timestamp());
        return repeatOffender ? REPEAT_OFFENDER_BONUS : 0;
    }
}
