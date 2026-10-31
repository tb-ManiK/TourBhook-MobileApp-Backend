package com.tourbhook.api.service.event;

import com.tourbhook.api.config.EventProperties;
import com.tourbhook.api.repository.TripRepository;
import com.tourbhook.api.service.impl.GoogleMapsClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class SyncAreaResolver {

    private final EventProperties properties;
    private final TripRepository tripRepository;
    private final GoogleMapsClient googleMapsClient;

    private final Map<String, Optional<SyncArea>> locatedCities = new ConcurrentHashMap<>();

    public List<SyncArea> resolve(LocalDate from, LocalDate to) {
        Map<String, SyncArea> areas = new LinkedHashMap<>();

        for (EventProperties.Area configured : properties.getAreas()) {
            if (configured.getCity() != null && !configured.getCity().isBlank()) {
                SyncArea area = new SyncArea(configured.getCity().trim(), configured.getLatitude(), configured.getLongitude());
                areas.putIfAbsent(area.key(), area);
            }
        }

        int tripAreasAdded = 0;
        for (String city : tripRepository.findDistinctCitiesWithTripsBetween(from, to)) {
            if (tripAreasAdded >= properties.getSync().getMaxTripAreas()) {
                log.info("Reached the limit of {} trip destinations for this sync", properties.getSync().getMaxTripAreas());
                break;
            }
            if (city == null || city.isBlank() || areas.containsKey(city.trim().toLowerCase(Locale.ROOT))) {
                continue;
            }
            Optional<SyncArea> located = locate(city.trim());
            if (located.isPresent() && areas.putIfAbsent(located.get().key(), located.get()) == null) {
                tripAreasAdded++;
            }
        }
        return List.copyOf(areas.values());
    }

    private Optional<SyncArea> locate(String city) {
        String key = city.toLowerCase(Locale.ROOT);
        Optional<SyncArea> cached = locatedCities.get(key);
        if (cached != null) {
            return cached;
        }

        Map<String, Object> coordinates = googleMapsClient.geocodeCity(city);
        Object lat = coordinates == null ? null : coordinates.get("lat");
        Object lng = coordinates == null ? null : coordinates.get("lng");
        if (lat instanceof Number latitude && lng instanceof Number longitude) {
            Optional<SyncArea> area = Optional.of(new SyncArea(city, latitude.doubleValue(), longitude.doubleValue()));
            locatedCities.put(key, area); // only successes are remembered; a failed lookup is retried next run
            return area;
        }
        log.warn("Could not locate trip destination '{}', so no events are synced for it", city);
        return Optional.empty();
    }
}