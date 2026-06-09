package com.akamai.miniwsa.api.dto;

public record GeoLocationRequest(
        String country,
        String city
) {
}
