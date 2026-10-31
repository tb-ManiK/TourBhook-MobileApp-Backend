package com.tourbhook.api.service.event;

import com.tourbhook.api.config.EventProperties;
import com.tourbhook.api.dto.event.EventResponse;
import com.tourbhook.api.entity.LocalEvent;
import com.tourbhook.api.repository.LocalEventRepository;
import com.tourbhook.api.repository.exception.BadRequestException;
import com.tourbhook.api.service.event.EventSearcher.DateRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
class EventSearcherTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T06:30:00Z"), IST);   // 12:00 on 6 Oct
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);
    private static final double DEGREES_PER_KM = 1.0 / 111.195;

    @Mock private LocalEventRepository eventRepository;

    private final EventProperties properties = new EventProperties();
    private EventSearcher searcher;

    @BeforeEach
    void setUp() {
        searcher = new EventSearcher(eventRepository, properties, CLOCK);
    }

    private LocalEvent event(String title, LocalDate start, LocalTime time, String category, double kmNorth) {
        return LocalEvent.builder().id(title).title(title).city("Chennai").cityKey("chennai").category(category)
                .latitude(13.0 + kmNorth * DEGREES_PER_KM).longitude(80.0)
                .startDate(start).endDate(start).startTime(time).source("ics").active(true).build();
    }

    // ------------------------------------------------------------------ resolveRange

    @Test
    void resolveRange_withoutDates_isTodayAndTheNextSixDays() {
        assertThat(searcher.resolveRange(null, null)).isEqualTo(new DateRange(TODAY, TODAY.plusDays(6)));
    }

    @Test
    void resolveRange_aPastStartIsMovedUpToToday_andTheDefaultWindowStartsThere() {
        assertThat(searcher.resolveRange(TODAY.minusDays(40), null)).isEqualTo(new DateRange(TODAY, TODAY.plusDays(6)));
        assertThat(searcher.resolveRange(TODAY.minusDays(40), TODAY.plusDays(2))).isEqualTo(new DateRange(TODAY, TODAY.plusDays(2)));
    }

    @Test
    void resolveRange_onlyAFutureEnd_startsToday() {
        assertThat(searcher.resolveRange(null, TODAY.plusDays(10))).isEqualTo(new DateRange(TODAY, TODAY.plusDays(10)));
    }

    @Test
    void resolveRange_aFutureStartKeepsItsOwnWindow() {
        LocalDate from = TODAY.plusDays(20);

        assertThat(searcher.resolveRange(from, null)).isEqualTo(new DateRange(from, from.plusDays(6)));
    }

    @Test
    void resolveRange_rejectsAnEndInThePast_aBackwardsRange_andAnOverlongRange() {
        assertThatThrownBy(() -> searcher.resolveRange(null, TODAY.minusDays(1)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("past");
        assertThatThrownBy(() -> searcher.resolveRange(TODAY.plusDays(5), TODAY.plusDays(2)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("before");
        assertThatThrownBy(() -> searcher.resolveRange(TODAY, TODAY.plusDays(90)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("90");
        assertThat(searcher.resolveRange(TODAY, TODAY.plusDays(89)).to()).isEqualTo(TODAY.plusDays(89));
    }

    @Test
    void today_followsTheConfiguredTimeZone_notTheServers() {
        // 19:00 UTC on 6 Oct is already 00:30 on 7 Oct in India
        Clock lateEvening = Clock.fixed(Instant.parse("2026-10-06T19:00:00Z"), IST);

        assertThat(new EventSearcher(eventRepository, properties, lateEvening).today()).isEqualTo(LocalDate.of(2026, 10, 7));
    }

    // ------------------------------------------------------------------ the no-past guarantee

    @Test
    void inCity_aRangeThatIsEntirelyInThePast_returnsNothingWithoutQueryingAnything() {
        assertThat(searcher.inCity("Chennai", TODAY.minusDays(10), TODAY.minusDays(1), null)).isEmpty();

        verify(eventRepository, never()).findForCity(any(), any(), any(), any());
    }

    @Test
    void inCity_aRangeThatStartsInThePast_isOnlyEverSearchedFromToday() {
        when(eventRepository.findForCity(eq("chennai"), eq(TODAY), eq(TODAY.plusDays(2)), any())).thenReturn(List.of());

        searcher.inCity("  Chennai ", TODAY.minusDays(5), TODAY.plusDays(2), null);

        verify(eventRepository).findForCity(eq("chennai"), eq(TODAY), eq(TODAY.plusDays(2)), any());
    }

    @Test
    void around_aRangeThatIsEntirelyInThePast_returnsNothingWithoutQueryingAnything() {
        assertThat(searcher.around(13.0, 80.0, 25, TODAY.minusDays(10), TODAY.minusDays(1), null)).isEmpty();

        verify(eventRepository, never()).findInBoundingBox(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), any(), any());
    }

    @Test
    void around_aRangeThatStartsInThePast_isOnlyEverSearchedFromToday() {
        when(eventRepository.findInBoundingBox(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(TODAY), eq(TODAY.plusDays(2)), any()))
                .thenReturn(List.of());

        searcher.around(13.0, 80.0, 25, TODAY.minusDays(30), TODAY.plusDays(2), null);

        verify(eventRepository).findInBoundingBox(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(TODAY), eq(TODAY.plusDays(2)), any());
    }

    // ------------------------------------------------------------------ ranking and filtering

    @Test
    void inCity_ordersSoonestFirst_withAllDayEventsBeforeTimedOnes_andFiltersByCategory() {
        when(eventRepository.findForCity(eq("chennai"), any(), any(), any())).thenReturn(List.of(
                event("Later", TODAY.plusDays(2), LocalTime.of(10, 0), "Music", 0),
                event("Evening", TODAY, LocalTime.of(19, 0), "Music", 0),
                event("All day", TODAY, null, "Festival", 0),
                event("Match", TODAY, LocalTime.of(9, 0), "Sports", 0)));

        assertThat(searcher.inCity("Chennai", TODAY, TODAY.plusDays(6), null)).extracting(EventResponse::title)
                .containsExactly("All day", "Match", "Evening", "Later");
        assertThat(searcher.inCity("Chennai", TODAY, TODAY.plusDays(6), " MUSIC ")).extracting(EventResponse::title)
                .containsExactly("Evening", "Later");
    }

    @Test
    void around_keepsOnlyEventsWithinTheRadius_usingTheExactDistance() {
        when(eventRepository.findInBoundingBox(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), any(), any()))
                .thenReturn(List.of(
                        event("near", TODAY, LocalTime.of(18, 0), "Music", 2),
                        event("edge-in", TODAY, LocalTime.of(18, 0), "Music", 24.9),
                        event("edge-out", TODAY, LocalTime.of(18, 0), "Music", 25.1)));

        List<EventResponse> found = searcher.around(13.0, 80.0, 25.0, TODAY, TODAY.plusDays(6), null);

        assertThat(found).extracting(EventResponse::title).containsExactly("near", "edge-in");
        assertThat(found.get(0).distanceKm()).isEqualTo(2.0);
    }

    @Test
    void requireCoordinateAndResolveRadius_rejectNonsense() {
        assertThatThrownBy(() -> searcher.requireCoordinate(Double.NaN, "lat", 90)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> searcher.requireCoordinate(null, "lng", 180)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> searcher.requireCoordinate(181.0, "lng", 180)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> searcher.resolveRadius(0.0)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> searcher.resolveRadius(100.5)).isInstanceOf(BadRequestException.class);
        assertThat(searcher.resolveRadius(null)).isEqualTo(25.0);
        assertThat(searcher.requireCoordinate(13.08, "lat", 90)).isEqualTo(13.08);
    }

    @Test
    void pageSlice_cutsAPage_andAnOutOfRangePageIsEmpty() {
        List<Integer> all = List.of(1, 2, 3, 4, 5, 6, 7);

        PageSlice<Integer> second = PageSlice.of(all, 1, 3, 50);
        PageSlice<Integer> beyond = PageSlice.of(all, 9, 3, 50);
        PageSlice<Integer> clamped = PageSlice.of(all, -4, 5000, 50);

        assertThat(second.items()).containsExactly(4, 5, 6);
        assertThat(second.totalPages()).isEqualTo(3);
        assertThat(second.totalElements()).isEqualTo(7);
        assertThat(beyond.items()).isEmpty();
        assertThat(clamped.page()).isZero();
        assertThat(clamped.size()).isEqualTo(50);
    }
}