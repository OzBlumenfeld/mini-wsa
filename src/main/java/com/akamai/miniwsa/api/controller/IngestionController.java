package com.akamai.miniwsa.api.controller;

import com.akamai.miniwsa.api.dto.IngestionResponse;
import com.akamai.miniwsa.service.IngestionService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/events")
public class IngestionController {

    private final IngestionService ingestionService;

    public IngestionController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    /**
     * Accepts a single event object or a JSON array of event objects. Always returns
     * {@code 201 Created} with per-event results once the body is recognized as
     * event-shaped — {@code 400} is reserved for request-level parse failures and is
     * raised from the service/normalization step via {@link com.akamai.miniwsa.api.exception.GlobalExceptionHandler}.
     */
    @PostMapping("/ingest")
    ResponseEntity<IngestionResponse> ingest(@RequestBody JsonNode body) {
        IngestionResponse response = ingestionService.ingest(body);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
