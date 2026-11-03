package com.tourbhook.api.dto.itinerary;

import java.util.List;

public record ItineraryResponse(
        String id,
        String tripId,
        String city,
        String title,
        
        List<ItineraryDayResponse> days,
        String imageUrl
) {
}
