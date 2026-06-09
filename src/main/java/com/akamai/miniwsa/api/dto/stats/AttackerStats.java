package com.akamai.miniwsa.api.dto.stats;

public record AttackerStats(String clientIp, long count, double avgThreatScore) {}
