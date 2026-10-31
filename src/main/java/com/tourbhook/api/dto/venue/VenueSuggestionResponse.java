package com.tourbhook.api.dto.venue;

import java.math.BigDecimal;
import java.util.List;

public record VenueSuggestionResponse(
        VenueSuggestionStatus status,
        String message,
        VenueResponse venue,
        List<VenueResponse> alternatives,
        int searchedRadiusMeters,
        BigDecimal minRating
) {
}