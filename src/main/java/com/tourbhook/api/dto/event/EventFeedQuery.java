package com.tourbhook.api.dto.event;

import java.time.LocalDate;

public record EventFeedQuery(
        Double lat,
        Double lng,
        Double radiusKm,
        String city,
        LocalDate from,
        LocalDate to,
        String category,
        int page,
        int size
) {
}