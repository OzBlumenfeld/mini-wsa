package com.akamai.miniwsa.enrichment;

import com.akamai.miniwsa.domain.RuleCategory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class AttackClassifierTest {

    private final AttackClassifier classifier = new AttackClassifier();

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "INJECTION,          SQL/Command Injection",
            "XSS,                Cross-Site Scripting",
            "PROTOCOL_VIOLATION, Protocol Anomaly",
            "DATA_LEAKAGE,       Data Exfiltration",
            "BOT,                Bot Activity",
            "DOS,                Denial of Service",
            "RATE_LIMIT,         Rate Limiting"
    })
    void mapsEachCategoryToExpectedAttackType(RuleCategory category, String expectedAttackType) {
        assertThat(classifier.classify(category)).isEqualTo(expectedAttackType);
    }

    @org.junit.jupiter.api.Test
    void coversEveryRuleCategory() {
        for (RuleCategory category : RuleCategory.values()) {
            assertThat(classifier.classify(category)).isNotBlank();
        }
    }
}
