package com.tourbhook.api.dto.trip;

import java.time.LocalDate;
import java.util.List;

public record GeneratedTripResponse(
        String tripId,
        String itineraryId,
        String city,
        LocalDate startDate,
        LocalDate endDate,
        Integer days,
        String tripStyle,
        String purposeOfTravel,
        TripStartingPointDto starting_point,
        List<String> selected_places,
        String imageUrl

) {
}