package com.tourbhook.api.service.impl;

import com.tourbhook.api.dto.place.AddPlaceToTripRequest;
import com.tourbhook.api.dto.place.PlaceResponse;
import com.tourbhook.api.entity.Place;
import com.tourbhook.api.repository.PlaceRepository;
import com.tourbhook.api.repository.exception.BadRequestException;
import com.tourbhook.api.repository.exception.ResourceNotFoundException;
import com.tourbhook.api.service.PlaceService;
import com.tourbhook.api.service.TripService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.function.Function;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import java.util.Comparator;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PlaceServiceImpl implements PlaceService {

    private static final int MIN_DB_RESULTS = 10;
    private static final int TARGET_RESULTS = 30;
    private static final int NEARBY_RADIUS_METERS = 12000;
    private static final int SEARCH_MIN_QUERY_LENGTH = 2;
    private static final int SEARCH_MIN_GOOGLE_QUERY_LENGTH = 3;
    private static final int SEARCH_MAX_QUERY_LENGTH = 100;
    private static final int SEARCH_MAX_RESULTS = 20;
    private static final int SEARCH_MIN_LOCAL_RESULTS = 10;
    private static final long GOOGLE_SEARCH_REPEAT_MS = 10 * 60 * 1000L;
    private static final int GOOGLE_SEARCH_CACHE_LIMIT = 2000;
    private static final int CITY_CENTER_CACHE_LIMIT = 500;

    private final PlaceRepository placeRepository;
    private final TripService tripService;
    private final GoogleMapsClient googleMapsClient;
    private final PlaceRankingService placeRankingService;

    @Value("${place-search.city-radius-km:40}")
    private double cityRadiusKm = 40;

    private final Map<String, double[]> cityCenters = new ConcurrentHashMap<>();
    private final Map<String, Long> recentGoogleSearches = new ConcurrentHashMap<>();

    @Override
    public List<PlaceResponse> getPlaces(String city) {
        if (city == null || city.isBlank()) {
            return toRankedResponses(placeRepository.findAll());
        }

        String normalizedCity = city.trim();
        List<Place> dbPlaces = placeRepository.findByCityIgnoreCase(normalizedCity);

        if (dbPlaces.size() < MIN_DB_RESULTS) {
            fetchAndSavePlacesFromGoogle(normalizedCity, false, TARGET_RESULTS);
            dbPlaces = placeRepository.findByCityIgnoreCase(normalizedCity);
        }

        return toRankedResponses(dbPlaces);
    }

    @Override
    public List<PlaceResponse> getSuggestedPlaces(String city) {
        if (city == null || city.isBlank()) {
            return toRankedResponses(placeRepository.findAll().stream()
                    .filter(Place::isSuggested)
                    .toList());
        }

        String normalizedCity = city.trim();
        List<Place> suggestedPlaces = placeRepository.findByCityIgnoreCaseAndSuggestedTrue(normalizedCity);

        if (suggestedPlaces.size() < MIN_DB_RESULTS) {
            fetchAndSavePlacesFromGoogle(normalizedCity, true, TARGET_RESULTS);
            suggestedPlaces = placeRepository.findByCityIgnoreCaseAndSuggestedTrue(normalizedCity);
        }

        return toRankedResponses(suggestedPlaces);
    }

    @Override
    public List<PlaceResponse> search(String q) {
        if (q == null || q.isBlank()) {
            return List.of();
        }

        String keyword = q.trim();

        List<Place> dbResults = placeRepository.searchByKeyword(keyword);
        if (!dbResults.isEmpty()) {
            return toRankedResponses(dbResults);
        }

        Map<String, Object> googleResponse = googleMapsClient.textSearch(keyword);
        validateGoogleResponse(keyword, googleResponse);

        List<Map<String, Object>> results = castList(googleResponse.get("results"));
        if (results == null || results.isEmpty()) {
            return List.of();
        }

        String inferredCity = inferCityFromQuery(keyword);
        List<Place> savedPlaces = new ArrayList<>();

        for (Map<String, Object> item : results) {
            try {
                Place saved = saveOrUpdateGooglePlace(item, inferredCity, false, keyword);
                if (saved != null) {
                    savedPlaces.add(saved);
                }
            } catch (Exception e) {
                log.error("Failed to save Google place for query '{}': {}", keyword, e.getMessage(), e);
            }
        }

        List<Place> deduplicated = new ArrayList<>(
                savedPlaces.stream()
                        .filter(Objects::nonNull)
                        .collect(java.util.stream.Collectors.toMap(
                                Place::getId,
                                p -> p,
                                (a, b) -> a,
                                LinkedHashMap::new
                        ))
                        .values()
        );
        return toRankedResponses(deduplicated);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<PlaceResponse> search(String q, String city, String tripId) {
        String scopedCity = resolveSearchCity(city, tripId);
        if (scopedCity == null) {
            return search(q);
        }

        String keyword = q == null ? "" : q.trim();
        if (keyword.length() < SEARCH_MIN_QUERY_LENGTH) {
            return List.of();
        }
        if (keyword.length() > SEARCH_MAX_QUERY_LENGTH) {
            keyword = keyword.substring(0, SEARCH_MAX_QUERY_LENGTH).trim();
        }

        List<String> tokens = PlaceSearchSupport.significantTokens(keyword, scopedCity);
        double[] center = findCityCenter(scopedCity);

        Map<String, Place> found = new LinkedHashMap<>();
        for (Place place : localCityCandidates(scopedCity, center)) {
            if (PlaceSearchSupport.isAreaOnly(place.getCategory())) {
                continue;
            }
            if (!isInCity(place.getLatitude(), place.getLongitude(), center, scopedCity, place.getCity(), place.getAddress())) {
                continue;
            }
            if (PlaceSearchSupport.matchesAllTokens(tokens,
                    place.getName(), place.getAddress(), place.getCategory(), place.getCity())) {
                found.putIfAbsent(place.getId(), place);
            }
        }

        if (found.size() < SEARCH_MIN_LOCAL_RESULTS && keyword.length() >= SEARCH_MIN_GOOGLE_QUERY_LENGTH) {
            addGoogleMatches(keyword, scopedCity, center, tokens, found);
        }

        List<Place> ordered = new ArrayList<>(found.values());
        ordered.sort(Comparator
                .comparingInt((Place p) -> PlaceSearchSupport.relevance(
                        tokens, p.getName(), p.getAddress(), p.getCategory()))
                .reversed()
                .thenComparing(Comparator
                        .comparingDouble((Place p) -> placeRankingService.weightFor(p))
                        .reversed())
                .thenComparing(Comparator
                        .comparingInt((Place p) -> p.getReviewsCount() == null ? 0 : p.getReviewsCount())
                        .reversed()));

        if (!ordered.isEmpty()) {
            Place best = ordered.get(0);
            int top = PlaceSearchSupport.relevance(tokens, best.getName(), best.getAddress(), best.getCategory());
            int minimum = top >= 2 ? 2 : 1;
            ordered.removeIf(p -> PlaceSearchSupport.relevance(
                    tokens, p.getName(), p.getAddress(), p.getCategory()) < minimum);
        }

        return ordered.stream()
                .limit(SEARCH_MAX_RESULTS)
                .map(this::mapPlace)
                .toList();
    }

    private String resolveSearchCity(String city, String tripId) {
        if (city != null && !city.isBlank()) {
            return city.trim();
        }
        if (tripId != null && !tripId.isBlank()) {
            String tripCity = tripService.getTripById(tripId.trim()).city();
            return tripCity == null || tripCity.isBlank() ? null : tripCity.trim();
        }
        return null;
    }

    /** Saved places that could belong to the city: same city name, or inside the city's bounding box. */
    private List<Place> localCityCandidates(String city, double[] center) {
        Map<String, Place> candidates = new LinkedHashMap<>();
        for (Place place : placeRepository.findByCityIgnoreCase(city)) {
            candidates.putIfAbsent(place.getId(), place);
        }
        if (center != null) {
            double latDelta = PlaceSearchSupport.latitudeDelta(cityRadiusKm);
            double lngDelta = PlaceSearchSupport.longitudeDelta(cityRadiusKm, center[0]);
            for (Place place : placeRepository.findWithinBounds(
                    center[0] - latDelta, center[0] + latDelta,
                    center[1] - lngDelta, center[1] + lngDelta)) {
                candidates.putIfAbsent(place.getId(), place);
            }
        }
        return new ArrayList<>(candidates.values());
    }

    private boolean isInCity(
            Double lat, Double lng, double[] center,
            String city, String storedCity, String address
    ) {
        return PlaceSearchSupport.belongsToCity(
                lat, lng,
                center == null ? null : center[0],
                center == null ? null : center[1],
                cityRadiusKm, city, storedCity, address);
    }

    private void addGoogleMatches(
            String keyword, String city, double[] center, List<String> tokens, Map<String, Place> found
    ) {
        String googleQuery = PlaceSearchSupport.buildGoogleQuery(keyword, city);
        String cacheKey = PlaceSearchSupport.normalize(city) + "|" + PlaceSearchSupport.normalize(googleQuery);

        if (askedGoogleRecently(cacheKey)) {
            return;
        }

        Map<String, Object> response;
        try {
            Integer radiusMeters = center == null ? null : (int) Math.round(cityRadiusKm * 1000);
            response = googleMapsClient.textSearch(
                    googleQuery,
                    center == null ? null : center[0],
                    center == null ? null : center[1],
                    radiusMeters);
            validateGoogleResponse(googleQuery, response);
        } catch (Exception e) {
            // Google being down or out of quota must not break search: saved places are still returned.
            log.warn("Google search skipped for '{}': {}", googleQuery, e.getMessage());
            return;
        }
        rememberGoogleSearch(cacheKey);

        List<Map<String, Object>> results = castList(response.get("results"));
        if (results == null) {
            return;
        }

        String sourceQuery = googleQuery.length() > 250 ? googleQuery.substring(0, 250) : googleQuery;
        for (Map<String, Object> item : results) {
            try {
                if (item.get("types") instanceof List<?> types && !types.isEmpty()
                        && PlaceSearchSupport.isAreaOnly(String.valueOf(types.get(0)))) {
                    continue;
                }
                Map<String, Object> geometry = castMap(item.get("geometry"));
                Map<String, Object> location = geometry != null ? castMap(geometry.get("location")) : null;
                Double lat = location != null ? toDoubleOrNull(location.get("lat")) : null;
                Double lng = location != null ? toDoubleOrNull(location.get("lng")) : null;

                // Google only treats the city as a hint, so results from other cities are dropped here.
                if (!isInCity(lat, lng, center, city, null, readString(item.get("formatted_address")))) {
                    continue;
                }

                String category = item.get("types") instanceof List<?> t && !t.isEmpty()
                        ? String.valueOf(t.get(0)).replace('_', ' ')
                        : null;
                if (PlaceSearchSupport.relevance(
                        tokens, readString(item.get("name")), readString(item.get("formatted_address")), category) == 0) {
                    continue;
                }



                Place saved = saveGooglePlaceOnce(item, city, sourceQuery);
                if (saved != null) {
                    found.putIfAbsent(saved.getId(), saved);
                }
            } catch (Exception e) {
                log.error("Failed to save Google place for query '{}': {}", googleQuery, e.getMessage(), e);
            }
        }
    }

    private Place saveGooglePlaceOnce(Map<String, Object> item, String city, String sourceQuery) {
        try {
            return saveOrUpdateGooglePlace(item, city, false, sourceQuery);
        } catch (DataIntegrityViolationException e) {
            String googlePlaceId = readString(item.get("place_id"));
            return googlePlaceId == null ? null : placeRepository.findByGooglePlaceId(googlePlaceId).orElse(null);
        }
    }

    private double[] findCityCenter(String city) {
        String key = PlaceSearchSupport.normalize(city);
        double[] cached = cityCenters.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            Map<String, Object> coordinates = googleMapsClient.geocodeCity(city);
            if (coordinates != null) {
                Double lat = toDoubleOrNull(coordinates.get("lat"));
                Double lng = toDoubleOrNull(coordinates.get("lng"));
                if (lat != null && lng != null) {
                    if (cityCenters.size() >= CITY_CENTER_CACHE_LIMIT) {
                        cityCenters.clear();
                    }
                    double[] center = new double[]{lat, lng};
                    cityCenters.put(key, center);
                    return center;
                }
            }
        } catch (Exception e) {
            log.warn("Could not find the centre of city '{}': {}", city, e.getMessage());
        }
        return null;
    }

    private boolean askedGoogleRecently(String key) {
        Long askedAt = recentGoogleSearches.get(key);
        return askedAt != null && System.currentTimeMillis() - askedAt < GOOGLE_SEARCH_REPEAT_MS;
    }

    private void rememberGoogleSearch(String key) {
        if (recentGoogleSearches.size() >= GOOGLE_SEARCH_CACHE_LIMIT) {
            recentGoogleSearches.clear();
        }
        recentGoogleSearches.put(key, System.currentTimeMillis());
    }

    @Override
    @Transactional(readOnly = true)
    public List<PlaceResponse> suggested() {
        return toRankedResponses(placeRepository.findAll().stream()
                .filter(Place::isSuggested)
                .toList());
    }

    @Override
    @Transactional(readOnly = true)
    public PlaceResponse getPlace(String id) {
        return placeRepository.findById(id)
                .map(this::mapPlace)
                .orElseThrow(() -> new ResourceNotFoundException("Place not found"));
    }

    @Override
    public void addToTrip(AddPlaceToTripRequest request) {
        tripService.addPlaceToTrip(request.trip_id(), request.place_id());
    }

    @Override
    public void removeFromTrip(AddPlaceToTripRequest request) {
        tripService.removePlaceFromTrip(request.trip_id(), request.place_id());
    }

    private void fetchAndSavePlacesFromGoogle(String city, boolean suggested, int targetLimit) {
        String normalizedCity = safeString(city, "Unknown");
        List<Map<String, Object>> mergedResults = fetchMergedPlaces(normalizedCity, suggested);

        if (mergedResults.isEmpty()) {
            log.warn("No Google places found for city={}", normalizedCity);
            return;
        }

        int count = 0;
        for (Map<String, Object> item : mergedResults) {
            if (targetLimit > 0 && count >= targetLimit) {
                break;
            }

            try {
                saveOrUpdateGooglePlace(item, normalizedCity, suggested, buildSourceQuery(normalizedCity, suggested));
                count++;
            } catch (Exception e) {
                log.error("Failed saving place for city '{}': {}", normalizedCity, e.getMessage(), e);
            }
        }

        log.info("Saved/updated {} places for city={}", count, normalizedCity);
    }

    private List<Map<String, Object>> fetchMergedPlaces(String city, boolean suggested) {
        Set<String> seenPlaceIds = new LinkedHashSet<>();
        List<Map<String, Object>> merged = new ArrayList<>();

        List<String> queries = suggested
                ? List.of(
                        "top sights in " + city,
                        "best places to visit in " + city,
                        "famous tourist attractions in " + city
                )
                : List.of(
                        "tourist attractions in " + city,
                        "places to visit in " + city,
                        "best attractions in " + city
                );

        Double lat = null;
        Double lng = null;

        try {
            Map<String, Object> coordinates = googleMapsClient.geocodeCity(city);
            if (coordinates != null) {
                lat = toDoubleOrNull(coordinates.get("lat"));
                lng = toDoubleOrNull(coordinates.get("lng"));
            }
        } catch (Exception e) {
            log.warn("Failed to geocode city '{}': {}", city, e.getMessage());
        }

        for (String query : queries) {
            try {
                Map<String, Object> response = googleMapsClient.textSearch(query);
                validateGoogleResponse(query, response);

                List<Map<String, Object>> results = castList(response.get("results"));
                addUniquePlaces(results, merged, seenPlaceIds);
            } catch (Exception e) {
                log.warn("Text search failed for query '{}': {}", query, e.getMessage());
            }
        }

        if (lat != null && lng != null) {
            List<String> nearbyTypes = List.of(
                    "tourist_attraction",
                    "hindu_temple",
                    "museum",
                    "park",
                    "amusement_park",
                    "art_gallery"
            );

            for (String type : nearbyTypes) {
                try {
                    Map<String, Object> nearbyResponse =
                            googleMapsClient.nearbySearch(lat, lng, NEARBY_RADIUS_METERS, type);

                    validateGoogleResponse("nearbySearch:" + type + ":" + city, nearbyResponse);

                    List<Map<String, Object>> results = castList(nearbyResponse.get("results"));
                    addUniquePlaces(results, merged, seenPlaceIds);
                } catch (Exception e) {
                    log.warn("Nearby search failed for city '{}', type '{}': {}", city, type, e.getMessage());
                }
            }
        }

        log.info("Merged Google places count for city '{}': {}", city, merged.size());
        return merged;
    }

    private void addUniquePlaces(
            List<Map<String, Object>> source,
            List<Map<String, Object>> target,
            Set<String> seenPlaceIds
    ) {
        if (source == null || source.isEmpty()) {
            return;
        }

        for (Map<String, Object> item : source) {
            if (item == null) {
                continue;
            }

            String placeId = readString(item.get("place_id"));
            if (placeId == null || placeId.isBlank()) {
                continue;
            }

            if (seenPlaceIds.add(placeId)) {
                target.add(item);
            }
        }
    }

    private Place saveOrUpdateGooglePlace(
            Map<String, Object> googlePlace,
            String fallbackCity,
            boolean suggested,
            String sourceQuery
    ) {
        String googlePlaceId = readString(googlePlace.get("place_id"));
        if (googlePlaceId == null || googlePlaceId.isBlank()) {
            throw new IllegalArgumentException("Google place_id missing");
        }

        String name = safeString(readString(googlePlace.get("name")), "Unknown Place");
        String address = readString(googlePlace.get("formatted_address"));
        String imageUrl = buildPhotoUrl(googlePlace.get("photos"));
        String city = safeString(fallbackCity, "Unknown");

        List<?> types = googlePlace.get("types") instanceof List<?> t ? t : List.of();
        String category = !types.isEmpty()
                ? String.valueOf(types.get(0)).replace("_", " ")
                : "Point of Interest";

        Map<String, Object> geometry = castMap(googlePlace.get("geometry"));
        Map<String, Object> location = geometry != null ? castMap(geometry.get("location")) : null;

        Double latitude = location != null ? toDoubleOrNull(location.get("lat")) : null;
        Double longitude = location != null ? toDoubleOrNull(location.get("lng")) : null;

        BigDecimal rating = toBigDecimalOrZero(googlePlace.get("rating"));
        Integer reviewsCount = toIntOrZero(googlePlace.get("user_ratings_total"));

        Place place = placeRepository.findByGooglePlaceId(googlePlaceId)
                .orElseGet(() -> placeRepository
                        .findFirstByNameIgnoreCaseAndCityIgnoreCase(name, city)
                        .filter(existing -> existing.getGooglePlaceId() == null
                                || existing.getGooglePlaceId().isBlank())
                        .orElseGet(Place::new));

        place.setGooglePlaceId(googlePlaceId);
        place.setName(name);
        place.setCity(city);
        place.setCategory(category);
        place.setAddress(address);
        place.setImageUrl(imageUrl);
        place.setLatitude(latitude);
        place.setLongitude(longitude);
        place.setTypicalDuration(defaultDurationByCategory(category));
        place.setRating(rating);
        place.setReviewsCount(reviewsCount);
        place.setVerified(true);
        place.setSuggested(place.isSuggested() || suggested);
        place.setSourceQuery(sourceQuery);

        return placeRepository.save(place);
    }

    private void validateGoogleResponse(String query, Map<String, Object> googleResponse) {
        if (googleResponse == null || googleResponse.isEmpty()) {
            throw new BadRequestException("Google Places API returned empty response");
        }

        String status = readString(googleResponse.get("status"));
        String errorMessage = readString(googleResponse.get("error_message"));

        if ("ZERO_RESULTS".equalsIgnoreCase(status)) {
            return;
        }

        if (status != null && !"OK".equalsIgnoreCase(status)) {
            throw new BadRequestException(
                    "Google Places API failed for query '" + query + "' with status: " + status +
                            (errorMessage != null ? " (" + errorMessage + ")" : "")
            );
        }
    }

    private List<PlaceResponse> toRankedResponses(List<Place> places) {
        List<Place> nonNull = places.stream().filter(Objects::nonNull).toList();
        return placeRankingService.rank(nonNull, Function.identity()).stream()
                .map(this::mapPlace)
                .toList();
    }

    private PlaceResponse mapPlace(Place place) {
        return new PlaceResponse(
                place.getId(),
                place.getName(),
                place.getCategory(),
                place.getImageUrl(),
                place.getTypicalDuration(),
                place.getLatitude(),
                place.getLongitude(),
                place.getRating(),
                place.getReviewsCount(),
                place.isVerified(),
                place.getAddress()

        );
    }

    private String buildPhotoUrl(Object photosObj) {
        if (!(photosObj instanceof List<?> photos) || photos.isEmpty()) {
            return "https://via.placeholder.com/400x300?text=No+Image";
        }

        Object firstPhotoObj = photos.get(0);
        if (!(firstPhotoObj instanceof Map<?, ?> firstPhoto)) {
            return "https://via.placeholder.com/400x300?text=No+Image";
        }

        Object refObj = firstPhoto.get("photo_reference");
        if (refObj == null) {
            return "https://via.placeholder.com/400x300?text=No+Image";
        }

        return "https://maps.googleapis.com/maps/api/place/photo?maxwidth=400&photo_reference="
                + refObj + "&key=" + googleMapsClient.getApiKey();
    }

    private String inferCityFromQuery(String query) {
        if (query == null || query.isBlank()) {
            return "Unknown";
        }

        String lower = query.toLowerCase(Locale.ROOT);
        if (lower.contains(" in ")) {
            return query.substring(lower.lastIndexOf(" in ") + 4).trim();
        }

        return "Unknown";
    }

    private String buildSourceQuery(String city, boolean suggested) {
        return suggested ? "top sights in " + city : "tourist attractions in " + city;
    }

    private String defaultDurationByCategory(String category) {
        if (category == null) {
            return "2-3 hours";
        }

        String c = category.toLowerCase(Locale.ROOT);

        if (c.contains("restaurant") || c.contains("cafe") || c.contains("food")) {
            return "1-2 hours";
        }
        if (c.contains("park") || c.contains("garden")) {
            return "2-3 hours";
        }
        if (c.contains("museum") || c.contains("art") || c.contains("gallery")) {
            return "2-3 hours";
        }
        if (c.contains("temple") || c.contains("mosque") || c.contains("church")) {
            return "1-2 hours";
        }

        return "2-3 hours";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object obj) {
        return (List<Map<String, Object>>) obj;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object obj) {
        return (Map<String, Object>) obj;
    }

    private String readString(Object obj) {
        return obj == null ? null : obj.toString();
    }

    private Double toDoubleOrNull(Object obj) {
        return obj instanceof Number n ? n.doubleValue() : null;
    }

    private BigDecimal toBigDecimalOrZero(Object obj) {
        return obj instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : BigDecimal.ZERO;
    }

    private Integer toIntOrZero(Object obj) {
        return obj instanceof Number n ? n.intValue() : 0;
    }

    private String safeString(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }
}