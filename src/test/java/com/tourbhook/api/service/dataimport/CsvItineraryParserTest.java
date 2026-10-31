package com.tourbhook.api.service.dataimport;

import com.tourbhook.api.dto.dataimport.ImportedItemType;
import com.tourbhook.api.repository.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvItineraryParserTest {

    private final CsvItineraryParser parser = new CsvItineraryParser();

    @Test
    void parse_wellFormedFile_readsRowsInAnyDateFormatAndColumnCase() {
        String csv = """
                Date,Time,Title,Type,Duration_Minutes,Cost,City,Confirmation_Code
                2026-11-12,06:30,"IndiGo 6E 204: Chennai to Delhi",flight,165,"8,400",Delhi,XY12AB
                12 Nov 2026,2:00 PM,Hotel check-in: Lake View,hotel_check_in,,0,Udaipur,
                13/11/2026,,"Visit City Palace, Old Town",activity,180,700,,
                """;

        ParseResult result = parser.parse(csv);

        assertThat(result.items()).hasSize(3);
        ImportedItem flight = result.items().get(0);
        assertThat(flight.type()).isEqualTo(ImportedItemType.FLIGHT);
        assertThat(flight.cost()).isEqualByComparingTo("8400");
        assertThat(flight.confirmationCode()).isEqualTo("XY12AB");
        assertThat(result.items().get(1).time()).isEqualTo(LocalTime.of(14, 0));
        assertThat(result.items().get(2).title()).isEqualTo("Visit City Palace, Old Town");
        assertThat(result.items().get(2).date()).isEqualTo(LocalDate.of(2026, 11, 13)); // day-first
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void parse_badRows_areSkippedOrDowngradedWithRowNumberedWarnings() {
        String csv = """
                date,title,type,duration_minutes,cost
                not-a-date,Broken row,activity,,
                2026-11-14,Mystery,teleport,abc,$20
                """;

        ParseResult result = parser.parse(csv);

        assertThat(result.items()).hasSize(1);
        ImportedItem mystery = result.items().get(0);
        assertThat(mystery.type()).isEqualTo(ImportedItemType.ACTIVITY);
        assertThat(mystery.durationMinutes()).isNull();
        assertThat(mystery.cost()).isEqualByComparingTo("0");
        assertThat(result.warnings())
                .anyMatch(w -> w.startsWith("Row 2:") && w.contains("date"))
                .anyMatch(w -> w.startsWith("Row 3:") && w.contains("unknown type"))
                .anyMatch(w -> w.contains("duration_minutes"))
                .anyMatch(w -> w.contains("not in INR"));
    }

    @Test
    void parse_rowsShorterThanTheHeader_doNotFail() {
        ParseResult result = parser.parse("""
                date,title,type,cost
                2026-03-01,Short row
                2026-03-02,Full row,food,100
                """);

        assertThat(result.items()).hasSize(2);
    }

    @Test
    void parse_missingRequiredColumn_namesTheColumns() {
        assertThatThrownBy(() -> parser.parse("when,what\n1,2"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("date, title");
    }

    @Test
    void parse_duplicateColumnNames_areRejected() {
        assertThatThrownBy(() -> parser.parse("date,title,title\n2026-01-01,a,b"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void parse_fileWithNoUsableRows_isRejected() {
        assertThatThrownBy(() -> parser.parse("date,title\nnope,x"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("No importable rows");
    }

    @Test
    void parse_moreRowsThanTheLimit_isRejected() {
        StringBuilder csv = new StringBuilder("date,title\n");
        for (int i = 0; i <= CsvItineraryParser.MAX_DATA_ROWS; i++) {
            csv.append("2026-01-01,row ").append(i).append('\n');
        }

        assertThatThrownBy(() -> parser.parse(csv.toString()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(String.valueOf(CsvItineraryParser.MAX_DATA_ROWS));
    }
}