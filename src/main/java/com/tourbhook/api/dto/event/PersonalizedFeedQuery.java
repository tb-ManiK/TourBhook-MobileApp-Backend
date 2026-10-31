package com.tourbhook.api.dto.event;

import java.time.LocalDate;

public record PersonalizedFeedQuery(
        Double lat,
        Double lng,
        Double radiusKm,
        LocalDate from,
        LocalDate to,
        boolean includeItinerary,
        String category,
        int page,
        int size
) {
}