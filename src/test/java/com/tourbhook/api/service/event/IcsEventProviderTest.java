package com.tourbhook.api.service.event;

import com.tourbhook.api.config.EventProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IcsEventProviderTest {

    private static final String FEED = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:before
            DTSTART;VALUE=DATE:20261101
            DTEND;VALUE=DATE:20261102
            SUMMARY:Already over
            END:VEVENT
            BEGIN:VEVENT
            UID:still-running
            DTSTART;VALUE=DATE:20261108
            DTEND;VALUE=DATE:20261115
            SUMMARY:Lake Festival
            LOCATION:Pichola
            END:VEVENT
            BEGIN:VEVENT
            UID:in-window
            DTSTART:20261112T133000Z
            SUMMARY:Evening concert
            END:VEVENT
            BEGIN:VEVENT
            UID:cancelled
            DTSTART;VALUE=DATE:20261112
            SUMMARY:Called off
            STATUS:CANCELLED
            END:VEVENT
            BEGIN:VEVENT
            UID:later
            DTSTART;VALUE=DATE:20261201
            SUMMARY:Next month
            END:VEVENT
            END:VCALENDAR
            """;

    private final EventProperties properties = new EventProperties();
    private final IcsEventProvider provider = new IcsEventProvider(properties, new RestTemplateBuilder());

    private EventProperties.IcsFeed feed(String city, String category) {
        EventProperties.IcsFeed feed = new EventProperties.IcsFeed();
        feed.setCity(city);
        feed.setUrl("https://example.org/feed.ics");
        feed.setCategory(category);
        return feed;
    }

    @Test
    void toExternalEvents_keepsEventsOverlappingTheWindow_includingOnesAlreadyUnderway() {
        List<ExternalEvent> events = provider.toExternalEvents(FEED, feed("Udaipur", "Festival"),
                LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 20));

        assertThat(events).extracting(ExternalEvent::title).containsExactly("Lake Festival", "Evening concert");
    }

    @Test
    void toExternalEvents_skipsCancelledEvents() {
        List<ExternalEvent> events = provider.toExternalEvents(FEED, feed("Udaipur", "Festival"),
                LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 12));

        assertThat(events).extracting(ExternalEvent::title).doesNotContain("Called off");
    }

    @Test
    void toExternalEvents_mapsFeedDetailsOntoTheEvent() {
        ExternalEvent festival = provider.toExternalEvents(FEED, feed("Udaipur", "Festival"),
                LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 20)).get(0);

        assertThat(festival.category()).isEqualTo("Festival");
        assertThat(festival.venueName()).isEqualTo("Pichola");
        assertThat(festival.startDate()).isEqualTo(LocalDate.of(2026, 11, 8));
        assertThat(festival.endDate()).isEqualTo(LocalDate.of(2026, 11, 14));
    }

    @Test
    void toExternalEvents_idIncludesTheDate_soAReschedulingIsANewEvent() {
        ExternalEvent festival = provider.toExternalEvents(FEED, feed("Udaipur", "Festival"),
                LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 20)).get(0);

        assertThat(festival.externalId()).isEqualTo("still-running|2026-11-08");
    }

    @Test
    void isEnabled_onlyWhenAtLeastOneFeedIsConfigured() {
        assertThat(provider.isEnabled()).isFalse();

        properties.getIcsFeeds().add(feed("Udaipur", "Festival"));

        assertThat(provider.isEnabled()).isTrue();
    }

    @Test
    void fetch_forACityWithoutAFeed_returnsNothingWithoutTouchingTheNetwork() {
        properties.getIcsFeeds().add(feed("Udaipur", "Festival"));

        assertThat(provider.fetch(new SyncArea("Chennai", 13.08, 80.27),
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30))).isEmpty();
    }
}