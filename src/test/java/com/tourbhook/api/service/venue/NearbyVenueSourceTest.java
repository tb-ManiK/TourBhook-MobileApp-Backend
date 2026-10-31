//package com.tourbhook.api.service.venue;
//
//import com.tourbhook.api.config.VenueSuggestionProperties;
//import com.tourbhook.api.repository.exception.ExternalServiceException;
//import com.tourbhook.api.service.impl.GoogleMapsClient;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//import java.math.BigDecimal;
//import java.util.Collections;
//import java.util.List;
//import java.util.Map;
//import static org.assertj.core.api.Assertions.assertThat;
//import static org.assertj.core.api.Assertions.assertThatThrownBy;
//import static org.mockito.Mockito.when;
//
//@ExtendWith(MockitoExtension.class)
//class NearbyVenueSourceTest {
//
//    private static final double LAT = 13.0;
//    private static final double LNG = 80.0;
//    private static final int RADIUS = 1500;
//
//    @Mock private GoogleMapsClient googleMapsClient;
//
//    private NearbyVenueSource source;
//
//    @BeforeEach
//    void setUp() {
//        source = new NearbyVenueSource(googleMapsClient, new VenueSuggestionProperties());
//    }
//
//    private Map<String, Object> result(String placeId, String name, Object rating, Object reviews,
//                                       List<String> types, Map<String, Object> extras) {
//        Map<String, Object> result = new java.util.HashMap<>(extras);
//        result.put("place_id", placeId);
//        result.put("name", name);
//        result.put("rating", rating);
//        result.put("user_ratings_total", reviews);
//        result.put("types", types);
//        result.put("geometry", Map.of("location", Map.of("lat", 13.001, "lng", 80.001)));
//        return result;
//    }
//
//    @SafeVarargs
//    private Map<String, Object> ok(Map<String, Object>... results) {
//        return Map.of("status", "OK", "results", List.of(results));
//    }
//
//    private void givenResponses(Map<String, Object> restaurants, Map<String, Object> cafes) {
//        when(googleMapsClient.nearbySearch(LAT, LNG, RADIUS, "restaurant")).thenReturn(restaurants);
//        when(googleMapsClient.nearbySearch(LAT, LNG, RADIUS, "cafe")).thenReturn(cafes);
//    }
//
//    @Test
//    void findNearby_readsEveryFieldWeNeed() {
//        givenResponses(ok(result("r1", "Spice Hub", 4.6, 320, List.of("restaurant", "food"),
//                        Map.of("vicinity", "12 Main Road", "business_status", "OPERATIONAL",
//                                "opening_hours", Map.of("open_now", true)))),
//                Map.of("status", "ZERO_RESULTS", "results", List.of()));
//
//        VenueCandidate venue = source.findNearby(LAT, LNG, RADIUS).get(0);
//
//        assertThat(venue.placeId()).isEqualTo("r1");
//        assertThat(venue.name()).isEqualTo("Spice Hub");
//        assertThat(venue.category()).isEqualTo("restaurant");
//        assertThat(venue.address()).isEqualTo("12 Main Road");
//        assertThat(venue.rating()).isEqualByComparingTo(new BigDecimal("4.6"));
//        assertThat(venue.reviewsCount()).isEqualTo(320);
//        assertThat(venue.businessStatus()).isEqualTo("OPERATIONAL");
//        assertThat(venue.openNow()).isTrue();
//        assertThat(venue.latitude()).isEqualTo(13.001);
//    }
//
//    @Test
//    void findNearby_mergesBothTypesAndListsAPlaceOnlyOnce() {
//        givenResponses(
//                ok(result("both", "Cafe Bistro", 4.5, 100, List.of("cafe", "restaurant"), Map.of()),
//                        result("r2", "Curry House", 4.0, 50, List.of("restaurant"), Map.of())),
//                ok(result("both", "Cafe Bistro", 4.5, 100, List.of("cafe", "restaurant"), Map.of()),
//                        result("c2", "Bean Bar", 4.7, 80, List.of("cafe"), Map.of())));
//
//        List<VenueCandidate> venues = source.findNearby(LAT, LNG, RADIUS);
//
//        assertThat(venues).extracting(VenueCandidate::placeId).containsExactly("both", "r2", "c2");
//        // a cafe that Google also tags as a restaurant is still reported as a cafe
//        assertThat(venues.get(0).category()).isEqualTo("cafe");
//    }
//
//    @Test
//    void findNearby_skipsResultsThatCannotBePlacedOnAMap() {
//        Map<String, Object> noGeometry = new java.util.HashMap<>(result("x", "No Position", 4.9, 99, List.of(), Map.of()));
//        noGeometry.remove("geometry");
//        Map<String, Object> noName = new java.util.HashMap<>(result("y", "n", 4.9, 99, List.of(), Map.of()));
//        noName.remove("name");
//
//        givenResponses(ok(noGeometry, noName, result("good", "Good Place", 4.2, 12, List.of("restaurant"), Map.of())),
//                Map.of("status", "ZERO_RESULTS"));
//
//        assertThat(source.findNearby(LAT, LNG, RADIUS)).extracting(VenueCandidate::placeId).containsExactly("good");
//    }
//
//    @Test
//    void findNearby_aMissingRatingStaysNullInsteadOfBecomingZero() {
//        givenResponses(ok(result("new", "Brand New", null, null, List.of("cafe"), Map.of())),
//                Map.of("status", "ZERO_RESULTS"));
//
//        VenueCandidate venue = source.findNearby(LAT, LNG, RADIUS).get(0);
//
//        assertThat(venue.rating()).isNull();
//        assertThat(venue.reviewsCount()).isNull();
//        assertThat(venue.openNow()).isNull();
//    }
//
//    @Test
//    void findNearby_whenOneTypeFails_stillReturnsTheOther() {
//        givenResponses(Collections.emptyMap(),   // the client reports every failure as an empty map
//                ok(result("c1", "Bean Bar", 4.7, 80, List.of("cafe"), Map.of())));
//
//        assertThat(source.findNearby(LAT, LNG, RADIUS)).extracting(VenueCandidate::placeId).containsExactly("c1");
//    }
//
//    @Test
//    void findNearby_whenEveryTypeFails_signalsAnExternalFailure() {
//        givenResponses(Collections.emptyMap(), Map.of("status", "REQUEST_DENIED", "error_message", "bad key"));
//
//        assertThatThrownBy(() -> source.findNearby(LAT, LNG, RADIUS)).isInstanceOf(ExternalServiceException.class);
//    }
//
//    @Test
//    void findNearby_zeroResultsIsAnAnswer_notAFailure() {
//        givenResponses(Map.of("status", "ZERO_RESULTS", "results", List.of()),
//                Map.of("status", "ZERO_RESULTS", "results", List.of()));
//
//        assertThat(source.findNearby(LAT, LNG, RADIUS)).isEmpty();
//    }
//}



package com.tourbhook.api.service.venue;

import com.tourbhook.api.config.VenueSuggestionProperties;
import com.tourbhook.api.repository.exception.ExternalServiceException;
import com.tourbhook.api.service.impl.GoogleMapsClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NearbyVenueSourceTest {

    private static final double LAT = 13.0;
    private static final double LNG = 80.0;
    private static final int RADIUS = 1500;

    @Mock
    private GoogleMapsClient googleMapsClient;

    private NearbyVenueSource source;

    @BeforeEach
    void setUp() {
        source = new NearbyVenueSource(
                googleMapsClient,
                new VenueSuggestionProperties()
        );
    }

    private Map<String, Object> result(
            String placeId,
            String name,
            Object rating,
            Object reviews,
            List<String> types,
            Map<String, Object> extras
    ) {
        Map<String, Object> result = new HashMap<>(extras);

        result.put("id", placeId);

        if (name != null) {
            result.put("displayName", Map.of("text", name));
        }

        if (rating != null) {
            result.put("rating", rating);
        }

        if (reviews != null) {
            result.put("userRatingCount", reviews);
        }

        result.put("types", types);
        result.put(
                "location",
                Map.of("latitude", 13.001, "longitude", 80.001)
        );

        return result;
    }

    @SafeVarargs
    private Map<String, Object> ok(Map<String, Object>... places) {
        return Map.of("places", List.of(places));
    }

    private Map<String, Object> zeroResults() {
        return Map.of("places", List.of());
    }

    private void givenResponses(
            Map<String, Object> restaurants,
            Map<String, Object> cafes
    ) {
        when(googleMapsClient.nearbySearch(
                LAT, LNG, RADIUS, "restaurant"
        )).thenReturn(restaurants);

        when(googleMapsClient.nearbySearch(
                LAT, LNG, RADIUS, "cafe"
        )).thenReturn(cafes);
    }

    @Test
    void findNearby_readsEveryFieldWeNeed() {
        givenResponses(
                ok(result(
                        "r1",
                        "Spice Hub",
                        4.6,
                        320,
                        List.of("restaurant", "food"),
                        Map.of(
                                "formattedAddress", "12 Main Road",
                                "businessStatus", "OPERATIONAL",
                                "currentOpeningHours",
                                Map.of("openNow", true)
                        )
                )),
                zeroResults()
        );

        VenueCandidate venue =
                source.findNearby(LAT, LNG, RADIUS).get(0);

        assertThat(venue.placeId()).isEqualTo("r1");
        assertThat(venue.name()).isEqualTo("Spice Hub");
        assertThat(venue.category()).isEqualTo("restaurant");
        assertThat(venue.address()).isEqualTo("12 Main Road");
        assertThat(venue.rating())
                .isEqualByComparingTo(new BigDecimal("4.6"));
        assertThat(venue.reviewsCount()).isEqualTo(320);
        assertThat(venue.businessStatus()).isEqualTo("OPERATIONAL");
        assertThat(venue.openNow()).isTrue();
        assertThat(venue.latitude()).isEqualTo(13.001);
        assertThat(venue.longitude()).isEqualTo(80.001);
    }

    @Test
    void findNearby_mergesBothTypesAndListsAPlaceOnlyOnce() {
        givenResponses(
                ok(
                        result(
                                "both", "Cafe Bistro", 4.5, 100,
                                List.of("cafe", "restaurant"), Map.of()
                        ),
                        result(
                                "r2", "Curry House", 4.0, 50,
                                List.of("restaurant"), Map.of()
                        )
                ),
                ok(
                        result(
                                "both", "Cafe Bistro", 4.5, 100,
                                List.of("cafe", "restaurant"), Map.of()
                        ),
                        result(
                                "c2", "Bean Bar", 4.7, 80,
                                List.of("cafe"), Map.of()
                        )
                )
        );

        List<VenueCandidate> venues =
                source.findNearby(LAT, LNG, RADIUS);

        assertThat(venues)
                .extracting(VenueCandidate::placeId)
                .containsExactly("both", "r2", "c2");

        // A place returned by both searches should appear only once.
        assertThat(venues.get(0).category()).isEqualTo("cafe");
    }

    @Test
    void findNearby_skipsResultsThatCannotBePlacedOnAMap() {
        Map<String, Object> noLocation = new HashMap<>(
                result(
                        "x", "No Position", 4.9, 99,
                        List.of("restaurant"), Map.of()
                )
        );
        noLocation.remove("location");

        Map<String, Object> noName = new HashMap<>(
                result(
                        "y", null, 4.9, 99,
                        List.of("restaurant"), Map.of()
                )
        );

        givenResponses(
                ok(
                        noLocation,
                        noName,
                        result(
                                "good", "Good Place", 4.2, 12,
                                List.of("restaurant"), Map.of()
                        )
                ),
                zeroResults()
        );

        assertThat(source.findNearby(LAT, LNG, RADIUS))
                .extracting(VenueCandidate::placeId)
                .containsExactly("good");
    }

    @Test
    void findNearby_aMissingRatingStaysNullInsteadOfBecomingZero() {
        givenResponses(
                ok(result(
                        "new", "Brand New", null, null,
                        List.of("cafe"), Map.of()
                )),
                zeroResults()
        );

        VenueCandidate venue =
                source.findNearby(LAT, LNG, RADIUS).get(0);

        assertThat(venue.rating()).isNull();
        assertThat(venue.reviewsCount()).isNull();
        assertThat(venue.openNow()).isNull();
    }

    @Test
    void findNearby_whenOneTypeFails_stillReturnsTheOther() {
        givenResponses(
                Collections.emptyMap(),
                ok(result(
                        "c1", "Bean Bar", 4.7, 80,
                        List.of("cafe"), Map.of()
                ))
        );

        assertThat(source.findNearby(LAT, LNG, RADIUS))
                .extracting(VenueCandidate::placeId)
                .containsExactly("c1");
    }

    @Test
    void findNearby_whenEveryTypeFails_signalsAnExternalFailure() {
        givenResponses(
                Map.of(
                        "error",
                        Map.of("message", "bad key")
                ),
                Map.of(
                        "error",
                        Map.of("message", "bad key")
                )
        );

        assertThatThrownBy(() ->
                source.findNearby(LAT, LNG, RADIUS)
        ).isInstanceOf(ExternalServiceException.class);
    }

    @Test
    void findNearby_zeroResultsIsAnAnswer_notAFailure() {
        givenResponses(zeroResults(), zeroResults());

        assertThat(source.findNearby(LAT, LNG, RADIUS)).isEmpty();
    }
}
