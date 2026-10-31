package com.tourbhook.api.service.venue;

import com.tourbhook.api.config.RatingWeightingProperties;
import com.tourbhook.api.config.VenueSuggestionProperties;
import com.tourbhook.api.dto.venue.VenueResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VenueSelectorTest {

    private static final double ORIGIN_LAT = 13.0000;
    private static final double ORIGIN_LNG = 80.0000;
    private static final int RADIUS = 1500;
    private static final double DEGREES_PER_METRE = 1.0 / 111_195.0;

    private final VenueSelector selector =
            new VenueSelector(new RatingWeightingProperties(), new VenueSuggestionProperties());

    private VenueCandidate venue(String id, String rating, Integer reviews, double metresNorth) {
        return new VenueCandidate(id, id, "restaurant", "addr", ORIGIN_LAT + metresNorth * DEGREES_PER_METRE,
                ORIGIN_LNG, rating == null ? null : new BigDecimal(rating), reviews, "OPERATIONAL", null);
    }

    private VenueCandidate withStatus(VenueCandidate base, String status, Boolean openNow) {
        return new VenueCandidate(base.placeId(), base.name(), base.category(), base.address(), base.latitude(),
                base.longitude(), base.rating(), base.reviewsCount(), status, openNow);
    }

    private List<String> topRatedIds(boolean openNowOnly, VenueCandidate... candidates) {
        return selector.topRated(List.of(candidates), ORIGIN_LAT, ORIGIN_LNG, RADIUS, openNowOnly).stream()
                .map(VenueResponse::placeId).toList();
    }

    @Test
    void topRated_ordersNearestFirst_notHighestRatedFirst() {
        List<String> ids = topRatedIds(true,
                venue("far-but-5.0", "5.0", 300, 1200),
                venue("near-4.5", "4.5", 300, 200),
                venue("middle-4.8", "4.8", 300, 700));

        assertThat(ids).containsExactly("near-4.5", "middle-4.8", "far-but-5.0");
    }

    @Test
    void topRated_ratingBarIsInclusive() {
        assertThat(topRatedIds(true, venue("exactly", "4.5", 50, 100), venue("just-below", "4.4", 50, 50)))
                .containsExactly("exactly");
    }

    @Test
    void topRated_ignoresVenuesWithTooFewReviewsOrNoRating() {
        assertThat(topRatedIds(true,
                venue("five-reviews", "5.0", 5, 100),
                venue("no-review-count", "4.9", null, 100),
                venue("unrated", null, 100, 100),
                venue("trusted", "4.6", 10, 400)))
                .containsExactly("trusted");
    }

    @Test
    void topRated_ignoresVenuesOutsideTheRadius() {
        assertThat(topRatedIds(true, venue("inside", "4.7", 80, 1400), venue("outside", "4.9", 80, 1600)))
                .containsExactly("inside");
    }

    @Test
    void topRated_skipsClosedBusinessesAndVenuesClosedRightNow() {
        VenueCandidate permanentlyClosed = withStatus(venue("gone", "4.9", 90, 50), "CLOSED_PERMANENTLY", null);
        VenueCandidate temporarilyClosed = withStatus(venue("refurb", "4.9", 90, 60), "CLOSED_TEMPORARILY", null);
        VenueCandidate closedNow = withStatus(venue("shut", "4.8", 90, 70), "OPERATIONAL", false);
        VenueCandidate openNow = withStatus(venue("open", "4.6", 90, 400), "OPERATIONAL", true);
        VenueCandidate hoursUnknown = withStatus(venue("unknown", "4.6", 90, 500), null, null);

        assertThat(topRatedIds(true, permanentlyClosed, temporarilyClosed, closedNow, openNow, hoursUnknown))
                .containsExactly("open", "unknown");
    }

    @Test
    void topRated_whenOpenNowIsNotRequired_keepsVenuesThatAreClosedRightNow() {
        VenueCandidate closedNow = withStatus(venue("shut", "4.8", 90, 70), "OPERATIONAL", false);

        assertThat(topRatedIds(false, closedNow)).containsExactly("shut");
    }

    @Test
    void topRated_equalDistance_prefersTheHigherRatingThenMoreReviews() {
        assertThat(topRatedIds(true,
                venue("4.6", "4.6", 500, 300),
                venue("4.8-few", "4.8", 20, 300),
                venue("4.8-many", "4.8", 900, 300)))
                .containsExactly("4.8-many", "4.8-few", "4.6");
    }

    @Test
    void topRated_reportsDistanceInWholeMetres() {
        VenueResponse found = selector.topRated(List.of(venue("v", "4.7", 80, 600)),
                ORIGIN_LAT, ORIGIN_LNG, RADIUS, true).get(0);

        assertThat(found.distanceMeters()).isBetween(598, 602);
    }

    @Test
    void bestAvailable_prefersTheHighestRatedAtOrAboveTheFallbackRating() {
        List<VenueResponse> ranked = selector.bestAvailable(List.of(
                        venue("near-4.1", "4.1", 100, 100),
                        venue("far-4.4", "4.4", 100, 1000),
                        venue("near-3.9", "3.9", 100, 50)),
                ORIGIN_LAT, ORIGIN_LNG, RADIUS, true);

        assertThat(ranked).extracting(VenueResponse::placeId).containsExactly("far-4.4", "near-4.1");
    }

    @Test
    void bestAvailable_whenNothingIsWellRated_fallsBackToTheNearestOpenVenue() {
        List<VenueResponse> ranked = selector.bestAvailable(List.of(
                        venue("far-3.5", "3.5", 100, 900),
                        venue("unrated-near", null, null, 100),
                        venue("mid-3.0", "3.0", 100, 400)),
                ORIGIN_LAT, ORIGIN_LNG, RADIUS, true);

        assertThat(ranked).extracting(VenueResponse::placeId).containsExactly("unrated-near", "mid-3.0", "far-3.5");
    }

    @Test
    void bestAvailable_withNoEligibleVenue_isEmpty() {
        assertThat(selector.bestAvailable(List.of(withStatus(venue("gone", "4.9", 90, 50), "CLOSED_PERMANENTLY", null)),
                ORIGIN_LAT, ORIGIN_LNG, RADIUS, true)).isEmpty();
    }
}