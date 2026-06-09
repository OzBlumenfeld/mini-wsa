package com.akamai.miniwsa.enrichment;

import com.akamai.miniwsa.domain.RuleCategory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Maps {@code rule.category} to a human-readable {@code attackType} (REQUIREMENTS.md Part 2a).
 * Pure, stateless, O(1) lookup — no branching.
 */
@Component
public class AttackClassifier {

    private static final Map<RuleCategory, String> ATTACK_TYPES = new EnumMap<>(Map.of(
            RuleCategory.INJECTION, "SQL/Command Injection",
            RuleCategory.XSS, "Cross-Site Scripting",
            RuleCategory.PROTOCOL_VIOLATION, "Protocol Anomaly",
            RuleCategory.DATA_LEAKAGE, "Data Exfiltration",
            RuleCategory.BOT, "Bot Activity",
            RuleCategory.DOS, "Denial of Service",
            RuleCategory.RATE_LIMIT, "Rate Limiting"
    ));

    public String classify(RuleCategory category) {
        String attackType = ATTACK_TYPES.get(category);
        if (attackType == null) {
            throw new IllegalStateException("No attackType mapping defined for category: " + category);
        }
        return attackType;
    }
}
