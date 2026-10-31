package com.tourbhook.api.dto.venue;

import java.math.BigDecimal;

public record VenueResponse(
        String placeId,
        String name,
        String category,
        String address,
        double latitude,
        double longitude,
        BigDecimal rating,
        Integer reviewsCount,
        int distanceMeters,
        Boolean openNow
) {
}