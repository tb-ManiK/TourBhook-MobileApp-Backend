package com.tourbhook.api.service.event;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IcsParserTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private List<IcsParser.IcsEvent> parse(String body) {
        return IcsParser.parse("BEGIN:VCALENDAR\nVERSION:2.0\n" + body + "END:VCALENDAR\n", IST);
    }

    @Test
    void parse_allDayEvent_endsOnItsOwnDayBecauseICalendarEndsAreExclusive() {
        IcsParser.IcsEvent diwali = parse("""
                BEGIN:VEVENT
                UID:diwali-2026
                DTSTART;VALUE=DATE:20261108
                DTEND;VALUE=DATE:20261109
                SUMMARY:Diwali
                END:VEVENT
                """).get(0);

        assertThat(diwali.startDate()).isEqualTo(LocalDate.of(2026, 11, 8));
        assertThat(diwali.endDate()).isEqualTo(LocalDate.of(2026, 11, 8));
        assertThat(diwali.startTime()).isNull();
    }

    @Test
    void parse_multiDayAllDayEvent_includesItsLastDay() {
        IcsParser.IcsEvent festival = parse("""
                BEGIN:VEVENT
                DTSTART;VALUE=DATE:20261112
                DTEND;VALUE=DATE:20261116
                SUMMARY:Lake Festival
                END:VEVENT
                """).get(0);

        assertThat(festival.startDate()).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(festival.endDate()).isEqualTo(LocalDate.of(2026, 11, 15));
    }

    @Test
    void parse_eventWithoutAnEnd_isASingleDay() {
        IcsParser.IcsEvent event = parse("BEGIN:VEVENT\nDTSTART;VALUE=DATE:20261112\nSUMMARY:One day\nEND:VEVENT\n").get(0);

        assertThat(event.endDate()).isEqualTo(event.startDate());
    }

    @Test
    void parse_utcTimes_areConvertedToTheConfiguredZone() {
        IcsParser.IcsEvent concert = parse("""
                BEGIN:VEVENT
                DTSTART:20261112T133000Z
                DTEND:20261112T153000Z
                SUMMARY:Concert
                END:VEVENT
                """).get(0);

        assertThat(concert.startDate()).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(concert.startTime()).isEqualTo(LocalTime.of(19, 0)); // 13:30 UTC is 19:00 in India
    }

    @Test
    void parse_aLateUtcTime_canLandOnTheNextLocalDay() {
        IcsParser.IcsEvent lateShow = parse("BEGIN:VEVENT\nDTSTART:20261112T203000Z\nSUMMARY:Late show\nEND:VEVENT\n").get(0);

        assertThat(lateShow.startDate()).isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(lateShow.startTime()).isEqualTo(LocalTime.of(2, 0));
    }

    @Test
    void parse_localTimesWithOrWithoutATimeZoneName_areTakenAsWritten() {
        List<IcsParser.IcsEvent> events = parse("""
                BEGIN:VEVENT
                DTSTART;TZID=Asia/Kolkata:20261112T190000
                SUMMARY:With zone
                END:VEVENT
                BEGIN:VEVENT
                DTSTART:20261112T190000
                SUMMARY:Floating
                END:VEVENT
                """);

        assertThat(events).extracting(IcsParser.IcsEvent::startTime).containsOnly(LocalTime.of(19, 0));
    }

    @Test
    void parse_eventRunningPastMidnight_endsOnTheNextDay_butNotWhenItEndsExactlyAtMidnight() {
        List<IcsParser.IcsEvent> events = parse("""
                BEGIN:VEVENT
                DTSTART:20261112T190000
                DTEND:20261113T020000
                SUMMARY:All night
                END:VEVENT
                BEGIN:VEVENT
                DTSTART:20261112T190000
                DTEND:20261113T000000
                SUMMARY:Until midnight
                END:VEVENT
                """);

        assertThat(events.get(0).endDate()).isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(events.get(1).endDate()).isEqualTo(LocalDate.of(2026, 11, 12));
    }

    @Test
    void parse_readsTextFieldsUnfoldsLongLinesAndUnescapes() {
        IcsParser.IcsEvent event = parse("BEGIN:VEVENT\nUID:abc\nDTSTART;VALUE=DATE:20261112\nSUMMARY:Food Fair\n"
                + "DESCRIPTION:Street food\\, sweets\\; and more\\nHello wor\n ld\n"
                + "LOCATION:Marina Beach\\, Chennai\nURL:https://example.org/fair\n"
                + "GEO:13.0500;80.2824\nEND:VEVENT\n").get(0);

        assertThat(event.uid()).isEqualTo("abc");
        assertThat(event.description()).isEqualTo("Street food, sweets; and more\nHello world");
        assertThat(event.location()).isEqualTo("Marina Beach, Chennai");
        assertThat(event.url()).isEqualTo("https://example.org/fair");
        assertThat(event.latitude()).isEqualTo(13.05);
        assertThat(event.longitude()).isEqualTo(80.2824);
    }

    @Test
    void parse_acceptsWindowsLineEndingsAndColonsInsideQuotedParameters() {
        String ics = ("BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nDTSTART;VALUE=DATE:20261112\r\n"
                + "ATTACH;FMTTYPE=\"text/x:y\":https://example.org/a.png\r\nSUMMARY:Windows file\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n");

        assertThat(IcsParser.parse(ics, IST)).extracting(IcsParser.IcsEvent::summary).containsExactly("Windows file");
    }

    @Test
    void parse_marksCancelledEvents() {
        assertThat(parse("BEGIN:VEVENT\nDTSTART;VALUE=DATE:20261112\nSUMMARY:Off\nSTATUS:CANCELLED\nEND:VEVENT\n")
                .get(0).cancelled()).isTrue();
    }

    @Test
    void parse_skipsBrokenEventsWithoutLosingTheGoodOnes() {
        List<IcsParser.IcsEvent> events = parse("""
                BEGIN:VEVENT
                DTSTART;VALUE=DATE:20261112
                END:VEVENT
                BEGIN:VEVENT
                DTSTART;VALUE=DATE:20261340
                SUMMARY:Month thirteen
                END:VEVENT
                BEGIN:VEVENT
                DTSTART;VALUE=DATE:ABCDEFGH
                SUMMARY:Not a date
                END:VEVENT
                BEGIN:VEVENT
                DTSTART:soon
                SUMMARY:Vague
                END:VEVENT
                BEGIN:VEVENT
                DTSTART;VALUE=DATE:20261112
                SUMMARY:Fine
                GEO:not;numbers
                END:VEVENT
                """);

        assertThat(events).extracting(IcsParser.IcsEvent::summary).containsExactly("Fine");
        assertThat(events.get(0).latitude()).isNull();
    }
}