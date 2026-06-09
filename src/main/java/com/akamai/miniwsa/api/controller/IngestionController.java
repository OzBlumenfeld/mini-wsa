package com.akamai.miniwsa.api.controller;

import com.akamai.miniwsa.api.dto.IngestionResponse;
import com.akamai.miniwsa.api.dto.SecurityEventRequest;
import com.akamai.miniwsa.api.exception.MalformedIngestionRequestException;
import com.akamai.miniwsa.service.IngestionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/events")
public class IngestionController {

    private static final int MAX_BATCH_SIZE = 500;

    private final IngestionService ingestionService;

    public IngestionController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    /**
     * Accepts a JSON array of up to 500 event objects. Returns {@code 201 Created} with
     * per-event results whenever the array is parseable — even if some/all events fail
     * validation. {@code 400 Bad Request} is returned for: malformed JSON, a non-array
     * body, an empty array, a batch exceeding 500 events, or a type/enum mismatch in any
     * event (Jackson fails the whole request before this method runs in those cases).
     */
    @PostMapping("/ingest")
    ResponseEntity<IngestionResponse> ingest(@RequestBody List<SecurityEventRequest> events) {
        if (events.isEmpty()) {
            throw new MalformedIngestionRequestException("Request body must be a non-empty array of events");
        }
        if (events.size() > MAX_BATCH_SIZE) {
            throw new MalformedIngestionRequestException(
                    "Batch size exceeds maximum of " + MAX_BATCH_SIZE + " events");
        }
        IngestionResponse response = ingestionService.ingest(events);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
