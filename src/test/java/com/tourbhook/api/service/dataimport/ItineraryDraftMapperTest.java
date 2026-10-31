package com.tourbhook.api.service.dataimport;

import com.tourbhook.api.dto.dataimport.DraftActivity;
import com.tourbhook.api.dto.dataimport.ImportedItemType;
import com.tourbhook.api.dto.dataimport.ItineraryDraft;
import com.tourbhook.api.repository.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ItineraryDraftMapperTest {

    private static final LocalDate DAY_1 = LocalDate.of(2026, 11, 12);

    private final ItineraryDraftMapper mapper = new ItineraryDraftMapper();

    private ImportedItem item(ImportedItemType type, String title, LocalDate date, LocalTime time,
                              Integer minutes, String city) {
        return new ImportedItem(type, title, date, time, minutes, BigDecimal.ZERO, city, null);
    }

    private ParseResult result(ImportedItem... items) {
        return new ParseResult(List.of(items), List.of());
    }

    @Test
    void toDraft_buildsOneDayPerCalendarDay_includingEmptyOnes() {
        ItineraryDraft draft = mapper.toDraft(result(
                item(ImportedItemType.FLIGHT, "Out", DAY_1, LocalTime.of(6, 30), 165, "Delhi"),
                item(ImportedItemType.FLIGHT, "Back", DAY_1.plusDays(3), LocalTime.of(18, 0), 165, "Chennai")), null);

        assertThat(draft.startDate()).isEqualTo(DAY_1);
        assertThat(draft.endDate()).isEqualTo(DAY_1.plusDays(3));
        assertThat(draft.days()).hasSize(4);
        assertThat(draft.days()).extracting("dayNumber").containsExactly(1, 2, 3, 4);
        assertThat(draft.days().get(1).activities()).isEmpty();
    }

    @Test
    void toDraft_ordersEventsByTimeAndFormatsThemForTheItinerary() {
        ItineraryDraft draft = mapper.toDraft(result(
                item(ImportedItemType.HOTEL_CHECK_IN, "Hotel check-in", DAY_1, null, null, "Udaipur"),
                item(ImportedItemType.FLIGHT, "Flight", DAY_1, LocalTime.of(6, 30), 165, "Udaipur")), null);

        List<DraftActivity> day = draft.days().get(0).activities();
        assertThat(day).extracting(DraftActivity::time).containsExactly("06:30 AM", "02:00 PM");
        assertThat(day.get(0).duration()).isEqualTo("165 min");
        assertThat(day.get(1).duration()).isEqualTo("30 min");
        assertThat(day.get(0).icon()).isEqualTo("flight");
        assertThat(day.get(1).icon()).isEqualTo("hotel");
    }

    @Test
    void toDraft_durationsUseOnlyHrOrMin_soTheReoptimiserCanParseThem() {
        ItineraryDraft draft = mapper.toDraft(result(
                item(ImportedItemType.ACTIVITY, "Two hours", DAY_1, LocalTime.of(9, 0), 120, "Jaipur"),
                item(ImportedItemType.ACTIVITY, "Ninety", DAY_1, LocalTime.of(12, 0), 90, "Jaipur")), null);

        assertThat(draft.days().get(0).activities()).extracting(DraftActivity::duration)
                .containsExactly("2 hr", "90 min");
    }

    @Test
    void toDraft_missingTime_fallsBackToTypeDefaultAndSaysSo() {
        ItineraryDraft draft = mapper.toDraft(result(
                item(ImportedItemType.ACTIVITY, "Fort", DAY_1, null, null, "Jaipur")), null);

        assertThat(draft.days().get(0).activities().get(0).time()).isEqualTo("10:00 AM");
        assertThat(draft.warnings()).anyMatch(w -> w.contains("assumed 10:00 AM"));
    }

    @Test
    void toDraft_cityComesFromOverrideThenHotelThenFirstFlight() {
        ImportedItem flight = item(ImportedItemType.FLIGHT, "Flight", DAY_1, LocalTime.of(6, 0), null, "Delhi");
        ImportedItem hotel = item(ImportedItemType.HOTEL_CHECK_IN, "Hotel", DAY_1, null, null, "Udaipur");

        assertThat(mapper.toDraft(result(flight, hotel), null).city()).isEqualTo("Udaipur");
        assertThat(mapper.toDraft(result(flight), null).city()).isEqualTo("Delhi");
        assertThat(mapper.toDraft(result(flight, hotel), "  Agra ").city()).isEqualTo("Agra");
    }

    @Test
    void toDraft_noCityAnywhere_asksTheClientForIt() {
        assertThatThrownBy(() -> mapper.toDraft(
                result(item(ImportedItemType.ACTIVITY, "Walk", DAY_1, null, null, null)), null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("'city'");
    }

    @Test
    void toDraft_dropsDuplicatesAndUndatedEntries() {
        ImportedItem fort = item(ImportedItemType.ACTIVITY, "Fort", DAY_1, LocalTime.of(10, 0), null, "Jaipur");
        ImportedItem undated = item(ImportedItemType.ACTIVITY, "No date", null, null, null, null);

        ItineraryDraft draft = mapper.toDraft(result(fort, fort, undated), null);

        assertThat(draft.days().get(0).activities()).hasSize(1);
        assertThat(draft.warnings()).anyMatch(w -> w.contains("no date"));
    }

    @Test
    void toDraft_implausibleDateSpan_isRejected() {
        ImportedItem first = item(ImportedItemType.ACTIVITY, "A", DAY_1, null, null, "Jaipur");
        ImportedItem typo = item(ImportedItemType.ACTIVITY, "B", DAY_1.plusYears(30), null, null, "Jaipur");

        assertThatThrownBy(() -> mapper.toDraft(result(first, typo), null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("at most " + ItineraryDraftMapper.MAX_TRIP_DAYS);
    }
}