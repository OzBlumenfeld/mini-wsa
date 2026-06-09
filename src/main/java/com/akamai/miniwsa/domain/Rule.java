package com.akamai.miniwsa.domain;

public record Rule(
        String id,
        String name,
        String message,
        Severity severity,
        RuleCategory category
) {
}
