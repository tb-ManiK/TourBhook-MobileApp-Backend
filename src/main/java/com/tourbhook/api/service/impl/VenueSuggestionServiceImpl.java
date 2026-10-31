package com.tourbhook.api.service.impl;

import com.tourbhook.api.config.RatingWeightingProperties;
import com.tourbhook.api.config.VenueSuggestionProperties;
import com.tourbhook.api.dto.venue.VenueResponse;
import com.tourbhook.api.dto.venue.VenueSuggestionResponse;
import com.tourbhook.api.dto.venue.VenueSuggestionStatus;
import com.tourbhook.api.repository.exception.BadRequestException;
import com.tourbhook.api.repository.exception.ExternalServiceException;
import com.tourbhook.api.service.VenueSuggestionService;
import com.tourbhook.api.service.venue.NearbyVenueSource;
import com.tourbhook.api.service.venue.VenueCandidate;
import com.tourbhook.api.service.venue.VenueSelector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.List;


@Slf4j
@Service
@RequiredArgsConstructor
public class VenueSuggestionServiceImpl implements VenueSuggestionService {

    private final NearbyVenueSource venueSource;
    private final VenueSelector venueSelector;
    private final VenueSuggestionProperties properties;
    private final RatingWeightingProperties ratingProperties;

    @Override
    public VenueSuggestionResponse suggestNearestTopRatedVenue(Double latitude, Double longitude,
                                                               Integer radiusMeters, boolean openNowOnly) {
        double lat = requireCoordinate(latitude, "lat", 90);
        double lng = requireCoordinate(longitude, "lng", 180);
        int radius = resolveRadius(radiusMeters);

        List<VenueCandidate> nearby;
        try {
            nearby = venueSource.findNearby(lat, lng, radius);
        } catch (ExternalServiceException e) {
            log.warn("Venue suggestion unavailable for ({}, {}) r={}: {}", lat, lng, radius, e.getMessage());
            return unavailable(radius);
        }

        List<VenueResponse> topRated = venueSelector.topRated(nearby, lat, lng, radius, openNowOnly);
        if (!topRated.isEmpty()) {
            return suggestion(VenueSuggestionStatus.TOP_RATED_NEARBY,
                    "Nearest top-rated venue within " + radius + " m.", topRated, radius);
        }

        int widerRadius = widerRadius(radius);
        if (widerRadius > radius) {
            List<VenueResponse> topRatedFarther = topRatedWithin(lat, lng, widerRadius, openNowOnly);
            if (!topRatedFarther.isEmpty()) {
                return suggestion(VenueSuggestionStatus.TOP_RATED_FARTHER,
                        "No venue rated " + ratingBar() + "+ within " + radius
                                + " m. This is the nearest one within " + widerRadius + " m.",
                        topRatedFarther, widerRadius);
            }
        }

        List<VenueResponse> bestAvailable = venueSelector.bestAvailable(nearby, lat, lng, radius, openNowOnly);
        if (!bestAvailable.isEmpty()) {
            return suggestion(VenueSuggestionStatus.BEST_AVAILABLE,
                    "No venue rated " + ratingBar() + "+ nearby. Showing the best-rated option within "
                            + radius + " m.",
                    bestAvailable, radius);
        }

        return new VenueSuggestionResponse(VenueSuggestionStatus.NO_VENUES,
                "No restaurants or cafes found within " + radius + " m. Try a larger radius.",
                null, List.of(), radius, ratingProperties.getThreshold());
    }

    // ------------------------------------------------------------------ helpers

    private List<VenueResponse> topRatedWithin(double lat, double lng, int radius, boolean openNowOnly) {
        try {
            return venueSelector.topRated(venueSource.findNearby(lat, lng, radius), lat, lng, radius, openNowOnly);
        } catch (ExternalServiceException e) {
            log.warn("Wider venue search failed, continuing with the fallback: {}", e.getMessage());
            return List.of();
        }
    }

    private VenueSuggestionResponse suggestion(VenueSuggestionStatus status, String message,
                                               List<VenueResponse> ranked, int searchedRadius) {
        int alternativesEnd = Math.min(ranked.size(), 1 + properties.getMaxAlternatives());
        return new VenueSuggestionResponse(
                status,
                message,
                ranked.get(0),
                List.copyOf(ranked.subList(1, alternativesEnd)),
                searchedRadius,
                ratingProperties.getThreshold());
    }

    private VenueSuggestionResponse unavailable(int radius) {
        return new VenueSuggestionResponse(VenueSuggestionStatus.UNAVAILABLE,
                "Venue suggestions are temporarily unavailable. Please try again shortly.",
                null, List.of(), radius, ratingProperties.getThreshold());
    }

    private int widerRadius(int radius) {
        long wider = Math.round(radius * properties.getExpansionFactor());
        return (int) Math.min(wider, properties.getMaxExpandedRadiusMeters());
    }

    private String ratingBar() {
        BigDecimal threshold = ratingProperties.getThreshold();
        return threshold.stripTrailingZeros().toPlainString();
    }

    private double requireCoordinate(Double value, String name, double limit) {
        if (value == null || !Double.isFinite(value) || value < -limit || value > limit) {
            throw new BadRequestException(name + " must be a number between -" + (int) limit + " and " + (int) limit + ".");
        }
        return value;
    }

    private int resolveRadius(Integer requested) {
        if (requested == null) {
            return properties.getDefaultRadiusMeters();
        }
        if (requested < properties.getMinRadiusMeters() || requested > properties.getMaxRadiusMeters()) {
            throw new BadRequestException("radius must be between " + properties.getMinRadiusMeters()
                    + " and " + properties.getMaxRadiusMeters() + " metres.");
        }
        return requested;
    }
}