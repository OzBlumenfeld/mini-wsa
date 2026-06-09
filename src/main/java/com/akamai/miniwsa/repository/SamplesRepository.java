package com.akamai.miniwsa.repository;

import com.akamai.miniwsa.api.dto.samples.EnrichedEventResponse;

import java.time.Instant;
import java.util.List;

public interface SamplesRepository {

    List<EnrichedEventResponse> querySamples(Long configId, Instant from, Instant to,
                                              String category, String action,
                                              int limit, int offset);

    long countSamples(Long configId, Instant from, Instant to, String category, String action);
}
