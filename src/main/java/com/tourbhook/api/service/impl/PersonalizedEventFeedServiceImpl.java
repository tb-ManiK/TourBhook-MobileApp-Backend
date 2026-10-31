package com.tourbhook.api.service.impl;

import com.tourbhook.api.config.EventProperties;
import com.tourbhook.api.dto.event.EventRelevance;
import com.tourbhook.api.dto.event.EventRelevanceType;
import com.tourbhook.api.dto.event.EventResponse;
import com.tourbhook.api.dto.event.PersonalizedEventFeedResponse;
import com.tourbhook.api.dto.event.PersonalizedEventResponse;
import com.tourbhook.api.dto.event.PersonalizedFeedQuery;
import com.tourbhook.api.entity.Trip;
import com.tourbhook.api.entity.User;
import com.tourbhook.api.repository.LocalEventRepository;
import com.tourbhook.api.repository.TripRepository;
import com.tourbhook.api.repository.exception.BadRequestException;
import com.tourbhook.api.service.AuthenticatedUserService;
import com.tourbhook.api.service.PersonalizedEventFeedService;
import com.tourbhook.api.service.event.EventSearcher;
import com.tourbhook.api.service.event.EventSearcher.DateRange;
import com.tourbhook.api.service.event.PageSlice;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PersonalizedEventFeedServiceImpl implements PersonalizedEventFeedService {

    private static final Comparator<EventRelevance> STRONGEST_REASON_FIRST = Comparator
            .comparing(EventRelevance::type)
            .thenComparing(EventRelevance::tripId, Comparator.nullsLast(Comparator.<String>naturalOrder()));

    private final EventSearcher eventSearcher;
    private final LocalEventRepository eventRepository;
    private final TripRepository tripRepository;
    private final AuthenticatedUserService authenticatedUserService;
    private final EventProperties properties;

    private static final class Match {
        private final EventResponse event;
        private final Set<EventRelevance> reasons = new LinkedHashSet<>();

        private Match(EventResponse event) {
            this.event = event;
        }
    }

    @Override
    public PersonalizedEventFeedResponse getFeedForMe(PersonalizedFeedQuery query) {
        boolean hasLocation = query.lat() != null || query.lng() != null;
        if (!hasLocation && !query.includeItinerary()) {
            throw new BadRequestException("Provide lat and lng, or ask for events on your itinerary.");
        }
        boolean datesGiven = query.from() != null || query.to() != null;
        DateRange explicitRange = datesGiven ? eventSearcher.resolveRange(query.from(), query.to()) : null;

        // Nearby events first, so an event that also matches a trip keeps its distance.
        Map<String, Match> matches = new LinkedHashMap<>();
        if (hasLocation) {
            addNearby(matches, query, explicitRange);
        }
        int tripsConsidered = query.includeItinerary() ? addItinerary(matches, query, explicitRange) : 0;

        List<PersonalizedEventResponse> ranked = matches.values().stream()
                .sorted((a, b) -> EventSearcher.SOONEST_THEN_NEAREST.compare(a.event, b.event))
                .map(match -> new PersonalizedEventResponse(match.event,
                        match.reasons.stream().sorted(STRONGEST_REASON_FIRST).toList()))
                .toList();

        PageSlice<PersonalizedEventResponse> slice =
                PageSlice.of(ranked, query.page(), query.size(), properties.getMaxPageSize());
        return new PersonalizedEventFeedResponse(
                slice.items(),
                slice.page(),
                slice.size(),
                slice.totalElements(),
                slice.totalPages(),
                tripsConsidered,
                eventRepository.findLastSyncedAt());
    }

    // ------------------------------------------------------------------ the two sources

    private void addNearby(Map<String, Match> matches, PersonalizedFeedQuery query, DateRange explicitRange) {
        double lat = eventSearcher.requireCoordinate(query.lat(), "lat", 90);
        double lng = eventSearcher.requireCoordinate(query.lng(), "lng", 180);
        double radiusKm = eventSearcher.resolveRadius(query.radiusKm());
        DateRange range = explicitRange != null ? explicitRange : eventSearcher.resolveRange(null, null);

        for (EventResponse event : eventSearcher.around(lat, lng, radiusKm, range.from(), range.to(), query.category())) {
            matches.computeIfAbsent(event.id(), id -> new Match(event)).reasons.add(
                    new EventRelevance(EventRelevanceType.NEAR_YOU, null, null, event.distanceKm()));
        }
    }

    private int addItinerary(Map<String, Match> matches, PersonalizedFeedQuery query, DateRange explicitRange) {
        User user = authenticatedUserService.getCurrentUser();
        LocalDate today = eventSearcher.today();

        int considered = 0;
        List<Trip> trips = tripRepository.findByUserAndEndDateGreaterThanEqualOrderByStartDateAsc(user, today).stream()
                .limit(properties.getMaxItineraryTrips())
                .toList();
        for (Trip trip : trips) {
            Optional<DateRange> window = windowFor(trip, explicitRange, today);
            if (window.isEmpty()) {
                continue;
            }
            considered++;
            for (EventResponse event : eventSearcher.inCity(trip.getCity(), window.get().from(), window.get().to(), query.category())) {
                matches.computeIfAbsent(event.id(), id -> new Match(event)).reasons.add(
                        new EventRelevance(EventRelevanceType.ON_YOUR_ITINERARY, trip.getId(), trip.getCity(), null));
            }
        }
        return considered;
    }

    private Optional<DateRange> windowFor(Trip trip, DateRange explicitRange, LocalDate today) {
        LocalDate from = later(trip.getStartDate(), today);
        LocalDate to = trip.getEndDate();
        if (explicitRange != null) {
            from = later(from, explicitRange.from());
            to = earlier(to, explicitRange.to());
        }
        to = earlier(to, today.plusDays(properties.getMaxWindowDays() - 1L));
        return from.isAfter(to) ? Optional.empty() : Optional.of(new DateRange(from, to));
    }

    private static LocalDate later(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? b : a;
    }

    private static LocalDate earlier(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? b : a;
    }
}