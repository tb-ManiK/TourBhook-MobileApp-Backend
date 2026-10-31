package com.tourbhook.api.service.impl;

import com.tourbhook.api.repository.exception.ExternalServiceException;
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
import org.springframework.http.MediaType;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class GoogleMapsClient {

    private final RestTemplate restTemplate;

    @Value("${google.maps.api-key:}")
    private String apiKey;

    public GoogleMapsClient(RestTemplateBuilder builder) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(15))
                .build();

        log.info("GoogleMapsClient initialized with timeout-enabled RestTemplate");
    }

    public String getApiKey() {
        return apiKey;
    }

    public Map<String, Object> textSearch(String query) {
        return textSearch(query, null, null, null);
    }

    public Map<String, Object> textSearch(String query, Double biasLat, Double biasLng, Integer biasRadiusMeters) {

        if (query == null || query.isBlank()) {
            return Collections.emptyMap();
        }

        if (apiKey == null || apiKey.isBlank()) {
            log.error("Google Maps API key is not configured");
            return Collections.emptyMap();
        }

        String url = "https://places.googleapis.com/v1/places:searchText";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Goog-Api-Key", apiKey);
        headers.set(
                "X-Goog-FieldMask",
                "places.id,places.displayName,places.formattedAddress,"
                        + "places.location,places.types,places.primaryType,"
                        + "places.rating,places.userRatingCount"
        );

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("textQuery", query);
        requestBody.put("maxResultCount", 20);

        if (biasLat != null && biasLng != null && biasRadiusMeters != null
                && biasLat >= -90 && biasLat <= 90
                && biasLng >= -180 && biasLng <= 180
                && biasRadiusMeters > 0) {
            Map<String, Object> biasCenter = new HashMap<>();
            biasCenter.put("latitude", biasLat);
            biasCenter.put("longitude", biasLng);

            Map<String, Object> biasCircle = new HashMap<>();
            biasCircle.put("center", biasCenter);
            biasCircle.put("radius", (double) Math.min(biasRadiusMeters, 50000));

            Map<String, Object> locationBias = new HashMap<>();
            locationBias.put("circle", biasCircle);
            requestBody.put("locationBias", locationBias);
        }

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(requestBody, headers),
                    Map.class
            );

            Map<String, Object> body = response.getBody();
            List<Map<String, Object>> normalizedResults = new java.util.ArrayList<>();

            if (body != null && body.get("places") instanceof List<?> places) {
                for (Object item : places) {
                    if (!(item instanceof Map<?, ?> place)) {
                        continue;
                    }

                    Map<String, Object> result = new HashMap<>();
                    result.put("place_id", place.get("id"));
                    result.put("formatted_address", place.get("formattedAddress"));
                    result.put("rating", place.get("rating"));
                    result.put("user_ratings_total", place.get("userRatingCount"));

                    if (place.get("displayName") instanceof Map<?, ?> displayName) {
                        result.put("name", displayName.get("text"));
                    }

                    Object types = place.get("types");
                    result.put("types", types != null ? types : List.of());

                    if (place.get("location") instanceof Map<?, ?> location) {
                        Map<String, Object> legacyLocation = new HashMap<>();
                        legacyLocation.put("lat", location.get("latitude"));
                        legacyLocation.put("lng", location.get("longitude"));

                        Map<String, Object> geometry = new HashMap<>();
                        geometry.put("location", legacyLocation);
                        result.put("geometry", geometry);
                    }

                    normalizedResults.add(result);
                }
            }

            Map<String, Object> normalizedResponse = new HashMap<>();
            normalizedResponse.put(
                    "status",
                    normalizedResults.isEmpty() ? "ZERO_RESULTS" : "OK"
            );
            normalizedResponse.put("results", normalizedResults);

            log.info("Google Places API (New) returned {} results for text search",
                    normalizedResults.size());

            return normalizedResponse;

        } catch (RestClientResponseException e) {
            log.error("Google Places text search failed: status={}, body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            return Collections.emptyMap();
        } catch (Exception e) {
            log.error("Google Places text search failed: {}", e.getMessage(), e);
            return Collections.emptyMap();
        }
    }


//    public Map<String, Object> textSearch(String query) {
//        String url = UriComponentsBuilder
//                .fromHttpUrl("https://maps.googleapis.com/maps/api/place/textsearch/json")
//                .queryParam("query", query)
//                .queryParam("key", apiKey)
//                .toUriString();
//
//        log.info("Google Text Search URL: {}", safeUrl(url));
//
//        try {
//            ResponseEntity<Map> response = restTemplate.exchange(
//                    url,
//                    HttpMethod.GET,
//                    new HttpEntity<>(new HttpHeaders()),
//                    Map.class
//            );
//
//            Map<String, Object> body = response.getBody();
//            log.info("Google Text Search response: {}", body);
//            return body != null ? body : Collections.emptyMap();
//
//        } catch (RestClientResponseException e) {
//            log.error("Google Text Search API error: status={}, body={}",
//                    e.getRawStatusCode(), e.getResponseBodyAsString(), e);
//            return Collections.emptyMap();
//        } catch (Exception e) {
//            log.error("Google Text Search unexpected error: {}", e.getMessage(), e);
//            return Collections.emptyMap();
//        }
//    }


    public Map<String, Object> nearbySearch(
            Double lat, Double lng, int radius, String type) {

        if (lat == null || lng == null
                || lat < -90 || lat > 90
                || lng < -180 || lng > 180
                || radius <= 0 || radius > 50000
                || type == null || type.isBlank()) {
            log.warn("Nearby search called with invalid parameters");
            return Collections.emptyMap();
        }

        if (apiKey == null || apiKey.isBlank()) {
            log.error("Google Maps API key is not configured");
            return Collections.emptyMap();
        }

        String url = "https://places.googleapis.com/v1/places:searchNearby";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Goog-Api-Key", apiKey);
        headers.set(
                "X-Goog-FieldMask",
                "places.id,places.displayName,places.location,"
                        + "places.formattedAddress,places.rating,"
                        + "places.userRatingCount,places.businessStatus,"
                        + "places.currentOpeningHours.openNow,"
                        + "places.primaryType,places.types"
        );

        Map<String, Object> center = new HashMap<>();
        center.put("latitude", lat);
        center.put("longitude", lng);

        Map<String, Object> circle = new HashMap<>();
        circle.put("center", center);
        circle.put("radius", (double) radius);

        Map<String, Object> locationRestriction = new HashMap<>();
        locationRestriction.put("circle", circle);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("includedTypes", List.of(type));
        requestBody.put("maxResultCount", 20);
        requestBody.put("locationRestriction", locationRestriction);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(requestBody, headers),
                    Map.class
            );

            Map<String, Object> body = response.getBody();

            log.info("Google Places API (New) returned {} places",
                    body != null && body.get("places") instanceof List<?>
                            ? ((List<?>) body.get("places")).size()
                            : 0);

            return body != null ? body : Collections.emptyMap();

        } catch (RestClientResponseException e) {
            log.error("Google Places API error: status={}, body={}",
                    e.getStatusCode(),
                    e.getResponseBodyAsString());
            return Collections.emptyMap();

        } catch (Exception e) {
            log.error("Google Places API unexpected error: {}",
                    e.getMessage(), e);
            return Collections.emptyMap();
        }
    }

//    public Map<String, Object> nearbySearch(Double lat, Double lng, int radius, String type) {
//        if (lat == null || lng == null) {
//            log.warn("Nearby search called with null coordinates");
//            return Collections.emptyMap();
//        }
//
//        String url = UriComponentsBuilder
//                .fromHttpUrl("https://maps.googleapis.com/maps/api/place/nearbysearch/json")
//                .queryParam("location", lat + "," + lng)
//                .queryParam("radius", radius)
//                .queryParam("type", type)
//                .queryParam("key", apiKey)
//                .toUriString();
//
//        log.info("Google Nearby Search URL: {}", safeUrl(url));
//
//        try {
//            ResponseEntity<Map> response = restTemplate.exchange(
//                    url,
//                    HttpMethod.GET,
//                    new HttpEntity<>(new HttpHeaders()),
//                    Map.class
//            );
//
//            Map<String, Object> body = response.getBody();
//            log.info("Google Nearby Search response: {}", body);
//            return body != null ? body : Collections.emptyMap();
//
//        } catch (RestClientResponseException e) {
//            log.error("Google Nearby Search API error: status={}, body={}",
//                    e.getRawStatusCode(), e.getResponseBodyAsString(), e);
//            return Collections.emptyMap();
//        } catch (Exception e) {
//            log.error("Google Nearby Search unexpected error: {}", e.getMessage(), e);
//            return Collections.emptyMap();
//        }
//    }

    public Map<String, Object> geocodeCityRaw(String city) {
        String normalizedCity = city == null ? "" : city.trim();

        if (normalizedCity.isBlank()) {
            log.warn("Geocode called with blank city");
            return Collections.emptyMap();
        }

        if (!normalizedCity.toLowerCase().contains("india")) {
            normalizedCity = normalizedCity + ", india";
        }

        String url = UriComponentsBuilder
                .fromHttpUrl("https://maps.googleapis.com/maps/api/geocode/json")
                .queryParam("address", normalizedCity)
                .queryParam("key", apiKey)
                .toUriString();

        log.info("Google Geocode RAW URL: {}", safeUrl(url));

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(new HttpHeaders()),
                    Map.class
            );

            Map<String, Object> body = response.getBody();
            log.info("Google Geocode RAW response: {}", body);
            return body != null ? body : Collections.emptyMap();

        } catch (RestClientResponseException e) {
            log.error("Google Geocode RAW API error: status={}, body={}",
                    e.getRawStatusCode(), e.getResponseBodyAsString(), e);
            return Collections.emptyMap();
        } catch (Exception e) {
            log.error("Google Geocode RAW unexpected error: {}", e.getMessage(), e);
            return Collections.emptyMap();
        }
    }

    public Map<String, Object> geocodeCity(String city) {
        String normalizedCity = city == null ? "" : city.trim();

        if (normalizedCity.isBlank()) {
            log.warn("Geocode called with blank city");
            return Collections.emptyMap();
        }

        if (!normalizedCity.toLowerCase().contains("india")) {
            normalizedCity = normalizedCity + ", india";
        }

        String url = UriComponentsBuilder
                .fromHttpUrl("https://maps.googleapis.com/maps/api/geocode/json")
                .queryParam("address", normalizedCity)
                .queryParam("key", apiKey)
                .toUriString();

        log.info("Google Geocode URL: {}", safeUrl(url));

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(new HttpHeaders()),
                    Map.class
            );

            Map<String, Object> body = response.getBody();
            log.info("Google Geocode full response: {}", body);

            if (body == null || body.isEmpty()) {
                return Collections.emptyMap();
            }

            Object resultsObj = body.get("results");
            if (!(resultsObj instanceof List<?> results) || results.isEmpty()) {
                return Collections.emptyMap();
            }

            Object firstObj = results.get(0);
            if (!(firstObj instanceof Map<?, ?> firstResult)) {
                return Collections.emptyMap();
            }

            Object geometryObj = firstResult.get("geometry");
            if (!(geometryObj instanceof Map<?, ?> geometry)) {
                return Collections.emptyMap();
            }

            Object locationObj = geometry.get("location");
            if (!(locationObj instanceof Map<?, ?> location)) {
                return Collections.emptyMap();
            }

            Object latObj = location.get("lat");
            Object lngObj = location.get("lng");

            if (!(latObj instanceof Number latNum) || !(lngObj instanceof Number lngNum)) {
                return Collections.emptyMap();
            }

            Map<String, Object> coordinates = new HashMap<>();
            coordinates.put("lat", latNum.doubleValue());
            coordinates.put("lng", lngNum.doubleValue());
            return coordinates;

        } catch (RestClientResponseException e) {
            log.error("Google Geocode API error: status={}, body={}",
                    e.getRawStatusCode(), e.getResponseBodyAsString(), e);
            return Collections.emptyMap();
        } catch (Exception e) {
            log.error("Google Geocode unexpected error: {}", e.getMessage(), e);
            return Collections.emptyMap();
        }
    }

    public Map<String, Object> getCurrentWeather(double lat, double lng) {
        String url = UriComponentsBuilder
                .fromHttpUrl("https://weather.googleapis.com/v1/currentConditions:lookup")
                .queryParam("key", apiKey)
                .queryParam("location.latitude", lat)
                .queryParam("location.longitude", lng)
                .toUriString();

        log.info("Google Weather URL: {}", safeUrl(url));

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(new HttpHeaders()),
                    Map.class
            );

            Map<String, Object> body = response.getBody();
            log.info("Google Weather full response: {}", body);
            return body != null ? body : Collections.emptyMap();

        } catch (RestClientResponseException e) {
            log.error("Google Weather API error: status={}, body={}",
                    e.getRawStatusCode(), e.getResponseBodyAsString(), e);
            return Collections.emptyMap();
        } catch (Exception e) {
            log.error("Google Weather unexpected error: {}", e.getMessage(), e);
            return Collections.emptyMap();
        }
    }


    public Map<String, Object> getDirections(
            String origin,
            String destination,
            String waypoints,
            String travelMode) {

        if (apiKey == null || apiKey.isBlank()) {
            return Map.of(
                    "status", "REQUEST_DENIED",
                    "error_message", "Google Maps API key is not configured"
            );
        }

        String url =
                "https://routes.googleapis.com/directions/v2:computeRoutes";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Goog-Api-Key", apiKey);
        headers.set(
                "X-Goog-FieldMask",
                "routes.distanceMeters,routes.duration,"
                        + "routes.polyline.encodedPolyline,"
                        + "routes.legs.distanceMeters,routes.legs.duration"
        );

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("origin", toRouteWaypoint(origin));
        requestBody.put("destination", toRouteWaypoint(destination));
        requestBody.put("travelMode", toRoutesTravelMode(travelMode));
        requestBody.put("units", "METRIC");
        requestBody.put("languageCode", "en");

        if (waypoints != null && !waypoints.isBlank()) {
            List<Map<String, Object>> intermediates =
                    java.util.Arrays.stream(waypoints.split("\\|"))
                            .filter(value -> !value.isBlank())
                            .map(this::toRouteWaypoint)
                            .toList();

            requestBody.put("intermediates", intermediates);
        }

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(requestBody, headers),
                    Map.class
            );

            Map<String, Object> body = response.getBody();

            if (body == null) {
                throw new ExternalServiceException(
                        "Routes API returned an empty response"
                );
            }

            Object routesObject = body.get("routes");

            if (!(routesObject instanceof List<?> routes)
                    || routes.isEmpty()) {
                throw new ExternalServiceException(
                        "Routes API returned no available routes"
                );
            }

            List<Map<String, Object>> normalizedRoutes =
                    new java.util.ArrayList<>();

            for (Object routeObject : routes) {
                if (!(routeObject instanceof Map<?, ?> route)) {
                    continue;
                }

                Map<String, Object> normalizedRoute = new HashMap<>();

                normalizedRoute.put(
                        "distance",
                        Map.of("value", numberValue(route.get("distanceMeters")))
                );

                normalizedRoute.put(
                        "duration",
                        Map.of("value", parseDurationSeconds(route.get("duration")))
                );

                Object polylineObject = route.get("polyline");
                if (polylineObject instanceof Map<?, ?> polyline) {
                    normalizedRoute.put(
                            "overview_polyline",
                            Map.of(
                                    "points",
                                    polyline.get("encodedPolyline") == null
                                            ? ""
                                            : polyline.get("encodedPolyline")
                            )
                    );
                }

                List<Map<String, Object>> normalizedLegs =
                        new java.util.ArrayList<>();

                Object legsObject = route.get("legs");

                if (legsObject instanceof List<?> legs) {
                    for (Object legObject : legs) {
                        if (!(legObject instanceof Map<?, ?> leg)) {
                            continue;
                        }

                        Map<String, Object> normalizedLeg = new HashMap<>();

                        normalizedLeg.put(
                                "distance",
                                Map.of(
                                        "value",
                                        numberValue(leg.get("distanceMeters"))
                                )
                        );

                        normalizedLeg.put(
                                "duration",
                                Map.of(
                                        "value",
                                        parseDurationSeconds(leg.get("duration"))
                                )
                        );

                        normalizedLegs.add(normalizedLeg);
                    }
                }

                normalizedRoute.put("legs", normalizedLegs);
                normalizedRoutes.add(normalizedRoute);
            }

            return Map.of(
                    "status", "OK",
                    "routes", normalizedRoutes
            );

        } catch (RestClientResponseException e) {
            log.error(
                    "Google Routes API error: status={}, body={}",
                    e.getStatusCode(),
                    e.getResponseBodyAsString()
            );

            throw new ExternalServiceException(
                    "Google Routes API request failed: HTTP "
                            + e.getStatusCode()
                            + ". Check the API configuration and request."
            );
        }
    }

    private Map<String, Object> toRouteWaypoint(String coordinates) {
        String[] parts = coordinates.split(",");

        if (parts.length != 2) {
            throw new IllegalArgumentException(
                    "Invalid coordinates: " + coordinates
            );
        }

        double latitude = Double.parseDouble(parts[0].trim());
        double longitude = Double.parseDouble(parts[1].trim());

        Map<String, Object> latLng = new HashMap<>();
        latLng.put("latitude", latitude);
        latLng.put("longitude", longitude);

        Map<String, Object> location = new HashMap<>();
        location.put("latLng", latLng);

        return Map.of("location", location);
    }

    private String toRoutesTravelMode(String travelMode) {
        return switch (travelMode.toLowerCase(java.util.Locale.ROOT)) {
            case "driving" -> "DRIVE";
            case "walking" -> "WALK";
            case "bicycling" -> "BICYCLE";
            case "transit" -> "TRANSIT";
            default -> throw new IllegalArgumentException(
                    "Unsupported travel mode: " + travelMode
            );
        };
    }

    private long numberValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private long parseDurationSeconds(Object value) {
        if (value == null) {
            return 0L;
        }

        String duration = value.toString().trim();

        if (!duration.endsWith("s")) {
            throw new IllegalArgumentException(
                    "Unexpected Routes API duration: " + duration
            );
        }

        return (long) Double.parseDouble(
                duration.substring(0, duration.length() - 1)
        );
    }


//    public Map<String, Object> getDirections(String origin, String destination, String waypoints, String travelMode) {
//        UriComponentsBuilder builder = UriComponentsBuilder
//                .fromHttpUrl("https://maps.googleapis.com/maps/api/directions/json")
//                .queryParam("origin", origin)
//                .queryParam("destination", destination)
//                .queryParam("mode", travelMode)
//                .queryParam("key", apiKey);
//
//        if (waypoints != null && !waypoints.isBlank()) {
//            builder.queryParam("waypoints", waypoints);
//        }
//
//        String url = builder.toUriString();
//        log.info("Google Directions URL: {}", safeUrl(url));
//
//        try {
//            ResponseEntity<Map> response = restTemplate.exchange(
//                    url,
//                    HttpMethod.GET,
//                    new HttpEntity<>(new HttpHeaders()),
//                    Map.class
//            );
//
//            Map<String, Object> body = response.getBody();
//            log.info("Google Directions response status: {}", body != null ? body.get("status") : "null body");
//            return body != null ? body : Collections.emptyMap();
//
//        } catch (RestClientResponseException e) {
//            log.error("Google Directions API error: status={}, body={}",
//                    e.getRawStatusCode(), e.getResponseBodyAsString(), e);
//            return Collections.emptyMap();
//        } catch (Exception e) {
//            log.error("Google Directions unexpected error: {}", e.getMessage(), e);
//            return Collections.emptyMap();
//        }
//    }

    private String safeUrl(String url) {
        return apiKey == null || apiKey.isBlank() ? url : url.replace(apiKey, "****");
    }
}