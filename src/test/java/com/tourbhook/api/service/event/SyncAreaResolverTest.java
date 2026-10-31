package com.tourbhook.api.service.event;

import com.tourbhook.api.config.EventProperties;
import com.tourbhook.api.repository.TripRepository;
import com.tourbhook.api.service.impl.GoogleMapsClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SyncAreaResolverTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 6);
    private static final LocalDate TO = LocalDate.of(2026, 12, 5);

    @Mock private TripRepository tripRepository;
    @Mock private GoogleMapsClient googleMapsClient;

    private final EventProperties properties = new EventProperties();
    private SyncAreaResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new SyncAreaResolver(properties, tripRepository, googleMapsClient);
    }

    private void configure(String city, double lat, double lng) {
        EventProperties.Area area = new EventProperties.Area();
        area.setCity(city);
        area.setLatitude(lat);
        area.setLongitude(lng);
        properties.getAreas().add(area);
    }

    @Test
    void resolve_includesConfiguredAreasAsTheyAre() {
        configure("Chennai", 13.08, 80.27);
        when(tripRepository.findDistinctCitiesWithTripsBetween(FROM, TO)).thenReturn(List.of());

        assertThat(resolver.resolve(FROM, TO)).containsExactly(new SyncArea("Chennai", 13.08, 80.27));
    }

    @Test
    void resolve_addsTheDestinationsOfUpcomingTrips_locatedThroughGeocoding() {
        when(tripRepository.findDistinctCitiesWithTripsBetween(FROM, TO)).thenReturn(List.of("Udaipur"));
        when(googleMapsClient.geocodeCity("Udaipur")).thenReturn(Map.of("lat", 24.58, "lng", 73.71));

        assertThat(resolver.resolve(FROM, TO)).containsExactly(new SyncArea("Udaipur", 24.58, 73.71));
    }

    @Test
    void resolve_aTripCityThatIsAlreadyConfigured_isNotGeocodedOrRepeated() {
        configure("Chennai", 13.08, 80.27);
        when(tripRepository.findDistinctCitiesWithTripsBetween(FROM, TO)).thenReturn(List.of("chennai", "CHENNAI "));

        assertThat(resolver.resolve(FROM, TO)).hasSize(1);
        verify(googleMapsClient, times(0)).geocodeCity(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void resolve_aCityThatCannotBeLocatedIsSkipped_andTriedAgainNextTime() {
        when(tripRepository.findDistinctCitiesWithTripsBetween(FROM, TO)).thenReturn(List.of("Atlantis"));
        when(googleMapsClient.geocodeCity("Atlantis")).thenReturn(Collections.emptyMap());

        assertThat(resolver.resolve(FROM, TO)).isEmpty();
        assertThat(resolver.resolve(FROM, TO)).isEmpty();

        verify(googleMapsClient, times(2)).geocodeCity("Atlantis");
    }

    @Test
    void resolve_aLocatedCityIsRememberedSoItIsGeocodedOnlyOnce() {
        when(tripRepository.findDistinctCitiesWithTripsBetween(FROM, TO)).thenReturn(List.of("Udaipur"));
        when(googleMapsClient.geocodeCity("Udaipur")).thenReturn(Map.of("lat", 24.58, "lng", 73.71));

        resolver.resolve(FROM, TO);
        assertThat(resolver.resolve(FROM, TO)).hasSize(1);

        verify(googleMapsClient, times(1)).geocodeCity("Udaipur");
    }

    @Test
    void resolve_capsHowManyTripDestinationsAreSyncedPerRun() {
        properties.getSync().setMaxTripAreas(2);
        when(tripRepository.findDistinctCitiesWithTripsBetween(FROM, TO)).thenReturn(List.of("A", "B", "C", "D"));
        when(googleMapsClient.geocodeCity(org.mockito.ArgumentMatchers.anyString())).thenReturn(Map.of("lat", 1.0, "lng", 2.0));

        assertThat(resolver.resolve(FROM, TO)).extracting(SyncArea::city).containsExactly("A", "B");
    }

    @Test
    void resolve_ignoresBlankConfiguredAndTripCities() {
        configure("  ", 0, 0);
        when(tripRepository.findDistinctCitiesWithTripsBetween(FROM, TO)).thenReturn(java.util.Arrays.asList(null, "", "  "));

        assertThat(resolver.resolve(FROM, TO)).isEmpty();
    }
}