package com.akamai.miniwsa.api.controller;

import com.akamai.miniwsa.api.dto.stats.StatsSummaryResponse;
import com.akamai.miniwsa.service.StatsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/v1/stats")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/summary")
    public ResponseEntity<StatsSummaryResponse> getSummary(
            @RequestParam(required = false) Long configId,
            @RequestParam Instant from,
            @RequestParam Instant to) {
        return ResponseEntity.ok(statsService.getSummary(configId, from, to));
    }
}
