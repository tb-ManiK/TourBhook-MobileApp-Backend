package com.tourbhook.api.service.impl;

import com.tourbhook.api.config.EventProperties;
import com.tourbhook.api.dto.event.EventFeedQuery;
import com.tourbhook.api.dto.event.EventFeedResponse;
import com.tourbhook.api.dto.event.EventResponse;
import com.tourbhook.api.entity.Trip;
import com.tourbhook.api.entity.User;
import com.tourbhook.api.repository.LocalEventRepository;
import com.tourbhook.api.repository.TripRepository;
import com.tourbhook.api.repository.exception.BadRequestException;
import com.tourbhook.api.repository.exception.ResourceNotFoundException;
import com.tourbhook.api.service.AuthenticatedUserService;
import com.tourbhook.api.service.EventFeedService;
import com.tourbhook.api.service.event.EventSearcher;
import com.tourbhook.api.service.event.EventSearcher.DateRange;
import com.tourbhook.api.service.event.PageSlice;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventFeedServiceImpl implements EventFeedService {

    private final EventSearcher eventSearcher;
    private final LocalEventRepository eventRepository;
    private final TripRepository tripRepository;
    private final AuthenticatedUserService authenticatedUserService;
    private final EventProperties properties;

    @Override
    public EventFeedResponse getFeed(EventFeedQuery query) {
        DateRange range = eventSearcher.resolveRange(query.from(), query.to());

        List<EventResponse> matches;
        if (query.lat() != null || query.lng() != null) {
            double lat = eventSearcher.requireCoordinate(query.lat(), "lat", 90);
            double lng = eventSearcher.requireCoordinate(query.lng(), "lng", 180);
            double radiusKm = eventSearcher.resolveRadius(query.radiusKm());
            matches = eventSearcher.around(lat, lng, radiusKm, range.from(), range.to(), query.category());
        } else if (query.city() != null && !query.city().isBlank()) {
            matches = eventSearcher.inCity(query.city(), range.from(), range.to(), query.category());
        } else {
            throw new BadRequestException("Provide a location: either city, or lat and lng.");
        }
        return respond(matches, query.page(), query.size(), range.from(), range.to());
    }

    @Override
    public EventFeedResponse getFeedForTrip(String tripId, String category, int page, int size) {
        User user = authenticatedUserService.getCurrentUser();
        Trip trip = tripRepository.findById(tripId)
                .filter(found -> found.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found"));

        LocalDate today = eventSearcher.today();
        LocalDate from = trip.getStartDate().isBefore(today) ? today : trip.getStartDate();
        if (trip.getEndDate().isBefore(from)) {
            // The trip is over, so nothing that is on there can still be upcoming.
            return respond(List.of(), page, size, trip.getStartDate(), trip.getEndDate());
        }

        LocalDate to = trip.getEndDate();
        LocalDate lastAllowedDay = from.plusDays(properties.getMaxWindowDays() - 1L);
        if (to.isAfter(lastAllowedDay)) {
            to = lastAllowedDay; // an unusually long trip is shown in its first stretch
        }
        return respond(eventSearcher.inCity(trip.getCity(), from, to, category), page, size, from, to);
    }

    private EventFeedResponse respond(List<EventResponse> matches, int page, int size, LocalDate from, LocalDate to) {
        PageSlice<EventResponse> slice = PageSlice.of(matches, page, size, properties.getMaxPageSize());
        return new EventFeedResponse(
                slice.items(),
                slice.page(),
                slice.size(),
                slice.totalElements(),
                slice.totalPages(),
                from,
                to,
                eventRepository.findLastSyncedAt());
    }
}