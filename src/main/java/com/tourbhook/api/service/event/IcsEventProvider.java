package com.tourbhook.api.service.event;

import com.tourbhook.api.config.EventProperties;
import com.tourbhook.api.repository.exception.ExternalServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class IcsEventProvider implements EventProvider {

    static final String SOURCE = "ics";

    private final EventProperties properties;
    private final RestTemplate restTemplate;

    @Autowired
    public IcsEventProvider(EventProperties properties, RestTemplateBuilder builder) {
        this(properties, builder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(20))
                .build());
    }

    IcsEventProvider(EventProperties properties, RestTemplate restTemplate) {
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    @Override
    public String sourceName() {
        return SOURCE;
    }

    @Override
    public boolean isEnabled() {
        return !properties.getIcsFeeds().isEmpty();
    }

    @Override
    public List<ExternalEvent> fetch(SyncArea area, LocalDate from, LocalDate to) {
        List<EventProperties.IcsFeed> feeds = properties.getIcsFeeds().stream()
                .filter(feed -> feed.getCity() != null && feed.getCity().trim().toLowerCase(Locale.ROOT).equals(area.key()))
                .toList();

        List<ExternalEvent> events = new ArrayList<>();
        for (EventProperties.IcsFeed feed : feeds) {
            events.addAll(toExternalEvents(download(feed.getUrl()), feed, from, to));
        }
        return events;
    }

    List<ExternalEvent> toExternalEvents(String ics, EventProperties.IcsFeed feed, LocalDate from, LocalDate to) {
        ZoneId zone = ZoneId.of(properties.getZone());
        return IcsParser.parse(ics, zone).stream()
                .filter(event -> !event.cancelled())
                .filter(event -> !event.startDate().isAfter(to) && !event.endDate().isBefore(from))
                .map(event -> new ExternalEvent(
                        // The date is part of the id so that a rescheduled event is a new event
                        // and the old date drops out of the feed.
                        (event.uid() != null ? event.uid() : event.summary()) + "|" + event.startDate(),
                        event.summary(),
                        event.description(),
                        feed.getCategory(),
                        event.location(),
                        null,
                        event.latitude(),
                        event.longitude(),
                        event.startDate(),
                        event.startTime(),
                        event.endDate(),
                        event.url(),
                        null))
                .toList();
    }

    private String download(String url) {
        try {
            byte[] body = restTemplate.getForObject(URI.create(url), byte[].class);
            if (body == null || body.length == 0) {
                throw new ExternalServiceException("Calendar feed was empty");
            }
            return new String(body, StandardCharsets.UTF_8);
        } catch (RestClientException | IllegalArgumentException e) {
            // Deliberately not e.getMessage(): it can contain the full request URL.
            throw new ExternalServiceException("Calendar feed request failed (" + e.getClass().getSimpleName() + ")");
        }
    }
}