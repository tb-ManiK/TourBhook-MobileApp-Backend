package com.tourbhook.api.service.venue;

import com.tourbhook.api.config.RatingWeightingProperties;
import com.tourbhook.api.config.VenueSuggestionProperties;
import com.tourbhook.api.dto.venue.VenueResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;


@Component
@RequiredArgsConstructor
public class VenueSelector {

    private static final String OPERATIONAL = "OPERATIONAL";

    private static final Comparator<VenueResponse> BY_RATING_DESC = (a, b) -> {
        BigDecimal ratingA = a.rating() == null ? BigDecimal.valueOf(-1) : a.rating();
        BigDecimal ratingB = b.rating() == null ? BigDecimal.valueOf(-1) : b.rating();
        return ratingB.compareTo(ratingA);
    };

    private static final Comparator<VenueResponse> BY_REVIEWS_DESC = (a, b) ->
            Integer.compare(b.reviewsCount() == null ? 0 : b.reviewsCount(),
                    a.reviewsCount() == null ? 0 : a.reviewsCount());

    private static final Comparator<VenueResponse> NEAREST_FIRST = Comparator
            .comparingInt(VenueResponse::distanceMeters)
            .thenComparing(BY_RATING_DESC)
            .thenComparing(BY_REVIEWS_DESC);

    private static final Comparator<VenueResponse> HIGHEST_RATED_FIRST = BY_RATING_DESC
            .thenComparing(BY_REVIEWS_DESC)
            .thenComparingInt(VenueResponse::distanceMeters);

    private final RatingWeightingProperties ratingProperties;
    private final VenueSuggestionProperties properties;

    public List<VenueResponse> topRated(List<VenueCandidate> candidates, double originLat, double originLng,
                                        int radiusMeters, boolean openNowOnly) {
        return eligible(candidates, originLat, originLng, radiusMeters, openNowOnly).stream()
                .filter(this::isTopRated)
                .sorted(NEAREST_FIRST)
                .toList();
    }

    public List<VenueResponse> bestAvailable(List<VenueCandidate> candidates, double originLat, double originLng,
                                             int radiusMeters, boolean openNowOnly) {
        List<VenueResponse> eligible = eligible(candidates, originLat, originLng, radiusMeters, openNowOnly);

        List<VenueResponse> wellRated = eligible.stream()
                .filter(venue -> venue.rating() != null
                        && venue.rating().compareTo(properties.getFallbackMinRating()) >= 0)
                .sorted(HIGHEST_RATED_FIRST)
                .toList();
        if (!wellRated.isEmpty()) {
            return wellRated;
        }
        return eligible.stream().sorted(NEAREST_FIRST).toList();
    }

//    private List<VenueResponse> eligible(List<VenueCandidate> candidates, double originLat, double originLng,
//                                         int radiusMeters, boolean openNowOnly) {
//        return candidates.stream()
//                .filter(candidate -> isOperational(candidate))
//                .filter(candidate -> !openNowOnly || Boolean.TRUE.equals(candidate.openNow()))
//                .map(candidate -> toResponse(candidate, originLat, originLng))
//                .filter(venue -> venue.distanceMeters() <= radiusMeters)
//                .toList();
//    }


    private List<VenueResponse> eligible(
            List<VenueCandidate> candidates,
            double originLat,
            double originLng,
            int radiusMeters,
            boolean openNowOnly) {

        return candidates.stream()
                .filter(this::isOperational)
                .filter(candidate -> !openNowOnly
                        || !Boolean.FALSE.equals(candidate.openNow()))
                .map(candidate -> toResponse(candidate, originLat, originLng))
                .filter(venue -> venue.distanceMeters() <= radiusMeters)
                .toList();
    }



    private boolean isOperational(VenueCandidate candidate) {
        return candidate.businessStatus() == null || OPERATIONAL.equalsIgnoreCase(candidate.businessStatus());
    }

    private boolean isTopRated(VenueResponse venue) {
        return venue.rating() != null
                && venue.rating().compareTo(ratingProperties.getThreshold()) >= 0
                && venue.reviewsCount() != null
                && venue.reviewsCount() >= properties.getMinReviews();
    }

    private VenueResponse toResponse(VenueCandidate candidate, double originLat, double originLng) {
        double meters = GeoDistance.meters(originLat, originLng, candidate.latitude(), candidate.longitude());
        return new VenueResponse(
                candidate.placeId(),
                candidate.name(),
                candidate.category(),
                candidate.address(),
                candidate.latitude(),
                candidate.longitude(),
                candidate.rating(),
                candidate.reviewsCount(),
                (int) Math.round(meters),
                candidate.openNow());
    }
}