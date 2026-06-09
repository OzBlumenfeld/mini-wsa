package com.akamai.miniwsa.service;

import com.akamai.miniwsa.api.dto.samples.EnrichedEventResponse;
import com.akamai.miniwsa.api.dto.samples.SamplesResponse;
import com.akamai.miniwsa.repository.SamplesRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class SamplesService {

    private final SamplesRepository samplesRepository;

    public SamplesService(SamplesRepository samplesRepository) {
        this.samplesRepository = samplesRepository;
    }

    public SamplesResponse getSamples(Long configId, Instant from, Instant to,
                                       String category, String action,
                                       int limit, int offset) {
        long total = samplesRepository.countSamples(configId, from, to, category, action);
        List<EnrichedEventResponse> events = samplesRepository.querySamples(
                configId, from, to, category, action, limit, offset);
        return new SamplesResponse(total, events);
    }
}
