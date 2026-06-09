package com.akamai.miniwsa.api.controller;

import com.akamai.miniwsa.api.dto.samples.SamplesResponse;
import com.akamai.miniwsa.service.SamplesService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@Validated
@RestController
@RequestMapping("/v1/events")
public class SamplesController {

    private final SamplesService samplesService;

    public SamplesController(SamplesService samplesService) {
        this.samplesService = samplesService;
    }

    @GetMapping("/samples")
    public ResponseEntity<SamplesResponse> getSamples(
            @RequestParam(required = false) Long configId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return ResponseEntity.ok(
                samplesService.getSamples(configId, from, to, category, action, limit, offset));
    }
}
