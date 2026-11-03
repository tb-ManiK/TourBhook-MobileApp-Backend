package com.tourbhook.api.service.image;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Slf4j
@Component
public class GooglePhotoClient {

    private static final String BASE = "https://places.googleapis.com/v1";
    private static final Pattern PLACE_ID = Pattern.compile("^[A-Za-z0-9_-]{10,200}$");
    private static final Pattern PHOTO_NAME = Pattern.compile("^places/[A-Za-z0-9_-]+/photos/[A-Za-z0-9_-]+$");
    private static final int MAX_PHOTOS_CONSIDERED = 10;

    private final RestTemplate restTemplate;
    private final String apiKey;

    public GooglePhotoClient(
            RestTemplateBuilder builder,
            @Value("${google.maps.api-key:}") String apiKey
    ) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(8))
                .build();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public Optional<String> fetchPhotoUri(String googlePlaceId, int maxWidthPx) {
        if (apiKey.isEmpty() || googlePlaceId == null || !PLACE_ID.matcher(googlePlaceId).matches()) {
            return Optional.empty();
        }
        try {
            Optional<String> photoName = firstPhotoName(googlePlaceId);
            if (photoName.isEmpty()) {
                return Optional.empty();
            }
            return photoUri(photoName.get(), maxWidthPx);
        } catch (RestClientResponseException e) {
            log.warn("Google place photo lookup failed: status={}", e.getStatusCode().value());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Google place photo lookup failed: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private Optional<String> firstPhotoName(String googlePlaceId) {
        URI uri = UriComponentsBuilder.fromHttpUrl(BASE)
                .pathSegment("places", googlePlaceId)
                .build()
                .encode()
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Goog-Api-Key", apiKey);
        headers.set("X-Goog-FieldMask", "photos");

        ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        Map<?, ?> body = response.getBody();
        if (body == null || !(body.get("photos") instanceof List<?> photos) || photos.isEmpty()) {
            return Optional.empty();
        }

        // Prefer a landscape photo, which fits a card or a trip header; otherwise take the first one.
        String firstName = null;
        int considered = 0;
        for (Object item : photos) {
            if (considered++ >= MAX_PHOTOS_CONSIDERED) {
                break;
            }
            if (!(item instanceof Map<?, ?> photo) || !(photo.get("name") instanceof String name)
                    || !PHOTO_NAME.matcher(name).matches()) {
                continue;
            }
            if (firstName == null) {
                firstName = name;
            }
            if (photo.get("widthPx") instanceof Number width
                    && photo.get("heightPx") instanceof Number height
                    && width.intValue() > height.intValue()) {
                return Optional.of(name);
            }
        }
        return Optional.ofNullable(firstName);
    }

    private Optional<String> photoUri(String photoName, int maxWidthPx) {
        URI uri = UriComponentsBuilder.fromHttpUrl(BASE + "/" + photoName + "/media")
                .queryParam("maxWidthPx", maxWidthPx)
                .queryParam("skipHttpRedirect", "true")
                .queryParam("key", apiKey)
                .build()
                .encode()
                .toUri();

        ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), Map.class);
        Map<?, ?> body = response.getBody();
        if (body != null && body.get("photoUri") instanceof String photoUri && photoUri.startsWith("https://")) {
            return Optional.of(photoUri);
        }
        return Optional.empty();
    }
}