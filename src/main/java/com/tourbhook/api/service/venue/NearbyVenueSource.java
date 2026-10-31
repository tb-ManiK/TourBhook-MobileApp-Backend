package com.tourbhook.api.service.venue;

import com.tourbhook.api.config.VenueSuggestionProperties;
import com.tourbhook.api.repository.exception.ExternalServiceException;
import com.tourbhook.api.service.impl.GoogleMapsClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class NearbyVenueSource {

    private final GoogleMapsClient googleMapsClient;
    private final VenueSuggestionProperties properties;

    private static final Set<String> ALLOWED_VENUE_TYPES = Set.of(
            "restaurant",
            "cafe",
            "coffee_shop",
            "fast_food_restaurant",
            "sandwich_shop",
            "meal_takeaway",
            "meal_delivery",
            "bakery",
            "brunch_restaurant",
            "breakfast_restaurant",
            "diner",
            "food_court"
    );

    public List<VenueCandidate> findNearby(
            double latitude, double longitude, int radiusMeters) {

        List<String> venueTypes = properties.getVenueTypes();
        Map<String, VenueCandidate> byPlaceId = new LinkedHashMap<>();
        int failedLookups = 0;

        for (String type : venueTypes) {
            Map<String, Object> response =
                    googleMapsClient.nearbySearch(
                            latitude, longitude, radiusMeters, type);

            if (response == null
                    || response.isEmpty()
                    || response.containsKey("error")) {
                failedLookups++;
                log.warn("Google Places lookup failed for type '{}'", type);
                continue;
            }

            Object rawPlaces = response.get("places");

            // A successful response with no places is valid.
            if (rawPlaces == null) {
                log.warn("Google Places response has no 'places' field for type '{}'",
                        type);
                failedLookups++;
                continue;
            }

            if (!(rawPlaces instanceof List<?> places)) {
                failedLookups++;
                log.warn("Unexpected Google Places response for type '{}'", type);
                continue;
            }

//            for (Object rawPlace : places) {
//                parse(rawPlace).ifPresent(
//                        venue -> byPlaceId.putIfAbsent(venue.placeId(), venue));
//            }

            for (Object rawPlace : places) {
                parse(rawPlace)
                        .filter(venue -> venue.category() != null)
                        .filter(venue -> {
                            String category = venue.category().toLowerCase();
                            return ALLOWED_VENUE_TYPES.contains(category)
                                    || category.endsWith("_restaurant");
                        })
                        .ifPresent(venue ->
                                byPlaceId.putIfAbsent(venue.placeId(), venue));
            }
        }

        if (!venueTypes.isEmpty() && failedLookups == venueTypes.size()) {
            throw new ExternalServiceException("Nearby venue search failed");
        }

        return List.copyOf(byPlaceId.values());
    }

    private Optional<VenueCandidate> parse(Object rawPlace) {
        if (!(rawPlace instanceof Map<?, ?> place)) {
            return Optional.empty();
        }

        String placeId = text(place.get("id"));

        String name = null;
        if (place.get("displayName") instanceof Map<?, ?> displayName) {
            name = text(displayName.get("text"));
        }

        Double latitude = null;
        Double longitude = null;

        if (place.get("location") instanceof Map<?, ?> location) {
            latitude = number(location.get("latitude"));
            longitude = number(location.get("longitude"));
        }

        if (placeId == null || name == null
                || latitude == null || longitude == null) {
            return Optional.empty();
        }

        String category = text(place.get("primaryType"));

        if (category == null
                && place.get("types") instanceof List<?> types
                && !types.isEmpty()) {
            category = text(types.get(0));
        }

        String address = text(place.get("formattedAddress"));
        String businessStatus = text(place.get("businessStatus"));

        BigDecimal rating = place.get("rating") instanceof Number value
                ? BigDecimal.valueOf(value.doubleValue())
                : null;

        Integer reviewsCount =
                place.get("userRatingCount") instanceof Number value
                        ? value.intValue()
                        : null;

        Boolean openNow = null;

        if (place.get("currentOpeningHours")
                instanceof Map<?, ?> openingHours
                && openingHours.get("openNow") instanceof Boolean value) {
            openNow = value;
        }

        return Optional.of(new VenueCandidate(
                placeId,
                name,
                category,
                address,
                latitude,
                longitude,
                rating,
                reviewsCount,
                businessStatus,
                openNow
        ));
    }

    private String text(Object value) {
        if (value instanceof String string && !string.isBlank()) {
            return string;
        }
        return null;
    }

    private Double number(Object value) {
        return value instanceof Number number
                ? number.doubleValue()
                : null;
    }
}
