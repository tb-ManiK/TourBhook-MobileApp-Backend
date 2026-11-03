package com.tourbhook.api.service.image;

import com.tourbhook.api.entity.Place;
import com.tourbhook.api.repository.PlaceRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlacePhotoServiceTest {

    private final PlaceRepository repository = mock(PlaceRepository.class);
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private Optional<String> googleAnswer = Optional.of("https://lh3.googleusercontent.com/photo");

    private final GooglePhotoClient client = new GooglePhotoClient(new org.springframework.boot.web.client.RestTemplateBuilder(), "key") {
        @Override
        public Optional<String> fetchPhotoUri(String googlePlaceId, int maxWidthPx) {
            calls.incrementAndGet();
            return googleAnswer;
        }
    };

    private PlacePhotoService service(int perMinute) {
        return new PlacePhotoService(repository, client, 800, perMinute, now::get);
    }

    private void placeExists(String id, String googleId) {
        Place place = new Place();
        place.setId(id);
        place.setGooglePlaceId(googleId);
        when(repository.findById(id)).thenReturn(Optional.of(place));
    }

    @Test
    void returnsPhotoAndCachesIt() {
        placeExists("p1", "ChIJabcdefghij");
        PlacePhotoService service = service(10);

        assertThat(service.findPhotoUrl("p1")).contains("https://lh3.googleusercontent.com/photo");
        assertThat(service.findPhotoUrl("p1")).isPresent();
        assertThat(calls.get()).isEqualTo(1);

        now.addAndGet(PlacePhotoService.FOUND_TTL_MS + 1);
        service.findPhotoUrl("p1");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void missingPhotoIsCachedForAShortTime() {
        placeExists("p1", "ChIJabcdefghij");
        googleAnswer = Optional.empty();
        PlacePhotoService service = service(10);

        assertThat(service.findPhotoUrl("p1")).isEmpty();
        assertThat(service.findPhotoUrl("p1")).isEmpty();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void unknownPlaceOrNoGoogleIdNeverCallsGoogle() {
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        placeExists("p2", " ");
        PlacePhotoService service = service(10);

        assertThat(service.findPhotoUrl("nope")).isEmpty();
        assertThat(service.findPhotoUrl("p2")).isEmpty();
        assertThat(service.findPhotoUrl(null)).isEmpty();
        assertThat(service.findPhotoUrl("x".repeat(200))).isEmpty();
        assertThat(calls.get()).isZero();
    }

    @Test
    void stopsCallingGoogleWhenMinuteBudgetIsUsedAndRecoversNextMinute() {
        placeExists("a", "ChIJaaaaaaaaaa");
        placeExists("b", "ChIJbbbbbbbbbb");
        placeExists("c", "ChIJcccccccccc");
        PlacePhotoService service = service(2);

        assertThat(service.findPhotoUrl("a")).isPresent();
        assertThat(service.findPhotoUrl("b")).isPresent();
        assertThat(service.findPhotoUrl("c")).isEmpty();
        assertThat(calls.get()).isEqualTo(2);

        now.addAndGet(61_000L);
        assertThat(service.findPhotoUrl("c")).isPresent();
        assertThat(calls.get()).isEqualTo(3);
    }
}