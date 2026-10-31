package com.tourbhook.api.service.impl;

import com.tourbhook.api.config.EventProperties;
import com.tourbhook.api.dto.event.EventRelevance;
import com.tourbhook.api.dto.event.EventRelevanceType;
import com.tourbhook.api.dto.event.PersonalizedEventFeedResponse;
import com.tourbhook.api.dto.event.PersonalizedEventResponse;
import com.tourbhook.api.dto.event.PersonalizedFeedQuery;
import com.tourbhook.api.entity.LocalEvent;
import com.tourbhook.api.entity.Trip;
import com.tourbhook.api.entity.User;
import com.tourbhook.api.repository.LocalEventRepository;
import com.tourbhook.api.repository.TripRepository;
import com.tourbhook.api.repository.exception.BadRequestException;
import com.tourbhook.api.service.AuthenticatedUserService;
import com.tourbhook.api.service.event.EventSearcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersonalizedEventFeedServiceImplTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T06:30:00Z"), ZoneId.of("Asia/Kolkata"));
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);
    private static final double LAT = 13.0;
    private static final double LNG = 80.0;
    private static final double DEGREES_PER_KM = 1.0 / 111.195;

    @Mock private LocalEventRepository eventRepository;
    @Mock private TripRepository tripRepository;
    @Mock private AuthenticatedUserService authenticatedUserService;

    private final EventProperties properties = new EventProperties();
    private PersonalizedEventFeedServiceImpl service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new PersonalizedEventFeedServiceImpl(new EventSearcher(eventRepository, properties, CLOCK),
                eventRepository, tripRepository, authenticatedUserService, properties);
        user = User.builder().id("u1").build();
        lenient().when(authenticatedUserService.getCurrentUser()).thenReturn(user);
        lenient().when(eventRepository.findLastSyncedAt()).thenReturn(LocalDateTime.of(2026, 10, 6, 6, 0));
    }

    private LocalEvent event(String id, LocalDate start, String category, double kmNorth) {
        return LocalEvent.builder().id(id).title(id).city("x").cityKey("x").category(category)
                .latitude(LAT + kmNorth * DEGREES_PER_KM).longitude(LNG)
                .startDate(start).endDate(start).startTime(LocalTime.of(18, 0)).source("ics").active(true).build();
    }

    private Trip trip(String id, String city, LocalDate start, LocalDate end) {
        return Trip.builder().id(id).user(user).city(city).startDate(start).endDate(end).build();
    }

    private void givenTrips(Trip... trips) {
        when(tripRepository.findByUserAndEndDateGreaterThanEqualOrderByStartDateAsc(user, TODAY)).thenReturn(List.of(trips));
    }

    private PersonalizedFeedQuery nearbyOnly() {
        return new PersonalizedFeedQuery(LAT, LNG, 25.0, null, null, false, null, 0, 20);
    }

    private PersonalizedFeedQuery itineraryOnly(LocalDate from, LocalDate to, String category) {
        return new PersonalizedFeedQuery(null, null, null, from, to, true, category, 0, 20);
    }

    private List<String> ids(PersonalizedEventFeedResponse feed) {
        return feed.events().stream().map(e -> e.event().id()).toList();
    }

    // ------------------------------------------------------------------ location and/or itinerary

    @Test
    void nearbyOnly_returnsEventsNearTheUser_withTheirDistance_andNeverTouchesTheItinerary() {
        when(eventRepository.findInBoundingBox(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(TODAY), eq(TODAY.plusDays(6)), any()))
                .thenReturn(List.of(event("gig", TODAY.plusDays(1), "Music", 4), event("far", TODAY, "Music", 40)));

        PersonalizedEventFeedResponse feed = service.getFeedForMe(nearbyOnly());

        assertThat(ids(feed)).containsExactly("gig");
        assertThat(feed.events().get(0).relevance()).containsExactly(
                new EventRelevance(EventRelevanceType.NEAR_YOU, null, null, 4.0));
        assertThat(feed.itineraryTripsConsidered()).isZero();
        verify(tripRepository, never()).findByUserAndEndDateGreaterThanEqualOrderByStartDateAsc(any(), any());
        verify(authenticatedUserService, never()).getCurrentUser();
    }

    @Test
    void itineraryOnly_coversEveryActiveTrip_eachDuringItsOwnDates() {
        givenTrips(trip("t1", "Udaipur", LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 15)),
                trip("t2", "Goa", LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 25)));
        when(eventRepository.findForCity(eq("udaipur"), eq(LocalDate.of(2026, 11, 12)), eq(LocalDate.of(2026, 11, 15)), any()))
                .thenReturn(List.of(event("lake-festival", LocalDate.of(2026, 11, 13), "Festival", 0)));
        when(eventRepository.findForCity(eq("goa"), eq(LocalDate.of(2026, 11, 20)), eq(LocalDate.of(2026, 11, 25)), any()))
                .thenReturn(List.of(event("beach-party", LocalDate.of(2026, 11, 22), "Music", 0)));

        PersonalizedEventFeedResponse feed = service.getFeedForMe(itineraryOnly(null, null, null));

        assertThat(ids(feed)).containsExactly("lake-festival", "beach-party");
        assertThat(feed.events().get(0).relevance()).containsExactly(
                new EventRelevance(EventRelevanceType.ON_YOUR_ITINERARY, "t1", "Udaipur", null));
        assertThat(feed.events().get(1).relevance()).containsExactly(
                new EventRelevance(EventRelevanceType.ON_YOUR_ITINERARY, "t2", "Goa", null));
        assertThat(feed.itineraryTripsConsidered()).isEqualTo(2);
    }

    @Test
    void both_anEventThatQualifiesBothWays_appearsOnceWithBothReasons_strongestFirst() {
        LocalDate festivalDay = LocalDate.of(2026, 10, 8);
        when(eventRepository.findInBoundingBox(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(TODAY), eq(TODAY.plusDays(6)), any()))
                .thenReturn(List.of(event("festival", festivalDay, "Festival", 3)));
        givenTrips(trip("t1", "Chennai", LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 9)));
        when(eventRepository.findForCity(eq("chennai"), eq(LocalDate.of(2026, 10, 7)), eq(LocalDate.of(2026, 10, 9)), any()))
                .thenReturn(List.of(event("festival", festivalDay, "Festival", 3), event("concert", LocalDate.of(2026, 10, 9), "Music", 0)));

        PersonalizedEventFeedResponse feed = service.getFeedForMe(
                new PersonalizedFeedQuery(LAT, LNG, 25.0, null, null, true, null, 0, 20));

        assertThat(ids(feed)).containsExactly("festival", "concert");
        PersonalizedEventResponse festival = feed.events().get(0);
        assertThat(festival.relevance()).extracting(EventRelevance::type)
                .containsExactly(EventRelevanceType.ON_YOUR_ITINERARY, EventRelevanceType.NEAR_YOU);
        assertThat(festival.event().distanceKm()).isEqualTo(3.0);   // the nearby match keeps its distance
    }

    @Test
    void twoTripsToTheSameCity_listAnEventOnceWithAReasonForEach() {
        givenTrips(trip("t1", "Udaipur", LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 14)),
                trip("t2", "Udaipur", LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 16)));
        when(eventRepository.findForCity(eq("udaipur"), any(), any(), any()))
                .thenReturn(List.of(event("shared", LocalDate.of(2026, 11, 14), "Festival", 0)));

        PersonalizedEventFeedResponse feed = service.getFeedForMe(itineraryOnly(null, null, null));

        assertThat(ids(feed)).containsExactly("shared");
        assertThat(feed.events().get(0).relevance()).extracting(EventRelevance::tripId).containsExactly("t1", "t2");
    }

    @Test
    void noSourceAtAll_isRejected_andSoIsALocationWithoutBothCoordinates() {
        assertThatThrownBy(() -> service.getFeedForMe(new PersonalizedFeedQuery(null, null, null, null, null, false, null, 0, 20)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("lat and lng");
        assertThatThrownBy(() -> service.getFeedForMe(new PersonalizedFeedQuery(LAT, null, null, null, null, true, null, 0, 20)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("lng");
        verify(tripRepository, never()).findByUserAndEndDateGreaterThanEqualOrderByStartDateAsc(any(), any());
    }

    // ------------------------------------------------------------------ dates: past events must not surface

    @Test
    void aTripUnderway_isSearchedFromTodayOnly_neverItsPastDays() {
        givenTrips(trip("t1", "Udaipur", TODAY.minusDays(3), TODAY.plusDays(2)));
        when(eventRepository.findForCity(eq("udaipur"), eq(TODAY), eq(TODAY.plusDays(2)), any())).thenReturn(List.of());

        service.getFeedForMe(itineraryOnly(null, null, null));

        verify(eventRepository).findForCity(eq("udaipur"), eq(TODAY), eq(TODAY.plusDays(2)), any());
    }

    @Test
    void aTripThatHasAlreadyEnded_isSkippedEvenIfSomehowReturned() {
        givenTrips(trip("old", "Udaipur", TODAY.minusDays(9), TODAY.minusDays(1)));

        PersonalizedEventFeedResponse feed = service.getFeedForMe(itineraryOnly(null, null, null));

        assertThat(feed.events()).isEmpty();
        assertThat(feed.itineraryTripsConsidered()).isZero();
        verify(eventRepository, never()).findForCity(any(), any(), any(), any());
    }

    @Test
    void aTripBeyondTheFeedHorizon_isLeftOutForNow() {
        givenTrips(trip("far", "Udaipur", TODAY.plusDays(120), TODAY.plusDays(125)));

        PersonalizedEventFeedResponse feed = service.getFeedForMe(itineraryOnly(null, null, null));

        assertThat(feed.itineraryTripsConsidered()).isZero();
        verify(eventRepository, never()).findForCity(any(), any(), any(), any());
    }

    @Test
    void aLongTripIsCutOffAtTheHorizon() {
        givenTrips(trip("long", "Udaipur", TODAY.plusDays(10), TODAY.plusDays(300)));
        when(eventRepository.findForCity(eq("udaipur"), eq(TODAY.plusDays(10)), eq(TODAY.plusDays(89)), any())).thenReturn(List.of());

        service.getFeedForMe(itineraryOnly(null, null, null));

        verify(eventRepository).findForCity(eq("udaipur"), eq(TODAY.plusDays(10)), eq(TODAY.plusDays(89)), any());
    }

    @Test
    void anExplicitRange_limitsBothSources_andTripsOutsideItAreSkipped() {
        givenTrips(trip("t1", "Udaipur", LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 15)),
                trip("t2", "Goa", LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 25)));
        when(eventRepository.findForCity(eq("udaipur"), eq(LocalDate.of(2026, 11, 13)), eq(LocalDate.of(2026, 11, 14)), any()))
                .thenReturn(List.of(event("inside", LocalDate.of(2026, 11, 13), "Festival", 0)));

        PersonalizedEventFeedResponse feed = service.getFeedForMe(
                itineraryOnly(LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 14), null));

        assertThat(ids(feed)).containsExactly("inside");
        assertThat(feed.itineraryTripsConsidered()).isEqualTo(1);
        verify(eventRepository, never()).findForCity(eq("goa"), any(), any(), any());
    }

    @Test
    void aPastStartIsMovedUpToToday_andAPastEndIsRejected() {
        givenTrips();
        when(eventRepository.findInBoundingBox(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(TODAY), eq(TODAY.plusDays(2)), any()))
                .thenReturn(List.of());

        service.getFeedForMe(new PersonalizedFeedQuery(LAT, LNG, 25.0, TODAY.minusDays(20), TODAY.plusDays(2), true, null, 0, 20));
        verify(eventRepository).findInBoundingBox(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(TODAY), eq(TODAY.plusDays(2)), any());

        assertThatThrownBy(() -> service.getFeedForMe(itineraryOnly(null, TODAY.minusDays(1), null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("past");
    }

    // ------------------------------------------------------------------ limits, filters, paging

    @Test
    void onlyTheSoonestFewTripsAreSearched() {
        properties.setMaxItineraryTrips(2);
        givenTrips(trip("t1", "A", TODAY.plusDays(1), TODAY.plusDays(2)),
                trip("t2", "B", TODAY.plusDays(3), TODAY.plusDays(4)),
                trip("t3", "C", TODAY.plusDays(5), TODAY.plusDays(6)));
        when(eventRepository.findForCity(any(), any(), any(), any())).thenReturn(List.of());

        PersonalizedEventFeedResponse feed = service.getFeedForMe(itineraryOnly(null, null, null));

        assertThat(feed.itineraryTripsConsidered()).isEqualTo(2);
        verify(eventRepository, never()).findForCity(eq("c"), any(), any(), any());
    }

    @Test
    void theCategoryFilterAppliesToBothSources() {
        when(eventRepository.findInBoundingBox(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), any(), any()))
                .thenReturn(List.of(event("nearby-music", TODAY, "Music", 1), event("nearby-sport", TODAY, "Sports", 1)));
        givenTrips(trip("t1", "Chennai", TODAY.plusDays(1), TODAY.plusDays(3)));
        when(eventRepository.findForCity(eq("chennai"), any(), any(), any()))
                .thenReturn(List.of(event("trip-music", TODAY.plusDays(1), "Music", 0), event("trip-sport", TODAY.plusDays(1), "Sports", 0)));

        PersonalizedEventFeedResponse feed = service.getFeedForMe(
                new PersonalizedFeedQuery(LAT, LNG, 25.0, null, null, true, " music ", 0, 20));

        assertThat(ids(feed)).containsExactlyInAnyOrder("nearby-music", "trip-music");
    }

    @Test
    void theResultIsPaged_andSaysWhenTheDataWasLastRefreshed() {
        List<LocalEvent> events = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            events.add(event(String.format("e%02d", i), TODAY.plusDays(i % 6), "Music", 1));
        }
        when(eventRepository.findInBoundingBox(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), any(), any())).thenReturn(events);

        PersonalizedEventFeedResponse first = service.getFeedForMe(
                new PersonalizedFeedQuery(LAT, LNG, 25.0, null, null, false, null, 0, 10));
        PersonalizedEventFeedResponse last = service.getFeedForMe(
                new PersonalizedFeedQuery(LAT, LNG, 25.0, null, null, false, null, 2, 10));

        assertThat(first.events()).hasSize(10);
        assertThat(first.totalElements()).isEqualTo(25);
        assertThat(first.totalPages()).isEqualTo(3);
        assertThat(last.events()).hasSize(5);
        assertThat(first.lastRefreshedAt()).isEqualTo(LocalDateTime.of(2026, 10, 6, 6, 0));
    }
}