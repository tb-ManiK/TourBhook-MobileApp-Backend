package com.tourbhook.api.service.impl;

import com.tourbhook.api.config.EventProperties;
import com.tourbhook.api.dto.event.EventFeedQuery;
import com.tourbhook.api.dto.event.EventFeedResponse;
import com.tourbhook.api.dto.event.EventResponse;
import com.tourbhook.api.entity.LocalEvent;
import com.tourbhook.api.entity.Trip;
import com.tourbhook.api.entity.User;
import com.tourbhook.api.repository.LocalEventRepository;
import com.tourbhook.api.repository.TripRepository;
import com.tourbhook.api.repository.exception.BadRequestException;
import com.tourbhook.api.repository.exception.ResourceNotFoundException;
import com.tourbhook.api.service.AuthenticatedUserService;
import com.tourbhook.api.service.event.EventSearcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

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
class EventFeedServiceImplTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T06:30:00Z"), ZoneId.of("Asia/Kolkata"));
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);
    private static final double LAT = 13.0;
    private static final double LNG = 80.0;
    private static final double DEGREES_PER_KM = 1.0 / 111.195;

    @Mock private LocalEventRepository eventRepository;
    @Mock private TripRepository tripRepository;
    @Mock private AuthenticatedUserService authenticatedUserService;

    private final EventProperties properties = new EventProperties();
    private EventFeedServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new EventFeedServiceImpl(new EventSearcher(eventRepository, properties, CLOCK), eventRepository,
                tripRepository, authenticatedUserService, properties);
        lenient().when(eventRepository.findLastSyncedAt()).thenReturn(LocalDateTime.of(2026, 10, 6, 6, 0));
    }

    private LocalEvent event(String title, LocalDate start, LocalTime time, String category, double kmNorth) {
        return LocalEvent.builder().id(title).title(title).city("Chennai").cityKey("chennai").category(category)
                .latitude(LAT + kmNorth * DEGREES_PER_KM).longitude(LNG)
                .startDate(start).endDate(start).startTime(time).source("ics").active(true).build();
    }

    private EventFeedQuery cityQuery(String city, LocalDate from, LocalDate to, String category, int page, int size) {
        return new EventFeedQuery(null, null, null, city, from, to, category, page, size);
    }

    private EventFeedQuery aroundQuery(Double lat, Double lng, Double radiusKm) {
        return new EventFeedQuery(lat, lng, radiusKm, null, null, null, null, 0, 20);
    }

    // ------------------------------------------------------------------ date range (acceptance: filter by date range)

    @Test
    void getFeed_withoutDates_showsTheNextSevenDaysStartingToday() {
        when(eventRepository.findForCity(eq("chennai"), any(), any(), any())).thenReturn(List.of());

        EventFeedResponse feed = service.getFeed(cityQuery("Chennai", null, null, null, 0, 20));

        assertThat(feed.from()).isEqualTo(TODAY);
        assertThat(feed.to()).isEqualTo(TODAY.plusDays(6));
        verify(eventRepository).findForCity(eq("chennai"), eq(TODAY), eq(TODAY.plusDays(6)), any());
    }

    @Test
    void getFeed_passesAnExplicitRangeToTheQuery() {
        LocalDate from = LocalDate.of(2026, 11, 10);
        LocalDate to = LocalDate.of(2026, 11, 20);
        when(eventRepository.findForCity(eq("chennai"), eq(from), eq(to), any())).thenReturn(List.of());

        EventFeedResponse feed = service.getFeed(cityQuery("Chennai", from, to, null, 0, 20));

        assertThat(feed.from()).isEqualTo(from);
        assertThat(feed.to()).isEqualTo(to);
    }

    @Test
    void getFeed_onlyAStartDate_runsForTheDefaultWindowFromThere() {
        LocalDate from = LocalDate.of(2026, 11, 10);
        when(eventRepository.findForCity(any(), any(), any(), any())).thenReturn(List.of());

        EventFeedResponse feed = service.getFeed(cityQuery("Chennai", from, null, null, 0, 20));

        assertThat(feed.to()).isEqualTo(from.plusDays(6));
    }

    @Test
    void getFeed_rejectsBackwardsOrOverlongRanges() {
        assertThatThrownBy(() -> service.getFeed(cityQuery("Chennai", TODAY.plusDays(5), TODAY, null, 0, 20)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("before");
        assertThatThrownBy(() -> service.getFeed(cityQuery("Chennai", TODAY, TODAY.plusDays(90), null, 0, 20)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("90");
        verify(eventRepository, never()).findForCity(any(), any(), any(), any());
    }

    @Test
    void getFeed_aRangeOfExactlyTheMaximumIsAllowed() {
        when(eventRepository.findForCity(any(), any(), any(), any())).thenReturn(List.of());

        assertThat(service.getFeed(cityQuery("Chennai", TODAY, TODAY.plusDays(89), null, 0, 20)).events()).isEmpty();
    }

    // ------------------------------------------------------------------ location (acceptance: filter by location)

    @Test
    void getFeed_byCity_isCaseInsensitiveAndOrdersBySoonestFirst_allDayEventsBeforeTimedOnes() {
        when(eventRepository.findForCity(eq("chennai"), any(), any(), any())).thenReturn(List.of(
                event("Later", TODAY.plusDays(2), LocalTime.of(10, 0), "Music", 0),
                event("Evening", TODAY, LocalTime.of(19, 0), "Music", 0),
                event("All day", TODAY, null, "Festival", 0)));

        EventFeedResponse feed = service.getFeed(cityQuery("  CHENNAI ", null, null, null, 0, 20));

        assertThat(feed.events()).extracting(EventResponse::title).containsExactly("All day", "Evening", "Later");
        assertThat(feed.events()).allSatisfy(e -> assertThat(e.distanceKm()).isNull());
    }

    @Test
    void getFeed_byLocation_keepsOnlyEventsWithinTheRadius_nearestFirstOnTheSameDay() {
        when(eventRepository.findInBoundingBox(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), any(), any()))
                .thenReturn(List.of(
                        event("far", TODAY, LocalTime.of(18, 0), "Music", 30),
                        event("near", TODAY, LocalTime.of(18, 0), "Music", 2),
                        event("edge-in", TODAY, LocalTime.of(18, 0), "Music", 24.9),
                        event("edge-out", TODAY, LocalTime.of(18, 0), "Music", 25.1)));

        EventFeedResponse feed = service.getFeed(aroundQuery(LAT, LNG, 25.0));

        assertThat(feed.events()).extracting(EventResponse::title).containsExactly("near", "edge-in");
        assertThat(feed.events().get(0).distanceKm()).isEqualTo(2.0);
    }

    @Test
    void getFeed_byLocation_usesADefaultRadiusAndASearchRectangleAroundThePoint() {
        when(eventRepository.findInBoundingBox(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), any(), any()))
                .thenReturn(List.of());

        service.getFeed(aroundQuery(LAT, LNG, null));

        ArgumentCaptor<Double> minLat = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Double> maxLat = ArgumentCaptor.forClass(Double.class);
        verify(eventRepository).findInBoundingBox(minLat.capture(), maxLat.capture(), anyDouble(), anyDouble(), any(), any(), any());
        assertThat(maxLat.getValue() - LAT).isCloseTo(25.0 * DEGREES_PER_KM, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(LAT - minLat.getValue()).isCloseTo(25.0 * DEGREES_PER_KM, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void getFeed_rejectsMissingOrImpossibleLocations() {
        assertThatThrownBy(() -> service.getFeed(cityQuery(null, null, null, null, 0, 20)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("location");
        assertThatThrownBy(() -> service.getFeed(cityQuery("  ", null, null, null, 0, 20)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.getFeed(aroundQuery(LAT, null, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("lng");
        assertThatThrownBy(() -> service.getFeed(aroundQuery(null, LNG, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("lat");
        assertThatThrownBy(() -> service.getFeed(aroundQuery(95.0, LNG, null))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.getFeed(aroundQuery(Double.NaN, LNG, null))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.getFeed(aroundQuery(LAT, 181.0, null))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void getFeed_rejectsAnUnreasonableRadius() {
        for (Double radius : new Double[]{0.0, -5.0, 100.5, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> service.getFeed(aroundQuery(LAT, LNG, radius)))
                    .as("radius=" + radius).isInstanceOf(BadRequestException.class).hasMessageContaining("radiusKm");
        }
    }

    @Test
    void getFeed_whenBothAPointAndACityAreGiven_thePointWins() {
        when(eventRepository.findInBoundingBox(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), any(), any()))
                .thenReturn(List.of());

        service.getFeed(new EventFeedQuery(LAT, LNG, 10.0, "Mumbai", null, null, null, 0, 20));

        verify(eventRepository, never()).findForCity(any(), any(), any(), any());
    }

    // ------------------------------------------------------------------ category, paging, freshness

    @Test
    void getFeed_filtersByCategoryIgnoringCase() {
        when(eventRepository.findForCity(eq("chennai"), any(), any(), any())).thenReturn(List.of(
                event("Gig", TODAY, null, "Music", 0), event("Match", TODAY, null, "Sports", 0), event("Misc", TODAY, null, null, 0)));

        assertThat(service.getFeed(cityQuery("Chennai", null, null, " MUSIC ", 0, 20)).events())
                .extracting(EventResponse::title).containsExactly("Gig");
    }

    @Test
    void getFeed_pagesTheRankedResults_andReportsTotals() {
        List<LocalEvent> events = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            events.add(event(String.format("e%02d", i), TODAY.plusDays(i % 7), null, "Music", 0));
        }
        when(eventRepository.findForCity(eq("chennai"), any(), any(), any())).thenReturn(events);

        EventFeedResponse first = service.getFeed(cityQuery("Chennai", null, null, null, 0, 10));
        EventFeedResponse last = service.getFeed(cityQuery("Chennai", null, null, null, 2, 10));
        EventFeedResponse beyond = service.getFeed(cityQuery("Chennai", null, null, null, 9, 10));

        assertThat(first.events()).hasSize(10);
        assertThat(first.totalElements()).isEqualTo(25);
        assertThat(first.totalPages()).isEqualTo(3);
        assertThat(last.events()).hasSize(5);
        assertThat(beyond.events()).isEmpty();
    }

    @Test
    void getFeed_clampsThePageSize_andReportsWhenTheDataWasLastRefreshed() {
        when(eventRepository.findForCity(eq("chennai"), any(), any(), any())).thenReturn(List.of());

        EventFeedResponse feed = service.getFeed(cityQuery("Chennai", null, null, null, -3, 5000));

        assertThat(feed.size()).isEqualTo(50);
        assertThat(feed.page()).isZero();
        assertThat(feed.lastRefreshedAt()).isEqualTo(LocalDateTime.of(2026, 10, 6, 6, 0));
    }

    // ------------------------------------------------------------------ itinerary relevance

    private Trip trip(String ownerId, LocalDate start, LocalDate end) {
        return Trip.builder().id("t1").user(User.builder().id(ownerId).build()).city("Udaipur")
                .startDate(start).endDate(end).build();
    }

    @Test
    void getFeedForTrip_showsTheTripsCityDuringTheTrip() {
        when(authenticatedUserService.getCurrentUser()).thenReturn(User.builder().id("u1").build());
        when(tripRepository.findById("t1")).thenReturn(Optional.of(trip("u1", LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 15))));
        when(eventRepository.findForCity(eq("udaipur"), eq(LocalDate.of(2026, 11, 12)), eq(LocalDate.of(2026, 11, 15)), any()))
                .thenReturn(List.of(event("Lake Festival", LocalDate.of(2026, 11, 13), null, "Festival", 0)));

        EventFeedResponse feed = service.getFeedForTrip("t1", null, 0, 20);

        assertThat(feed.events()).extracting(EventResponse::title).containsExactly("Lake Festival");
        assertThat(feed.from()).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(feed.to()).isEqualTo(LocalDate.of(2026, 11, 15));
    }

    @Test
    void getFeedForTrip_anotherUsersTrip_looksLikeItDoesNotExist() {
        when(authenticatedUserService.getCurrentUser()).thenReturn(User.builder().id("intruder").build());
        when(tripRepository.findById("t1")).thenReturn(Optional.of(trip("u1", TODAY, TODAY.plusDays(3))));

        assertThatThrownBy(() -> service.getFeedForTrip("t1", null, 0, 20)).isInstanceOf(ResourceNotFoundException.class);
        verify(eventRepository, never()).findForCity(any(), any(), any(), any());
    }

    @Test
    void getFeedForTrip_aMissingTrip_isNotFound() {
        when(authenticatedUserService.getCurrentUser()).thenReturn(User.builder().id("u1").build());
        when(tripRepository.findById("t1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getFeedForTrip("t1", null, 0, 20)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getFeedForTrip_aVeryLongTripIsCappedAtTheMaximumWindow() {
        when(authenticatedUserService.getCurrentUser()).thenReturn(User.builder().id("u1").build());
        LocalDate start = LocalDate.of(2026, 11, 1);
        when(tripRepository.findById("t1")).thenReturn(Optional.of(trip("u1", start, start.plusDays(300))));
        when(eventRepository.findForCity(any(), any(), any(), any())).thenReturn(List.of());

        EventFeedResponse feed = service.getFeedForTrip("t1", null, 0, 20);

        assertThat(feed.to()).isEqualTo(start.plusDays(89));
    }

    // ------------------------------------------------------------------ past events must not surface

    @Test
    void getFeed_aStartDateInThePast_isMovedUpToToday() {
        when(eventRepository.findForCity(eq("chennai"), eq(TODAY), eq(TODAY.plusDays(3)), any())).thenReturn(List.of());

        EventFeedResponse feed = service.getFeed(cityQuery("Chennai", TODAY.minusDays(10), TODAY.plusDays(3), null, 0, 20));

        assertThat(feed.from()).isEqualTo(TODAY);
        assertThat(feed.to()).isEqualTo(TODAY.plusDays(3));
    }

    @Test
    void getFeed_aPastStartDateWithoutAnEnd_usesTheDefaultWindowFromToday() {
        when(eventRepository.findForCity(eq("chennai"), eq(TODAY), eq(TODAY.plusDays(6)), any())).thenReturn(List.of());

        EventFeedResponse feed = service.getFeed(cityQuery("Chennai", LocalDate.of(2020, 1, 1), null, null, 0, 20));

        assertThat(feed.from()).isEqualTo(TODAY);
        assertThat(feed.to()).isEqualTo(TODAY.plusDays(6));
    }

    @Test
    void getFeed_anEndDateInThePast_isRejectedBecauseThereIsNothingLeftToShow() {
        assertThatThrownBy(() -> service.getFeed(cityQuery("Chennai", null, TODAY.minusDays(1), null, 0, 20)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("past");
        assertThatThrownBy(() -> service.getFeed(cityQuery("Chennai", TODAY.minusDays(9), TODAY.minusDays(2), null, 0, 20)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("past");
        verify(eventRepository, never()).findForCity(any(), any(), any(), any());
    }

    @Test
    void getFeed_aRangeEndingToday_stillShowsTodaysEvents() {
        when(eventRepository.findForCity(eq("chennai"), eq(TODAY), eq(TODAY), any())).thenReturn(List.of());

        assertThat(service.getFeed(cityQuery("Chennai", null, TODAY, null, 0, 20)).from()).isEqualTo(TODAY);
    }

    @Test
    void getFeed_byLocation_alsoNeverSearchesBeforeToday() {
        when(eventRepository.findInBoundingBox(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(TODAY), eq(TODAY.plusDays(2)), any()))
                .thenReturn(List.of());

        service.getFeed(new EventFeedQuery(LAT, LNG, 10.0, null, TODAY.minusDays(30), TODAY.plusDays(2), null, 0, 20));

        verify(eventRepository).findInBoundingBox(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(TODAY), eq(TODAY.plusDays(2)), any());
    }

    @Test
    void getFeedForTrip_aTripUnderway_isShownFromTodayOnly() {
        when(authenticatedUserService.getCurrentUser()).thenReturn(User.builder().id("u1").build());
        when(tripRepository.findById("t1")).thenReturn(Optional.of(trip("u1", TODAY.minusDays(3), TODAY.plusDays(4))));
        when(eventRepository.findForCity(eq("udaipur"), eq(TODAY), eq(TODAY.plusDays(4)), any())).thenReturn(List.of());

        EventFeedResponse feed = service.getFeedForTrip("t1", null, 0, 20);

        assertThat(feed.from()).isEqualTo(TODAY);
        assertThat(feed.to()).isEqualTo(TODAY.plusDays(4));
    }

    @Test
    void getFeedForTrip_aTripThatIsOver_showsNothingAndDoesNotEvenQuery() {
        when(authenticatedUserService.getCurrentUser()).thenReturn(User.builder().id("u1").build());
        when(tripRepository.findById("t1")).thenReturn(Optional.of(trip("u1", TODAY.minusDays(10), TODAY.minusDays(1))));

        EventFeedResponse feed = service.getFeedForTrip("t1", null, 0, 20);

        assertThat(feed.events()).isEmpty();
        assertThat(feed.from()).isEqualTo(TODAY.minusDays(10));
        assertThat(feed.to()).isEqualTo(TODAY.minusDays(1));
        verify(eventRepository, never()).findForCity(any(), any(), any(), any());
    }
}