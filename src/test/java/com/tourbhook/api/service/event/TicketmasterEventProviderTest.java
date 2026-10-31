package com.tourbhook.api.service.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourbhook.api.config.EventProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class TicketmasterEventProviderTest {

    private static final String RESPONSE = """
            {
              "_embedded": { "events": [
                {
                  "id": "vv1",
                  "name": "Indie Night",
                  "url": "https://www.ticketmaster.com/event/vv1",
                  "info": "Doors open at 6.",
                  "images": [
                    { "url": "https://img/small.jpg", "width": 100 },
                    { "url": "https://img/large.jpg", "width": 1024 },
                    { "url": "https://img/medium.jpg", "width": 640 }
                  ],
                  "dates": {
                    "start": { "localDate": "2026-11-12", "localTime": "19:30:00" },
                    "end": { "localDate": "2026-11-14" },
                    "status": { "code": "onsale" }
                  },
                  "classifications": [ { "segment": { "name": "Music" } } ],
                  "_embedded": { "venues": [ {
                    "name": "Phoenix Arena",
                    "address": { "line1": "1 Main Road" },
                    "location": { "latitude": "13.0827", "longitude": "80.2707" }
                  } ] }
                },
                {
                  "id": "vv2", "name": "Called Off",
                  "dates": { "start": { "localDate": "2026-11-13" }, "status": { "code": "cancelled" } }
                },
                {
                  "id": "vv3", "name": "Postponed Gig",
                  "dates": { "start": { "localDate": "2026-11-13" }, "status": { "code": "postponed" } }
                },
                {
                  "id": "vv4", "name": "No Date Yet",
                  "dates": { "start": { "noSpecificTime": true } }
                },
                {
                  "id": "vv5", "name": "Bare Minimum",
                  "dates": { "start": { "localDate": "2026-11-15" } },
                  "_embedded": { "venues": [ { "name": "Somewhere", "location": { "latitude": "n/a", "longitude": "n/a" } } ] }
                }
              ] },
              "page": { "totalPages": 1 }
            }
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final EventProperties properties = new EventProperties();
    private final TicketmasterEventProvider provider = new TicketmasterEventProvider(properties, new RestTemplateBuilder());

    private List<ExternalEvent> parse(String json) throws Exception {
        JsonNode node = mapper.readTree(json);
        return provider.parse(node);
    }

    @Test
    void parse_mapsEveryFieldOfAnEvent() throws Exception {
        ExternalEvent event = parse(RESPONSE).get(0);

        assertThat(event.externalId()).isEqualTo("vv1");
        assertThat(event.title()).isEqualTo("Indie Night");
        assertThat(event.description()).isEqualTo("Doors open at 6.");
        assertThat(event.category()).isEqualTo("Music");
        assertThat(event.venueName()).isEqualTo("Phoenix Arena");
        assertThat(event.address()).isEqualTo("1 Main Road");
        assertThat(event.latitude()).isEqualTo(13.0827);
        assertThat(event.longitude()).isEqualTo(80.2707);
        assertThat(event.startDate()).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(event.startTime()).isEqualTo(LocalTime.of(19, 30));
        assertThat(event.endDate()).isEqualTo(LocalDate.of(2026, 11, 14));
        assertThat(event.url()).isEqualTo("https://www.ticketmaster.com/event/vv1");
    }

    @Test
    void parse_picksTheWidestImage() throws Exception {
        assertThat(parse(RESPONSE).get(0).imageUrl()).isEqualTo("https://img/large.jpg");
    }

    @Test
    void parse_dropsCancelledPostponedAndUndatedEvents() throws Exception {
        assertThat(parse(RESPONSE)).extracting(ExternalEvent::externalId).containsExactly("vv1", "vv5");
    }

    @Test
    void parse_toleratesMissingOrMalformedOptionalFields() throws Exception {
        ExternalEvent bare = parse(RESPONSE).get(1);

        assertThat(bare.venueName()).isEqualTo("Somewhere");
        assertThat(bare.latitude()).isNull();   // "n/a" is not a coordinate
        assertThat(bare.longitude()).isNull();
        assertThat(bare.category()).isNull();
        assertThat(bare.startTime()).isNull();
        assertThat(bare.endDate()).isNull();
        assertThat(bare.imageUrl()).isNull();
    }

    @Test
    void parse_aResponseWithNoEvents_isEmpty() throws Exception {
        assertThat(parse("{ \"page\": { \"totalElements\": 0 } }")).isEmpty();
    }

    @Test
    void isEnabled_needsBothTheSwitchAndAnApiKey() {
        assertThat(provider.isEnabled()).isFalse();

        properties.getTicketmaster().setEnabled(true);
        assertThat(provider.isEnabled()).isFalse();   // no key yet

        properties.getTicketmaster().setApiKey("  ");
        assertThat(provider.isEnabled()).isFalse();

        properties.getTicketmaster().setApiKey("key");
        assertThat(provider.isEnabled()).isTrue();
    }
}