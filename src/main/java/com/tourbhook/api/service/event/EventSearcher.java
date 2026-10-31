package com.tourbhook.api.service.event;

import com.tourbhook.api.config.EventProperties;
import com.tourbhook.api.dto.event.EventResponse;
import com.tourbhook.api.entity.LocalEvent;
import com.tourbhook.api.repository.LocalEventRepository;
import com.tourbhook.api.repository.exception.BadRequestException;
import com.tourbhook.api.service.venue.GeoDistance;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class EventSearcher {

    private static final int MAX_CANDIDATES = 1000;
    private static final double KM_PER_DEGREE = 111.195;

    public static final Comparator<EventResponse> SOONEST_THEN_NEAREST = Comparator
            .comparing(EventResponse::startDate)
            .thenComparing(EventResponse::startTime, Comparator.nullsFirst(Comparator.<LocalTime>naturalOrder()))
            .thenComparing(EventResponse::distanceKm, Comparator.nullsLast(Comparator.<Double>naturalOrder()))
            .thenComparing(EventResponse::title);

    public record DateRange(LocalDate from, LocalDate to) {
    }

    private final LocalEventRepository eventRepository;
    private final EventProperties properties;
    private final Clock clock;

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    // ------------------------------------------------------------------ searching

    public List<EventResponse> inCity(String city, LocalDate from, LocalDate to, String category) {
        Optional<DateRange> range = upcomingPart(from, to);
        if (range.isEmpty()) {
            return List.of();
        }
        String wantedCategory = normaliseCategory(category);
        return eventRepository.findForCity(city.trim().toLowerCase(Locale.ROOT),
                        range.get().from(), range.get().to(), candidateLimit()).stream()
                .filter(event -> matchesCategory(event, wantedCategory))
                .map(event -> toResponse(event, null))
                .sorted(SOONEST_THEN_NEAREST)
                .toList();
    }

    public List<EventResponse> around(double lat, double lng, double radiusKm,
                                      LocalDate from, LocalDate to, String category) {
        Optional<DateRange> range = upcomingPart(from, to);
        if (range.isEmpty()) {
            return List.of();
        }
        String wantedCategory = normaliseCategory(category);

        // The rectangle is a cheap way to leave most rows behind; the exact distance decides.
        double latDelta = radiusKm / KM_PER_DEGREE;
        double cosLat = Math.cos(Math.toRadians(lat));
        double lngDelta = cosLat < 0.01 ? 180 : Math.min(180, radiusKm / (KM_PER_DEGREE * cosLat));

        return eventRepository.findInBoundingBox(lat - latDelta, lat + latDelta, lng - lngDelta, lng + lngDelta,
                        range.get().from(), range.get().to(), candidateLimit()).stream()
                .filter(event -> matchesCategory(event, wantedCategory))
                .map(event -> new Located(event,
                        GeoDistance.meters(lat, lng, event.getLatitude(), event.getLongitude()) / 1000.0))
                .filter(located -> located.km() <= radiusKm) // exact distance decides, rounding is only for display
                .map(located -> toResponse(located.event(), located.km()))
                .sorted(SOONEST_THEN_NEAREST)
                .toList();
    }

    private record Located(LocalEvent event, double km) {
    }

    // ------------------------------------------------------------------ parameters

    public DateRange resolveRange(LocalDate requestedFrom, LocalDate requestedTo) {
        LocalDate today = today();
        if (requestedFrom != null && requestedTo != null && requestedTo.isBefore(requestedFrom)) {
            throw new BadRequestException("'to' cannot be before 'from'.");
        }
        if (requestedTo != null && requestedTo.isBefore(today)) {
            throw new BadRequestException("'to' is in the past. The feed only shows events that have not ended yet.");
        }

        LocalDate from = requestedFrom == null || requestedFrom.isBefore(today) ? today : requestedFrom;
        LocalDate to = requestedTo != null ? requestedTo : from.plusDays(properties.getDefaultWindowDays() - 1L);

        if (ChronoUnit.DAYS.between(from, to) + 1 > properties.getMaxWindowDays()) {
            throw new BadRequestException("The date range can be at most " + properties.getMaxWindowDays() + " days.");
        }
        return new DateRange(from, to);
    }

    public double requireCoordinate(Double value, String name, double limit) {
        if (value == null || !Double.isFinite(value) || value < -limit || value > limit) {
            throw new BadRequestException(name + " must be a number between -" + (int) limit + " and " + (int) limit
                    + ", and lat and lng must be given together.");
        }
        return value;
    }

    public double resolveRadius(Double requested) {
        if (requested == null) {
            return properties.getDefaultRadiusKm();
        }
        if (!Double.isFinite(requested) || requested <= 0 || requested > properties.getMaxRadiusKm()) {
            throw new BadRequestException("radiusKm must be greater than 0 and at most " + properties.getMaxRadiusKm() + ".");
        }
        return requested;
    }

    // ------------------------------------------------------------------ helpers

    private Optional<DateRange> upcomingPart(LocalDate from, LocalDate to) {
        LocalDate today = today();
        LocalDate start = from.isBefore(today) ? today : from;
        return to.isBefore(start) ? Optional.empty() : Optional.of(new DateRange(start, to));
    }

    private Pageable candidateLimit() {
        return PageRequest.of(0, MAX_CANDIDATES);
    }

    private String normaliseCategory(String category) {
        return category == null || category.isBlank() ? null : category.trim().toLowerCase(Locale.ROOT);
    }

    private boolean matchesCategory(LocalEvent event, String category) {
        return category == null
                || (event.getCategory() != null && event.getCategory().toLowerCase(Locale.ROOT).equals(category));
    }

    private EventResponse toResponse(LocalEvent event, Double distanceKm) {
        return new EventResponse(
                event.getId(),
                event.getTitle(),
                event.getDescription(),
                event.getCategory(),
                event.getVenueName(),
                event.getAddress(),
                event.getCity(),
                event.getLatitude(),
                event.getLongitude(),
                event.getStartDate(),
                event.getEndDate(),
                event.getStartTime(),
                event.getUrl(),
                event.getImageUrl(),
                event.getSource(),
                distanceKm == null ? null : Math.round(distanceKm * 10) / 10.0);
    }
}