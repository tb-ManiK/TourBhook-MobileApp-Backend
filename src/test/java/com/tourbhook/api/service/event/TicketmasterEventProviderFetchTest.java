package com.tourbhook.api.service.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourbhook.api.config.EventProperties;
import com.tourbhook.api.repository.exception.ExternalServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketmasterEventProviderFetchTest {

    private static final SyncArea CHENNAI = new SyncArea("Chennai", 13.0827, 80.2707);
    private static final LocalDate FROM = LocalDate.of(2026, 11, 1);
    private static final LocalDate TO = LocalDate.of(2026, 11, 30);

    @Mock private RestTemplate restTemplate;

    private final ObjectMapper mapper = new ObjectMapper();
    private final EventProperties properties = new EventProperties();
    private TicketmasterEventProvider provider;

    @BeforeEach
    void setUp() {
        properties.getTicketmaster().setEnabled(true);
        properties.getTicketmaster().setApiKey("super-secret-key");
        provider = new TicketmasterEventProvider(properties, restTemplate);
    }

    private JsonNode page(int totalPages, String... eventIds) throws Exception {
        StringBuilder events = new StringBuilder();
        for (String id : eventIds) {
            events.append(events.length() == 0 ? "" : ",")
                    .append("{\"id\":\"").append(id).append("\",\"name\":\"Event ").append(id)
                    .append("\",\"dates\":{\"start\":{\"localDate\":\"2026-11-12\"}}}");
        }
        return mapper.readTree("{\"_embedded\":{\"events\":[" + events + "]},\"page\":{\"totalPages\":" + totalPages + "}}");
    }

    @Test
    void fetch_asksForTheAreaAndTheWindow_inUtcAtIndianMidnights() throws Exception {
        when(restTemplate.getForObject(any(URI.class), eq(JsonNode.class))).thenReturn(page(1, "a"));

        provider.fetch(CHENNAI, FROM, TO);

        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).getForObject(uri.capture(), eq(JsonNode.class));
        String query = uri.getValue().toString();
        assertThat(query).contains("latlong=13.0827,80.2707").contains("radius=50").contains("unit=km")
                .contains("page=0").contains("sort=date,asc")
                // 1 Nov 00:00 IST is 31 Oct 18:30 UTC; the window is inclusive, so it ends at 1 Dec 00:00 IST
                .contains("startDateTime=2026-10-31T18:30:00Z").contains("endDateTime=2026-11-30T18:30:00Z");
    }

    @Test
    void fetch_readsEveryPageTheApiReports() throws Exception {
        when(restTemplate.getForObject(any(URI.class), eq(JsonNode.class)))
                .thenReturn(page(2, "a"), page(2, "b"));

        List<ExternalEvent> events = provider.fetch(CHENNAI, FROM, TO);

        assertThat(events).extracting(ExternalEvent::externalId).containsExactly("a", "b");
        verify(restTemplate, times(2)).getForObject(any(URI.class), eq(JsonNode.class));
    }

    @Test
    void fetch_stopsAtTheConfiguredPageLimit() throws Exception {
        properties.getTicketmaster().setMaxPages(2);
        when(restTemplate.getForObject(any(URI.class), eq(JsonNode.class)))
                .thenReturn(page(10, "a"), page(10, "b"));

        assertThat(provider.fetch(CHENNAI, FROM, TO)).hasSize(2);
        verify(restTemplate, times(2)).getForObject(any(URI.class), eq(JsonNode.class));
    }

    @Test
    void fetch_anHttpErrorBecomesAnExternalFailure_andTheApiKeyNeverLeaks() {
        when(restTemplate.getForObject(any(URI.class), eq(JsonNode.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> provider.fetch(CHENNAI, FROM, TO))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessageContaining("401")
                .hasMessageNotContaining("super-secret-key");
    }

    @Test
    void fetch_aTransportErrorOrEmptyBodyBecomesAnExternalFailure() {
        when(restTemplate.getForObject(any(URI.class), eq(JsonNode.class)))
                .thenThrow(new RestClientException("I/O error on GET request for \"https://x?apikey=super-secret-key\""))
                .thenReturn(null);

        assertThatThrownBy(() -> provider.fetch(CHENNAI, FROM, TO))
                .isInstanceOf(ExternalServiceException.class).hasMessageNotContaining("super-secret-key");
        assertThatThrownBy(() -> provider.fetch(CHENNAI, FROM, TO)).isInstanceOf(ExternalServiceException.class);
    }

    @Test
    void fetch_ifALaterPageFails_noPartialResultIsReturned() throws Exception {
        when(restTemplate.getForObject(any(URI.class), eq(JsonNode.class)))
                .thenReturn(page(2, "a"))
                .thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> provider.fetch(CHENNAI, FROM, TO)).isInstanceOf(ExternalServiceException.class);
    }
}