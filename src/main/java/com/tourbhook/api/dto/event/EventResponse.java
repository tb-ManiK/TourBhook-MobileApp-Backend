package com.tourbhook.api.dto.event;

import java.time.LocalDate;
import java.time.LocalTime;

public record EventResponse(
        String id,
        String title,
        String description,
        String category,
        String venueName,
        String address,
        String city,
        Double latitude,
        Double longitude,
        LocalDate startDate,
        LocalDate endDate,
        LocalTime startTime,
        String url,
        String imageUrl,
        String source,
        Double distanceKm
) {
}