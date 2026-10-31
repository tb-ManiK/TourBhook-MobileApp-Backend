package com.tourbhook.api.service.impl;

import com.tourbhook.api.config.RatingWeightingProperties;
import com.tourbhook.api.config.VenueSuggestionProperties;
import com.tourbhook.api.dto.venue.VenueResponse;
import com.tourbhook.api.dto.venue.VenueSuggestionResponse;
import com.tourbhook.api.dto.venue.VenueSuggestionStatus;
import com.tourbhook.api.repository.exception.BadRequestException;
import com.tourbhook.api.repository.exception.ExternalServiceException;
import com.tourbhook.api.service.venue.NearbyVenueSource;
import com.tourbhook.api.service.venue.VenueCandidate;
import com.tourbhook.api.service.venue.VenueSelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VenueSuggestionServiceImplTest {

    private static final double LAT = 13.0;
    private static final double LNG = 80.0;
    private static final double DEGREES_PER_METRE = 1.0 / 111_195.0;

    @Mock private NearbyVenueSource venueSource;

    private final VenueSuggestionProperties properties = new VenueSuggestionProperties();
    private final RatingWeightingProperties ratingProperties = new RatingWeightingProperties();
    private VenueSuggestionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new VenueSuggestionServiceImpl(
                venueSource, new VenueSelector(ratingProperties, properties), properties, ratingProperties);
    }

    private VenueCandidate venue(String id, String rating, int reviews, double metresNorth, Boolean openNow) {
        return new VenueCandidate(id, id, "cafe", "addr", LAT + metresNorth * DEGREES_PER_METRE, LNG,
                new BigDecimal(rating), reviews, "OPERATIONAL", openNow);
    }

    private VenueCandidate venue(String id, String rating, double metresNorth) {
        return venue(id, rating, 100, metresNorth, null);
    }

    private VenueSuggestionResponse suggest(Integer radius) {
        return service.suggestNearestTopRatedVenue(LAT, LNG, radius, true);
    }

    // ------------------------------------------------------------------ acceptance: nearest qualifying venue

    @Test
    void returnsTheNearestVenueRatedFourPointFiveOrBetter() {
        when(venueSource.findNearby(LAT, LNG, 1500)).thenReturn(List.of(
                venue("lower-rated-but-closest", "4.4", 100),
                venue("nearest-qualifying", "4.5", 300),
                venue("better-rated-but-farther", "4.9", 900)));

        VenueSuggestionResponse result = suggest(null);

        assertThat(result.status()).isEqualTo(VenueSuggestionStatus.TOP_RATED_NEARBY);
        assertThat(result.venue().placeId()).isEqualTo("nearest-qualifying");
        assertThat(result.alternatives()).extracting(VenueResponse::placeId).containsExactly("better-rated-but-farther");
        assertThat(result.searchedRadiusMeters()).isEqualTo(1500);
        assertThat(result.minRating()).isEqualByComparingTo("4.5");
        verify(venueSource, times(1)).findNearby(anyDouble(), anyDouble(), anyInt());
    }

    @Test
    void usesTheRequestedRadius() {
        when(venueSource.findNearby(LAT, LNG, 800)).thenReturn(List.of(venue("v", "4.8", 700)));

        assertThat(suggest(800).venue().placeId()).isEqualTo("v");
    }

    @Test
    void limitsAlternativesToTheConfiguredNumber() {
        when(venueSource.findNearby(LAT, LNG, 1500)).thenReturn(List.of(
                venue("a", "4.6", 100), venue("b", "4.6", 200), venue("c", "4.6", 300),
                venue("d", "4.6", 400), venue("e", "4.6", 500)));

        VenueSuggestionResponse result = suggest(null);

        assertThat(result.venue().placeId()).isEqualTo("a");
        assertThat(result.alternatives()).extracting(VenueResponse::placeId).containsExactly("b", "c", "d");
    }

    @Test
    void skipsVenuesClosedRightNowUnlessToldNotTo() {
        when(venueSource.findNearby(LAT, LNG, 1500)).thenReturn(List.of(
                venue("closed-now", "4.9", 100, 100.0, false), venue("open", "4.6", 100, 600.0, true)));

        assertThat(service.suggestNearestTopRatedVenue(LAT, LNG, null, true).venue().placeId()).isEqualTo("open");
        assertThat(service.suggestNearestTopRatedVenue(LAT, LNG, null, false).venue().placeId()).isEqualTo("closed-now");
    }

    // ------------------------------------------------------------------ acceptance: graceful fallback

    @Test
    void whenNothingIsTopRatedNearby_widensTheSearchOnceAndSaysSo() {
        when(venueSource.findNearby(LAT, LNG, 1500)).thenReturn(List.of(venue("ok-but-not-great", "4.2", 400)));
        when(venueSource.findNearby(LAT, LNG, 3000)).thenReturn(List.of(
                venue("ok-but-not-great", "4.2", 400), venue("worth-the-walk", "4.7", 2200)));

        VenueSuggestionResponse result = suggest(null);

        assertThat(result.status()).isEqualTo(VenueSuggestionStatus.TOP_RATED_FARTHER);
        assertThat(result.venue().placeId()).isEqualTo("worth-the-walk");
        assertThat(result.searchedRadiusMeters()).isEqualTo(3000);
        assertThat(result.message()).contains("4.5").contains("1500").contains("3000");
    }

    @Test
    void whenNothingIsTopRatedAnywhere_offersTheBestRatedVenueWithinTheOriginalRadius() {
        when(venueSource.findNearby(LAT, LNG, 1500)).thenReturn(List.of(
                venue("nearest-4.1", "4.1", 100), venue("best-4.4", "4.4", 1000)));
        when(venueSource.findNearby(LAT, LNG, 3000)).thenReturn(List.of(
                venue("nearest-4.1", "4.1", 100), venue("best-4.4", "4.4", 1000), venue("too-far-4.4", "4.4", 2500)));

        VenueSuggestionResponse result = suggest(null);

        assertThat(result.status()).isEqualTo(VenueSuggestionStatus.BEST_AVAILABLE);
        assertThat(result.venue().placeId()).isEqualTo("best-4.4");
        assertThat(result.alternatives()).extracting(VenueResponse::placeId).containsExactly("nearest-4.1");
        assertThat(result.searchedRadiusMeters()).isEqualTo(1500);
        assertThat(result.message()).contains("4.5");
    }

    @Test
    void whenTheWiderSearchFails_stillFallsBackToTheBestAvailable() {
        when(venueSource.findNearby(LAT, LNG, 1500)).thenReturn(List.of(venue("only-option", "4.2", 400)));
        when(venueSource.findNearby(LAT, LNG, 3000)).thenThrow(new ExternalServiceException("down"));

        VenueSuggestionResponse result = suggest(null);

        assertThat(result.status()).isEqualTo(VenueSuggestionStatus.BEST_AVAILABLE);
        assertThat(result.venue().placeId()).isEqualTo("only-option");
    }

    @Test
    void whenThereAreNoVenuesAtAll_saysSoInsteadOfFailing() {
        when(venueSource.findNearby(LAT, LNG, 1500)).thenReturn(List.of());
        when(venueSource.findNearby(LAT, LNG, 3000)).thenReturn(List.of());

        VenueSuggestionResponse result = suggest(null);

        assertThat(result.status()).isEqualTo(VenueSuggestionStatus.NO_VENUES);
        assertThat(result.venue()).isNull();
        assertThat(result.alternatives()).isEmpty();
        assertThat(result.message()).contains("larger radius");
    }

    @Test
    void whenThePlacesProviderIsDown_reportsUnavailableInsteadOfThrowing() {
        when(venueSource.findNearby(LAT, LNG, 1500)).thenThrow(new ExternalServiceException("Nearby venue search failed"));

        VenueSuggestionResponse result = suggest(null);

        assertThat(result.status()).isEqualTo(VenueSuggestionStatus.UNAVAILABLE);
        assertThat(result.venue()).isNull();
        verify(venueSource, times(1)).findNearby(anyDouble(), anyDouble(), anyInt());
    }

    @Test
    void whenWideningIsSwitchedOff_goesStraightToTheBestAvailable() {
        properties.setExpansionFactor(1.0);
        when(venueSource.findNearby(LAT, LNG, 1500)).thenReturn(List.of(venue("only-option", "4.2", 400)));

        assertThat(suggest(null).status()).isEqualTo(VenueSuggestionStatus.BEST_AVAILABLE);
        verify(venueSource, times(1)).findNearby(anyDouble(), anyDouble(), anyInt());
    }

    @Test
    void theWiderRadiusNeverExceedsTheConfiguredCeiling() {
        when(venueSource.findNearby(LAT, LNG, 5000)).thenReturn(List.of());
        when(venueSource.findNearby(LAT, LNG, 10_000)).thenReturn(List.of(venue("far", "4.8", 9000)));

        VenueSuggestionResponse result = suggest(5000);

        assertThat(result.searchedRadiusMeters()).isEqualTo(10_000);
        assertThat(result.venue().placeId()).isEqualTo("far");
    }

    // ------------------------------------------------------------------ input validation

    @Test
    void rejectsImpossibleCoordinatesAndRadii_withoutCallingTheProvider() {
        for (Double badLat : new Double[]{null, 91.0, -90.5, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> service.suggestNearestTopRatedVenue(badLat, LNG, null, true))
                    .as("lat=" + badLat).isInstanceOf(BadRequestException.class).hasMessageContaining("lat");
        }
        for (Double badLng : new Double[]{null, 181.0, -180.5, Double.NaN}) {
            assertThatThrownBy(() -> service.suggestNearestTopRatedVenue(LAT, badLng, null, true))
                    .as("lng=" + badLng).isInstanceOf(BadRequestException.class).hasMessageContaining("lng");
        }
        for (Integer badRadius : new Integer[]{0, 99, 5001, -10}) {
            assertThatThrownBy(() -> service.suggestNearestTopRatedVenue(LAT, LNG, badRadius, true))
                    .as("radius=" + badRadius).isInstanceOf(BadRequestException.class).hasMessageContaining("radius");
        }
        verify(venueSource, never()).findNearby(anyDouble(), anyDouble(), anyInt());
    }
}