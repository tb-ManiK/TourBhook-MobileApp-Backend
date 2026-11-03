package com.tourbhook.api.service.image;

import com.tourbhook.api.entity.Place;
import com.tourbhook.api.entity.Trip;
import com.tourbhook.api.entity.TripPlace;
import com.tourbhook.api.repository.PlaceRepository;
import com.tourbhook.api.repository.TripPlaceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ImageUrlResolver {

    private static final long CITY_CACHE_TTL_MS = 10 * 60 * 1000L;
    private static final int CITY_CACHE_LIMIT = 500;

    private final PlaceRepository placeRepository;
    private final TripPlaceRepository tripPlaceRepository;
    private final String baseUrl;

    private final Map<String, CityChoice> cityChoices = new ConcurrentHashMap<>();

    private record CityChoice(String placeId, long expiresAt) {
    }

    public ImageUrlResolver(
            PlaceRepository placeRepository,
            TripPlaceRepository tripPlaceRepository,
            @Value("${app.base-url:}") String baseUrl
    ) {
        this.placeRepository = placeRepository;
        this.tripPlaceRepository = tripPlaceRepository;
        this.baseUrl = ImageUrlRules.normalizeBase(baseUrl);
    }

    public String forPlace(Place place) {
        if (place == null) {
            return defaultImage();
        }
        if (ImageUrlRules.isUsable(place.getImageUrl())) {
            return place.getImageUrl().trim();
        }
        if (canServePhoto(place)) {
            return ImageUrlRules.placeImageUrl(baseUrl, place.getId());
        }
        return defaultImage();
    }

    public String forTrip(Trip trip) {
        if (trip == null) {
            return defaultImage();
        }
        if (ImageUrlRules.isUsable(trip.getImageUrl())) {
            return trip.getImageUrl().trim();
        }

        List<TripPlace> tripPlaces = trip.getId() == null ? List.of() : tripPlaceRepository.findByTrip(trip);
        for (TripPlace tripPlace : tripPlaces) {
            Place place = tripPlace.getPlace();
            if (place != null && canServePhoto(place)) {
                return ImageUrlRules.placeImageUrl(baseUrl, place.getId());
            }
        }

        String cityPlaceId = bestPlaceIdForCity(trip.getCity());
        return cityPlaceId != null
                ? ImageUrlRules.placeImageUrl(baseUrl, cityPlaceId)
                : defaultImage();
    }

    public String defaultImage() {
        return ImageUrlRules.defaultImageUrl(baseUrl);
    }

    private boolean canServePhoto(Place place) {
        return place.getId() != null
                && place.getGooglePlaceId() != null
                && !place.getGooglePlaceId().isBlank();
    }

    private String bestPlaceIdForCity(String city) {
        if (city == null || city.isBlank()) {
            return null;
        }
        String key = city.trim().toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();

        CityChoice cached = cityChoices.get(key);
        if (cached != null && cached.expiresAt() > now) {
            return cached.placeId();
        }

        List<Place> candidates = placeRepository.findCityHeroCandidates(city.trim(), PageRequest.of(0, 1));
        String placeId = candidates.isEmpty() ? null : candidates.get(0).getId();

        if (cityChoices.size() >= CITY_CACHE_LIMIT) {
            cityChoices.clear();
        }
        cityChoices.put(key, new CityChoice(placeId, now + CITY_CACHE_TTL_MS));
        return placeId;
    }
}