package com.tourbhook.api.service.venue;

import java.math.BigDecimal;

public record VenueCandidate(
        String placeId,
        String name,
        String category,
        String address,
        double latitude,
        double longitude,
        BigDecimal rating,
        Integer reviewsCount,
        String businessStatus,
        Boolean openNow
) {
}