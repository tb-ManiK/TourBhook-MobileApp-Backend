package com.tourbhook.api.service.image;

import com.tourbhook.api.entity.Place;
import com.tourbhook.api.repository.PlaceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

@Service
public class PlacePhotoService {

    static final long FOUND_TTL_MS = 60 * 60 * 1000L;
    static final long MISSING_TTL_MS = 10 * 60 * 1000L;
    static final int CACHE_LIMIT = 5000;
    private static final long WINDOW_MS = 60 * 1000L;
    private static final int MAX_PLACE_ID_LENGTH = 64;

    private final PlaceRepository placeRepository;
    private final GooglePhotoClient googlePhotoClient;
    private final int photoWidthPx;
    private final int maxLookupsPerMinute;
    private final LongSupplier clock;

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Object windowLock = new Object();
    private long windowStart;
    private int windowCount;

    private record Entry(String url, long expiresAt) {
    }

    @Autowired
    public PlacePhotoService(
            PlaceRepository placeRepository,
            GooglePhotoClient googlePhotoClient,
            @Value("${images.photo-width-px:800}") int photoWidthPx,
            @Value("${images.lookups-per-minute:120}") int maxLookupsPerMinute
    ) {
        this(placeRepository, googlePhotoClient, photoWidthPx, maxLookupsPerMinute, System::currentTimeMillis);
    }

    PlacePhotoService(
            PlaceRepository placeRepository,
            GooglePhotoClient googlePhotoClient,
            int photoWidthPx,
            int maxLookupsPerMinute,
            LongSupplier clock
    ) {
        this.placeRepository = placeRepository;
        this.googlePhotoClient = googlePhotoClient;
        this.photoWidthPx = Math.min(Math.max(photoWidthPx, 100), 1600);
        this.maxLookupsPerMinute = Math.max(maxLookupsPerMinute, 1);
        this.clock = clock;
    }

    public Optional<String> findPhotoUrl(String placeId) {
        if (placeId == null || placeId.isBlank() || placeId.length() > MAX_PLACE_ID_LENGTH) {
            return Optional.empty();
        }

        long now = clock.getAsLong();
        Entry cached = cache.get(placeId);
        if (cached != null && cached.expiresAt() > now) {
            return Optional.ofNullable(cached.url());
        }

        String googlePlaceId = placeRepository.findById(placeId)
                .map(Place::getGooglePlaceId)
                .filter(id -> !id.isBlank())
                .orElse(null);
        if (googlePlaceId == null) {
            remember(placeId, null, now, MISSING_TTL_MS);
            return Optional.empty();
        }

        if (!tryTakeLookup(now)) {
            return Optional.empty();
        }

        Optional<String> url = googlePhotoClient.fetchPhotoUri(googlePlaceId, photoWidthPx);
        remember(placeId, url.orElse(null), now, url.isPresent() ? FOUND_TTL_MS : MISSING_TTL_MS);
        return url;
    }

    private boolean tryTakeLookup(long now) {
        synchronized (windowLock) {
            if (now - windowStart >= WINDOW_MS) {
                windowStart = now;
                windowCount = 0;
            }
            if (windowCount >= maxLookupsPerMinute) {
                return false;
            }
            windowCount++;
            return true;
        }
    }

    private void remember(String placeId, String url, long now, long ttlMs) {
        if (cache.size() >= CACHE_LIMIT) {
            cache.clear();
        }
        cache.put(placeId, new Entry(url, now + ttlMs));
    }
}