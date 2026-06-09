package com.akamai.miniwsa.api.dto;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum IngestionEventStatus {
    ACCEPTED,
    REJECTED;

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }
}
