package com.tourbhook.api.dto.event;

import java.time.LocalDateTime;
import java.util.List;

public record PersonalizedEventFeedResponse(
        List<PersonalizedEventResponse> events,
        int page,
        int size,
        long totalElements,
        int totalPages,
        int itineraryTripsConsidered,
        LocalDateTime lastRefreshedAt
) {
}