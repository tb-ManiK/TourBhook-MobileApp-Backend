package com.tourbhook.api.dto.event;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record EventFeedResponse(
        List<EventResponse> events,
        int page,
        int size,
        long totalElements,
        int totalPages,
        LocalDate from,
        LocalDate to,
        LocalDateTime lastRefreshedAt
) {
}