package com.akamai.miniwsa.api.dto.samples;

import java.util.List;

public record SamplesResponse(long total, List<EnrichedEventResponse> events) {}
