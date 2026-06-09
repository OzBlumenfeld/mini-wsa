package com.akamai.miniwsa.enrichment;

import com.akamai.miniwsa.domain.EnrichedEvent;
import com.akamai.miniwsa.domain.SecurityEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Orchestrates classification, threat scoring, and the {@code receivedAt} stamp into a
 * single enrichment step (REQUIREMENTS.md Part 2). Designed so further enrichment steps
 * can be added here without touching the classifier/calculator.
 */
@Component
public class EnrichmentPipeline {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentPipeline.class);

    private final AttackClassifier attackClassifier;
    private final ThreatScoreCalculator threatScoreCalculator;
    private final Clock clock;

    public EnrichmentPipeline(AttackClassifier attackClassifier,
                              ThreatScoreCalculator threatScoreCalculator,
                              Clock clock) {
        this.attackClassifier = attackClassifier;
        this.threatScoreCalculator = threatScoreCalculator;
        this.clock = clock;
    }

    public EnrichedEvent enrich(SecurityEvent event) {
        String attackType = attackClassifier.classify(event.rule().category());
        int threatScore = threatScoreCalculator.calculate(event);
        Instant receivedAt = Instant.now(clock);
        log.debug("Enriched eventId={} attackType='{}' threatScore={}", event.eventId(), attackType, threatScore);
        return new EnrichedEvent(event, attackType, threatScore, receivedAt);
    }
}
