package com.akamai.miniwsa.api.exception;

/**
 * Thrown when the ingestion request body cannot be interpreted as a single event object
 * or an array of event objects — i.e. nothing could be parsed into candidate events at all.
 * This is the only condition that yields a request-level {@code 400 Bad Request}; once the
 * body is recognized as event-shaped, per-event validation failures are reported in the
 * {@code 201} response body instead.
 */
public class MalformedIngestionRequestException extends RuntimeException {

    public MalformedIngestionRequestException(String message) {
        super(message);
    }
}
