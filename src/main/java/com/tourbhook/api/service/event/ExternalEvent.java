package com.tourbhook.api.service.event;

import java.time.LocalDate;
import java.time.LocalTime;

public record ExternalEvent(
        String externalId,
        String title,
        String description,
        String category,
        String venueName,
        String address,
        Double latitude,
        Double longitude,
        LocalDate startDate,
        LocalTime startTime,
        LocalDate endDate,
        String url,
        String imageUrl
) {
}