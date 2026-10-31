package com.tourbhook.api.service.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.tourbhook.api.config.EventProperties;
import com.tourbhook.api.repository.exception.ExternalServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Component
public class TicketmasterEventProvider implements EventProvider {

    static final String SOURCE = "ticketmaster";

    private static final Set<String> SKIPPED_STATUSES = Set.of("cancelled", "canceled", "postponed");

    private final EventProperties properties;
    private final RestTemplate restTemplate;

    @Autowired
    public TicketmasterEventProvider(EventProperties properties, RestTemplateBuilder builder) {
        this(properties, builder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(20))
                .build());
    }

    TicketmasterEventProvider(EventProperties properties, RestTemplate restTemplate) {
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    @Override
    public String sourceName() {
        return SOURCE;
    }

    @Override
    public boolean isEnabled() {
        EventProperties.Ticketmaster config = properties.getTicketmaster();
        return config.isEnabled() && config.getApiKey() != null && !config.getApiKey().isBlank();
    }

    @Override
    public List<ExternalEvent> fetch(SyncArea area, LocalDate from, LocalDate to) {
        EventProperties.Ticketmaster config = properties.getTicketmaster();
        List<ExternalEvent> events = new ArrayList<>();

        for (int page = 0; page < config.getMaxPages(); page++) {
            JsonNode response = request(area, from, to, page);
            events.addAll(parse(response));

            int totalPages = response.path("page").path("totalPages").asInt(0);
            if (page + 1 >= totalPages) {
                break;
            }
            if (page + 1 == config.getMaxPages()) {
                log.warn("Ticketmaster has {} pages of events around {}; only the first {} were fetched. "
                        + "Raise events.ticketmaster.max-pages if events are missing.", totalPages, area.city(), config.getMaxPages());
            }
        }
        return events;
    }

    private JsonNode request(SyncArea area, LocalDate from, LocalDate to, int page) {
        EventProperties.Ticketmaster config = properties.getTicketmaster();
        ZoneId zone = ZoneId.of(properties.getZone());

        URI uri = UriComponentsBuilder.fromHttpUrl(config.getBaseUrl())
                .queryParam("apikey", config.getApiKey())
                .queryParam("latlong", area.latitude() + "," + area.longitude())
                .queryParam("radius", config.getRadiusKm())
                .queryParam("unit", "km")
                .queryParam("startDateTime", DateTimeFormatter.ISO_INSTANT.format(from.atStartOfDay(zone).toInstant()))
                .queryParam("endDateTime", DateTimeFormatter.ISO_INSTANT.format(to.plusDays(1).atStartOfDay(zone).toInstant()))
                .queryParam("size", config.getPageSize())
                .queryParam("page", page)
                .queryParam("sort", "date,asc")
                .build().encode().toUri();
        try {
            JsonNode body = restTemplate.getForObject(uri, JsonNode.class);
            if (body == null) {
                throw new ExternalServiceException("Ticketmaster returned an empty response");
            }
            return body;
        } catch (RestClientResponseException e) {
            throw new ExternalServiceException("Ticketmaster responded with HTTP " + e.getRawStatusCode());
        } catch (RestClientException e) {
            throw new ExternalServiceException("Ticketmaster request failed (" + e.getClass().getSimpleName() + ")");
        }
    }

    List<ExternalEvent> parse(JsonNode response) {
        List<ExternalEvent> events = new ArrayList<>();
        for (JsonNode node : response.path("_embedded").path("events")) {
            map(node).ifPresent(events::add);
        }
        return events;
    }

    private Optional<ExternalEvent> map(JsonNode node) {
        String id = text(node.path("id"));
        String title = text(node.path("name"));
        JsonNode dates = node.path("dates");
        Optional<LocalDate> start = date(dates.path("start").path("localDate"));
        if (id == null || title == null || start.isEmpty()) {
            return Optional.empty();
        }

        String status = text(dates.path("status").path("code"));
        if (status != null && SKIPPED_STATUSES.contains(status.toLowerCase())) {
            return Optional.empty();
        }

        JsonNode venue = node.path("_embedded").path("venues").path(0);
        JsonNode classification = node.path("classifications").path(0);

        return Optional.of(new ExternalEvent(
                id,
                title,
                firstText(node.path("info"), node.path("pleaseNote")),
                text(classification.path("segment").path("name")),
                text(venue.path("name")),
                text(venue.path("address").path("line1")),
                number(venue.path("location").path("latitude")),
                number(venue.path("location").path("longitude")),
                start.get(),
                time(dates.path("start").path("localTime")),
                date(dates.path("end").path("localDate")).orElse(null),
                text(node.path("url")),
                widestImage(node.path("images"))));
    }

    /** Ticketmaster lists several renditions of each picture; the widest one looks best in a feed. */
    private String widestImage(JsonNode images) {
        String best = null;
        int bestWidth = -1;
        for (JsonNode image : images) {
            String url = text(image.path("url"));
            int width = image.path("width").asInt(0);
            if (url != null && width > bestWidth) {
                best = url;
                bestWidth = width;
            }
        }
        return best;
    }

    private String firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            String text = text(node);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText().trim();
        return text.isEmpty() ? null : text;
    }

    /** Ticketmaster sends coordinates as strings. */
    private Double number(JsonNode node) {
        String text = text(node);
        if (text == null) {
            return null;
        }
        try {
            return Double.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Optional<LocalDate> date(JsonNode node) {
        String text = text(node);
        if (text == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(text));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private LocalTime time(JsonNode node) {
        String text = text(node);
        if (text == null) {
            return null;
        }
        try {
            return LocalTime.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}