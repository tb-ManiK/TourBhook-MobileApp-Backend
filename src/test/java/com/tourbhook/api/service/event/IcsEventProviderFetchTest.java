package com.tourbhook.api.service.event;

import com.tourbhook.api.config.EventProperties;
import com.tourbhook.api.repository.exception.ExternalServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IcsEventProviderFetchTest {

    private static final String HOLIDAYS_URL = "https://calendar.example.org/ical/en.indian%23holiday%40group/public/basic.ics";
    private static final String OTHER_URL = "https://tourism.example.org/udaipur.ics";
    private static final SyncArea UDAIPUR = new SyncArea("Udaipur", 24.58, 73.71);
    private static final LocalDate FROM = LocalDate.of(2026, 11, 1);
    private static final LocalDate TO = LocalDate.of(2026, 11, 30);

    @Mock private RestTemplate restTemplate;

    private final EventProperties properties = new EventProperties();
    private IcsEventProvider provider;

    @BeforeEach
    void setUp() {
        provider = new IcsEventProvider(properties, restTemplate);
    }

    private void addFeed(String city, String url) {
        EventProperties.IcsFeed feed = new EventProperties.IcsFeed();
        feed.setCity(city);
        feed.setUrl(url);
        properties.getIcsFeeds().add(feed);
    }

    private byte[] calendar(String summary) {
        return ("BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:" + summary.hashCode() + "\nDTSTART;VALUE=DATE:20261112\nSUMMARY:"
                + summary + "\nEND:VEVENT\nEND:VCALENDAR\n").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void fetch_readsTheFeedAsUtf8_andRequestsTheUrlExactlyAsConfigured() {
        addFeed("udaipur", HOLIDAYS_URL);   // the city matches case-insensitively
        when(restTemplate.getForObject(URI.create(HOLIDAYS_URL), byte[].class)).thenReturn(calendar("Holi \u2013 \u0930\u0902\u0917\u094B\u0902 \u0915\u093E \u0924\u094D\u092F\u094B\u0939\u093E\u0930"));

        List<ExternalEvent> events = provider.fetch(UDAIPUR, FROM, TO);

        assertThat(events).extracting(ExternalEvent::title)
                .containsExactly("Holi \u2013 \u0930\u0902\u0917\u094B\u0902 \u0915\u093E \u0924\u094D\u092F\u094B\u0939\u093E\u0930");
        // %23 and %40 must reach the server untouched (a String URL would have been encoded a second time)
        verify(restTemplate).getForObject(URI.create(HOLIDAYS_URL), byte[].class);
    }

    @Test
    void fetch_combinesTheFeedsOfTheCity() {
        addFeed("Udaipur", HOLIDAYS_URL);
        addFeed("Udaipur", OTHER_URL);
        when(restTemplate.getForObject(URI.create(HOLIDAYS_URL), byte[].class)).thenReturn(calendar("Festival A"));
        when(restTemplate.getForObject(URI.create(OTHER_URL), byte[].class)).thenReturn(calendar("Festival B"));

        assertThat(provider.fetch(UDAIPUR, FROM, TO)).extracting(ExternalEvent::title)
                .containsExactlyInAnyOrder("Festival A", "Festival B");
    }

    @Test
    void fetch_ifAnyFeedOfTheCityFails_theWholeAreaFailsInsteadOfReturningAPartialAnswer() {
        addFeed("Udaipur", HOLIDAYS_URL);
        addFeed("Udaipur", OTHER_URL);
        when(restTemplate.getForObject(URI.create(HOLIDAYS_URL), byte[].class)).thenReturn(calendar("Festival A"));
        when(restTemplate.getForObject(URI.create(OTHER_URL), byte[].class)).thenThrow(new RestClientException("timeout"));

        assertThatThrownBy(() -> provider.fetch(UDAIPUR, FROM, TO)).isInstanceOf(ExternalServiceException.class);
    }

    @Test
    void fetch_anEmptyDownloadIsAFailure_andTheErrorNeverRevealsTheUrl() {
        addFeed("Udaipur", HOLIDAYS_URL);
        when(restTemplate.getForObject(URI.create(HOLIDAYS_URL), byte[].class)).thenReturn(new byte[0]);

        assertThatThrownBy(() -> provider.fetch(UDAIPUR, FROM, TO))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessageNotContaining("calendar.example.org");
    }

    @Test
    void fetch_aTransportErrorBecomesAnExternalServiceFailureWithoutTheUrl() {
        addFeed("Udaipur", HOLIDAYS_URL);
        when(restTemplate.getForObject(URI.create(HOLIDAYS_URL), byte[].class))
                .thenThrow(new RestClientException("I/O error on GET request for \"" + HOLIDAYS_URL + "\""));

        assertThatThrownBy(() -> provider.fetch(UDAIPUR, FROM, TO))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessageNotContaining("calendar.example.org");
    }

    @Test
    void fetch_aMalformedConfiguredUrlIsAFailureNotACrash() {
        addFeed("Udaipur", "not a url");

        assertThatThrownBy(() -> provider.fetch(UDAIPUR, FROM, TO)).isInstanceOf(ExternalServiceException.class);
    }
}