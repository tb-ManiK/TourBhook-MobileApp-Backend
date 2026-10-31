package com.tourbhook.api.service.dataimport;

import com.tourbhook.api.dto.dataimport.ImportedItemType;
import com.tourbhook.api.repository.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfirmationEmailParserTest {

    private final ConfirmationEmailParser parser = new ConfirmationEmailParser();

    @Test
    void parse_singleFlightInPastedText_readsEveryField() {
        String email = """
                Hi Arun, your booking is confirmed.

                Booking Reference: XY12AB
                Airline: IndiGo
                Flight: 6E 204
                From: Chennai (MAA)
                To: Delhi (DEL)
                Departure: 12 Nov 2026 06:30
                Arrival: 12 Nov 2026 09:15
                Total: INR 8,400
                """;

        ParseResult result = parser.parse(email);

        assertThat(result.items()).hasSize(1);
        ImportedItem flight = result.items().get(0);
        assertThat(flight.type()).isEqualTo(ImportedItemType.FLIGHT);
        assertThat(flight.title()).isEqualTo("IndiGo 6E 204: Chennai to Delhi");
        assertThat(flight.date()).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(flight.time()).isEqualTo(LocalTime.of(6, 30));
        assertThat(flight.durationMinutes()).isEqualTo(165);
        assertThat(flight.city()).isEqualTo("Delhi");
        assertThat(flight.cost()).isEqualByComparingTo("8400");
        assertThat(flight.confirmationCode()).isEqualTo("XY12AB");
    }

    @Test
    void parse_roundTripWithHotel_readsEverySegmentAndBooksTotalOnce() {
        String email = """
                Booking ID: TRIP-77
                Airline: IndiGo
                Flight: 6E 204
                From: Chennai
                To: Delhi
                Departure: 12 Nov 2026 06:30
                Arrival: 12 Nov 2026 09:15

                Airline: IndiGo
                Flight: 6E 205
                From: Delhi
                To: Chennai
                Departure: 15 Nov 2026 18:00
                Arrival: 15 Nov 2026 20:45

                Hotel: Lake View Residency
                City: Udaipur
                Check-in: 12 Nov 2026 from 14:00
                Check-out: 14th Nov 2026
                Total: Rs. 22,500.50
                """;

        ParseResult result = parser.parse(email);

        assertThat(result.items()).extracting(ImportedItem::type).containsExactly(
                ImportedItemType.FLIGHT, ImportedItemType.FLIGHT,
                ImportedItemType.HOTEL_CHECK_IN, ImportedItemType.HOTEL_CHECK_OUT);
        assertThat(result.items().get(2).time()).isEqualTo(LocalTime.of(14, 0));
        assertThat(result.items().get(3).date()).isEqualTo(LocalDate.of(2026, 11, 14));
        assertThat(result.items().get(0).cost()).isEqualByComparingTo("22500.50");
        assertThat(result.items().get(1).cost()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.items()).allMatch(item -> "TRIP-77".equals(item.confirmationCode()));
    }

    @Test
    void parse_mimeMultipart_prefersPlainTextAndDecodesQuotedPrintable() {
        String eml = String.join("\n",
                "From: bookings@airline.example",
                "Subject: Your e-ticket",
                "MIME-Version: 1.0",
                "Message-ID: <1@example>",
                "Content-Type: multipart/alternative; boundary=\"BND\"",
                "",
                "--BND",
                "Content-Type: text/plain; charset=UTF-8",
                "Content-Transfer-Encoding: quoted-printable",
                "",
                "Airline: Air India",
                "Flight: AI 540",
                "From: Chennai (MAA)",
                "To: Mumbai (BOM)",
                "Departure: 20 Dec 2026 =",
                "07:10",
                "",
                "--BND",
                "Content-Type: text/html; charset=UTF-8",
                "",
                "<p>Flight: ZZ 999</p>",
                "--BND--",
                "");

        ParseResult result = parser.parse(eml);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).title()).isEqualTo("Air India AI 540: Chennai to Mumbai");
        assertThat(result.items().get(0).time()).isEqualTo(LocalTime.of(7, 10));
    }

    @Test
    void parse_htmlOnlyTableEmail_joinsLabelAndValueCells() {
        String eml = String.join("\n",
                "Subject: Booking",
                "MIME-Version: 1.0",
                "Message-ID: <2@example>",
                "Content-Type: text/html; charset=UTF-8",
                "",
                "<table>"
                        + "<tr><td>Confirmation Number</td><td>HT-99120</td></tr>"
                        + "<tr><td>Hotel:</td><td>Sea&nbsp;Breeze &amp; Spa</td></tr>"
                        + "<tr><td>City</td><td>Goa</td></tr>"
                        + "<tr><td>Check-in</td><td>2026-12-24</td></tr>"
                        + "<tr><td>Check-out</td><td>26/12/2026</td></tr>"
                        + "<tr><td>Total</td><td>&#8377; 18,000</td></tr>"
                        + "</table>");

        ParseResult result = parser.parse(eml);

        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).title()).isEqualTo("Hotel check-in: Sea Breeze & Spa");
        assertThat(result.items().get(0).city()).isEqualTo("Goa");
        assertThat(result.items().get(1).date()).isEqualTo(LocalDate.of(2026, 12, 26)); // day-first
        // The rupee sign arrives as &#8377; - it must not be mistaken for the amount.
        assertThat(result.items().get(0).cost()).isEqualByComparingTo("18000");
    }

    @Test
    void parse_totalInForeignCurrency_isNotConvertedAndIsReported() {
        ParseResult result = parser.parse("""
                Flight: AA 1
                From: Delhi
                To: Dubai
                Departure: 1 Dec 2026 02:00
                Total: USD 450.00
                """);

        assertThat(result.items().get(0).cost()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("not in INR"));
    }

    @Test
    void parse_flightWithUnreadableDate_isSkippedButOtherSegmentsSurvive() {
        ParseResult result = parser.parse("""
                Flight: A 1
                From: X
                To: Y
                Departure: soon

                Flight: B 2
                From: Y
                To: Z
                Departure: 3 Jan 2027 10:00
                """);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).title()).contains("B 2");
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("departure date"));
    }

    @Test
    void parse_overnightFlightWithTimeOnlyArrival_wrapsPastMidnight() {
        ParseResult result = parser.parse("""
                Flight: N 1
                From: A
                To: B
                Departure: 5 Feb 2027 23:30
                Arrival: 00:45
                """);

        assertThat(result.items().get(0).durationMinutes()).isEqualTo(75);
    }

    @Test
    void parse_textWithoutBookingDetails_isRejectedWithHelpfulMessage() {
        assertThatThrownBy(() -> parser.parse("Hello, see you at 10:30 tomorrow. Cheers"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Label: value");
    }
}