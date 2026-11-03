package com.tourbhook.api.service.impl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.tourbhook.api.dto.place.PlaceResponse;
import com.tourbhook.api.dto.trip.CreateTripRequest;
import com.tourbhook.api.dto.trip.GenerateTripRequest;
import com.tourbhook.api.dto.trip.GeneratedTripResponse;
import com.tourbhook.api.dto.trip.TripDetailResponse;
import com.tourbhook.api.dto.trip.TripStartingPointDto;
import com.tourbhook.api.dto.trip.TripSummaryResponse;
import com.tourbhook.api.dto.trip.UpdateTripRequest;
import com.tourbhook.api.entity.Activity;
import com.tourbhook.api.entity.Itinerary;
import com.tourbhook.api.entity.ItineraryDay;
import com.tourbhook.api.entity.PackingCategory;
import com.tourbhook.api.entity.PackingItem;
import com.tourbhook.api.entity.PackingList;
import com.tourbhook.api.entity.Place;
import com.tourbhook.api.entity.Trip;
import com.tourbhook.api.entity.TripPlace;
import com.tourbhook.api.entity.User;
import com.tourbhook.api.repository.ItineraryRepository;
import com.tourbhook.api.repository.PackingCategoryRepository;
import com.tourbhook.api.repository.PackingListRepository;
import com.tourbhook.api.repository.PlaceRepository;
import com.tourbhook.api.repository.TripPlaceRepository;
import com.tourbhook.api.repository.TripRepository;
import com.tourbhook.api.repository.exception.BadRequestException;
import com.tourbhook.api.repository.exception.ResourceNotFoundException;
import com.tourbhook.api.service.AnalyticsService;
import com.tourbhook.api.service.AuthenticatedUserService;
import com.tourbhook.api.service.TripService;
import com.tourbhook.api.service.image.ImageUrlResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TripServiceImpl implements TripService {

    private final TripRepository tripRepository;
    private final ItineraryRepository itineraryRepository;
    private final PlaceRepository placeRepository;
    private final TripPlaceRepository tripPlaceRepository;
    private final PackingListRepository packingListRepository;
    private final PackingCategoryRepository packingCategoryRepository;
    private final AnalyticsService analyticsService;
    private final AuthenticatedUserService authenticatedUserService;
    private final ImageUrlResolver imageUrlResolver;

    @Override
    @Transactional(readOnly = true)
    public List<TripSummaryResponse> getTrips() {
        User user = getCurrentUser();
        return tripRepository.findByUserOrderByStartDateDesc(user)
                .stream()
                .map(this::mapTripSummary)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public TripSummaryResponse getTripById(String id) {
        return mapTripSummary(getOwnedTrip(id));
    }

    public String fetchTripStyle(Integer tripStyle) {
        if (tripStyle == null) {
            return "Balanced";
        }

        return switch (tripStyle) {
            case 1 -> "Budget Friendly";
            case 2 -> "Fast Travel";
            case 3 -> "Relaxed Vacation";
            case 4 -> "Explore Everything";
            case 5 -> "Food Lover";
            default -> "Balanced";
        };
    }

    @Override
    public TripSummaryResponse createTrip(CreateTripRequest request) {
        validateDates(request.start_date(), request.end_date());

        final String tripStyle = fetchTripStyle(request.trip_style());

        Trip trip = createTripInternal(
                request.city(),
                request.start_date(),
                request.end_date(),
                request.adults(),
                request.kids(),
                tripStyle,
                request.purpose_of_travel(),
                List.of()
        );

        if (request.starting_point() != null) {
            Place savedStartingPoint = saveOrUpdateStartingPoint(
                    request.starting_point(),
                    request.city()
            );

            if (savedStartingPoint != null) {
                trip.setStartingPointId(savedStartingPoint.getId());
                tripRepository.save(trip);
            }
        }

        packingListRepository.findByTrip(trip)
                .orElseGet(() -> packingListRepository.save(buildPackingList(trip)));

        analyticsService.track("trip_created", Map.of(
                "tripId", trip.getId(),
                "city", trip.getCity(),
                "purposeOfTravel", safeValue(trip.getPurposeofTravel())
        ));

        return mapTripSummary(trip);
    }

    @Override
    public GeneratedTripResponse generateTrip(GenerateTripRequest request) {
        validateDates(request.start_date(), request.end_date());

        List<String> placeIds = request.selected_places() == null ? List.of() : request.selected_places();

        Trip trip = createTripInternal(
                request.city(),
                request.start_date(),
                request.end_date(),
                request.adults(),
                request.kids(),
                request.trip_style(),
                request.purpose_of_travel(),
                placeIds
        );

        Place savedStartingPoint = null;
        if (request.starting_point() != null) {
            savedStartingPoint = saveOrUpdateStartingPoint(
                    request.starting_point(),
                    request.city()
            );

            if (savedStartingPoint != null) {
                trip.setStartingPointId(savedStartingPoint.getId());
                tripRepository.save(trip);
            }
        }

        Itinerary itinerary = buildItinerary(trip, trip.getCity(), trip.getDays());
        itineraryRepository.save(itinerary);

        packingListRepository.findByTrip(trip)
                .orElseGet(() -> packingListRepository.save(buildPackingList(trip)));

        analyticsService.track("trip_generated", Map.of(
                "tripId", trip.getId(),
                "itineraryId", itinerary.getId(),
                "purposeOfTravel", safeValue(trip.getPurposeofTravel())
        ));

        return new GeneratedTripResponse(
                trip.getId(),
                itinerary.getId(),
                trip.getCity(),
                trip.getStartDate(),
                trip.getEndDate(),
                trip.getDays(),
                trip.getTripStyle(),
                trip.getPurposeofTravel(),
                mapStartingPoint(savedStartingPoint),
                placeIds,
                imageUrlResolver.forTrip(trip)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public TripDetailResponse getTripDetail(String id) {
        Trip trip = getOwnedTrip(id);

        List<PlaceResponse> places = tripPlaceRepository.findByTrip(trip)
                .stream()
                .map(TripPlace::getPlace)
                .map(this::mapPlace)
                .toList();

        if (places.isEmpty()) {
            places = placeRepository.findByCityIgnoreCase(trip.getCity())
                    .stream()
                    .limit(Math.max(1, trip.getPlacesCount()))
                    .map(this::mapPlace)
                    .toList();
        }

        String itineraryId = itineraryRepository.findByTrip(trip)
                .map(Itinerary::getId)
                .orElse(null);

        Place startingPointPlace = null;
        if (trip.getStartingPointId() != null && !trip.getStartingPointId().isBlank()) {
            startingPointPlace = placeRepository.findById(trip.getStartingPointId()).orElse(null);
        }

        return new TripDetailResponse(
                trip.getId(),
                trip.getCity(),
                imageUrlResolver.forTrip(trip),
                trip.getStartDate(),
                trip.getEndDate(),
                trip.getPlacesCount(),
                trip.getDays(),
                trip.getAdults(),
                trip.getKids(),
                trip.getTripStyle(),
                trip.getPurposeofTravel(),
                itineraryId,
                places,
                mapStartingPoint(startingPointPlace)
        );
    }

    @Override
    public TripDetailResponse updateTrip(String id, UpdateTripRequest request) {
        Trip trip = getOwnedTrip(id);

        if (request.city() != null) trip.setCity(request.city());
        if (request.start_date() != null) trip.setStartDate(request.start_date());
        if (request.end_date() != null) trip.setEndDate(request.end_date());
        if (request.adults() != null) trip.setAdults(request.adults());
        if (request.kids() != null) trip.setKids(request.kids());
        if (request.trip_style() != null) trip.setTripStyle(request.trip_style());
        if (request.purpose_of_travel() != null) trip.setPurposeofTravel(request.purpose_of_travel());
        if (request.image_url() != null) trip.setImageUrl(request.image_url());

        if (trip.getStartDate() != null && trip.getEndDate() != null) {
            validateDates(trip.getStartDate(), trip.getEndDate());
        }

        trip.setDays(calculateDays(trip.getStartDate(), trip.getEndDate()));

        if (request.place_ids() != null) {
            tripPlaceRepository.findByTrip(trip).forEach(tripPlaceRepository::delete);

            for (String placeId : request.place_ids()) {
                placeRepository.findById(placeId)
                        .ifPresent(place -> tripPlaceRepository.save(
                                TripPlace.builder().trip(trip).place(place).build()
                        ));
            }

            trip.setPlacesCount(request.place_ids().size());
        }
        if (request.place_ids() != null) {
            String newImageUrl = tripPlaceRepository.findByTrip(trip).stream()
                    .map(TripPlace::getPlace)
                    .filter(Objects::nonNull)
                    .map(Place::getImageUrl)
                    .filter(url -> url != null && !url.isBlank())
                    .findFirst()
                    .orElse(null);

            trip.setImageUrl(newImageUrl);
        }

        if (request.starting_point() != null) {
            Place savedStartingPoint = saveOrUpdateStartingPoint(
                    request.starting_point(),
                    trip.getCity()
            );

            if (savedStartingPoint != null) {
                trip.setStartingPointId(savedStartingPoint.getId());
            }
        }

        tripRepository.save(trip);

        analyticsService.track("trip_updated", Map.of(
                "tripId", trip.getId(),
                "purposeOfTravel", safeValue(trip.getPurposeofTravel())
        ));

        return getTripDetail(id);
    }

    @Override
    public void deleteTrip(String id) {
        Trip trip = getOwnedTrip(id);
        itineraryRepository.findByTrip(trip).ifPresent(itineraryRepository::delete);
        packingListRepository.findByTrip(trip).ifPresent(packingListRepository::delete);
        tripPlaceRepository.findByTrip(trip).forEach(tripPlaceRepository::delete);
        tripRepository.delete(trip);

        analyticsService.track("trip_deleted", Map.of("tripId", id));
    }
    @Override
    public void addPlaceToTrip(String tripId, String placeId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found with id: " + tripId));

        if (!trip.getUser().getId().equals(getCurrentUser().getId())) {
            throw new ResourceNotFoundException("Trip not found with id: " + tripId);
        }

        Place place = placeRepository.findById(placeId)
                .orElseThrow(() -> new ResourceNotFoundException("Place not found with id: " + placeId));

        boolean alreadyExists = tripPlaceRepository.existsByTrip_IdAndPlace_Id(tripId, placeId);
        if (alreadyExists) {
            return;
        }

        TripPlace tripPlace = TripPlace.builder()
                .trip(trip)
                .place(place)
                .build();

        tripPlaceRepository.save(tripPlace);

        int updatedCount = tripPlaceRepository.countByTrip_Id(tripId);
        trip.setPlacesCount(updatedCount);

        if ((trip.getImageUrl() == null || trip.getImageUrl().isBlank())
                && place.getImageUrl() != null
                && !place.getImageUrl().isBlank()) {
            trip.setImageUrl(safeImageUrl(place.getImageUrl()));
        }

        tripRepository.save(trip);
    }

    @Override
    public void removePlaceFromTrip(String tripId, String placeId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found with id: " + tripId));

        if (!trip.getUser().getId().equals(getCurrentUser().getId())) {
            throw new ResourceNotFoundException("Trip not found with id: " + tripId);
        }

        Place place = placeRepository.findById(placeId)
                .orElseThrow(() -> new ResourceNotFoundException("Place not found with id: " + placeId));

        TripPlace tripPlace = tripPlaceRepository.findByTrip_IdAndPlace_Id(tripId, placeId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip place mapping not found"));

        tripPlaceRepository.delete(tripPlace);

        int updatedCount = tripPlaceRepository.countByTrip_Id(tripId);
        trip.setPlacesCount(updatedCount);

        if (trip.getImageUrl() != null
                && !trip.getImageUrl().isBlank()
                && Objects.equals(trip.getImageUrl(), place.getImageUrl())) {

            String newImageUrl = tripPlaceRepository.findByTrip_Id(tripId).stream()
                    .map(tp -> tp.getPlace())
                    .filter(Objects::nonNull)
                    .map(p -> p.getImageUrl())
                    .filter(url -> url != null && !url.isBlank())
                    .map(this::safeImageUrl)
                    .findFirst()
                    .orElse(null);

            trip.setImageUrl(newImageUrl);
        }

        if (updatedCount == 0) {
            trip.setImageUrl(null);
        }

        tripRepository.save(trip);
    }
    public Trip getOwnedTrip(String id) {
        Trip trip = tripRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found"));

        if (!trip.getUser().getId().equals(getCurrentUser().getId())) {
            throw new ResourceNotFoundException("Trip not found");
        }

        return trip;
    }

    private Trip createTripInternal(
            String city,
            LocalDate startDate,
            LocalDate endDate,
            Integer adults,
            Integer kids,
            String tripStyle,
            String purposeOfTravel,
            List<String> placeIds
    ) {
        List<Place> selectedPlaces = new ArrayList<>();

        if (placeIds != null && !placeIds.isEmpty()) {
            for (String placeId : placeIds) {
                placeRepository.findById(placeId).ifPresent(selectedPlaces::add);
            }
        }

        String tripImageUrl = selectedPlaces.stream()
                .map(Place::getImageUrl)
                .filter(url -> url != null && !url.isBlank())
                .findFirst()
                .orElse(null);

        Trip trip = tripRepository.save(
                Trip.builder()
                        .user(getCurrentUser())
                        .city(city)
                        .imageUrl(tripImageUrl)
                        .startDate(startDate)
                        .endDate(endDate)
                        .placesCount(selectedPlaces.size())
                        .days(calculateDays(startDate, endDate))
                        .adults(adults)
                        .kids(kids)
                        .tripStyle(tripStyle == null || tripStyle.isBlank() ? "Balanced" : tripStyle)
                        .purposeofTravel(
                                purposeOfTravel == null || purposeOfTravel.isBlank()
                                        ? "general"
                                        : purposeOfTravel
                        )
                        .build()
        );

        for (Place place : selectedPlaces) {
            tripPlaceRepository.save(
                    TripPlace.builder()
                            .trip(trip)
                            .place(place)
                            .build()
            );
        }

        return trip;
    }
    
    private Itinerary buildItinerary(Trip trip, String city, Integer days) {
        List<Place> selectedPlaces = tripPlaceRepository.findByTrip(trip)
                .stream()
                .map(TripPlace::getPlace)
                .toList();

        List<ItineraryDay> itineraryDays = new ArrayList<>();
        int totalDays = Math.max(1, days);

        for (int day = 1; day <= totalDays; day++) {
            itineraryDays.add(ItineraryDay.builder()
                    .dayNumber(day)
                    .activities(new ArrayList<>())
                    .build());
        }

        if (selectedPlaces.isEmpty()) {
            for (int day = 1; day <= totalDays; day++) {
                itineraryDays.get(day - 1).getActivities().add(
                        Activity.builder()
                                .activityTime("09:00 AM")
                                .title("Explore " + city)
                                .duration("3 hr")
                                .cost(BigDecimal.ZERO)
                                .icon("explore")
                                .build()
                );
                itineraryDays.get(day - 1).getActivities().add(
                        Activity.builder()
                                .activityTime("01:30 PM")
                                .title("Lunch and local food break")
                                .duration("1 hr")
                                .cost(new BigDecimal("300"))
                                .icon("food")
                                .build()
                );
            }
        } else {
            int dayIndex = 0;

            for (Place place : selectedPlaces) {
                ItineraryDay itineraryDay = itineraryDays.get(dayIndex);

                if (itineraryDay.getActivities().isEmpty()) {
                    itineraryDay.getActivities().add(
                            Activity.builder()
                                    .activityTime("08:30 AM")
                                    .title("Start day " + itineraryDay.getDayNumber() + " from hotel")
                                    .duration("30 min")
                                    .cost(BigDecimal.ZERO)
                                    .icon("start")
                                    .build()
                    );
                }

                int activityIndex = itineraryDay.getActivities().size();

                itineraryDay.getActivities().add(
                        Activity.builder()
                                .activityTime(nextVisitTime(activityIndex))
                                .title("Visit " + place.getName())
                                .duration(place.getTypicalDuration() == null || place.getTypicalDuration().isBlank()
                                        ? "2 hr"
                                        : place.getTypicalDuration())
                                .cost(BigDecimal.ZERO)
                                .icon(resolveIcon(place.getCategory()))
                                .placeId(place.getId())
                                .build()
                );

                itineraryDay.getActivities().add(
                        Activity.builder()
                                .activityTime(nextBreakTime(itineraryDay.getActivities().size()))
                                .title("Travel / rest / food break near " + place.getName())
                                .duration("45 min")
                                .cost(new BigDecimal("200"))
                                .icon("food")
                                .build()
                );

                dayIndex = (dayIndex + 1) % totalDays;
            }
        }

        return Itinerary.builder()
                .trip(trip)
                .city(city)
                .title(city + " Smart Itinerary")
                .days(itineraryDays)
                .build();
    }
    private String safeImageUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        return url.length() > 2000 ? url.substring(0, 2000) : url;
    }

    private String resolveIcon(String category) {
        if (category == null) return "explore";

        String c = category.toLowerCase();

        if (c.contains("restaurant") || c.contains("cafe") || c.contains("food")) {
            return "food";
        }

        if (c.contains("park") || c.contains("garden")) {
            return "nature";
        }

        if (c.contains("museum") || c.contains("art") || c.contains("gallery")) {
            return "culture";
        }

        return "explore";
    }

    private String nextVisitTime(int index) {
        return switch (index) {
            case 1 -> "09:30 AM";
            case 3 -> "12:00 PM";
            case 5 -> "03:30 PM";
            case 7 -> "06:00 PM";
            default -> "09:30 AM";
        };
    }

    private String nextBreakTime(int index) {
        return switch (index) {
            case 2 -> "11:30 AM";
            case 4 -> "01:30 PM";
            case 6 -> "04:30 PM";
            case 8 -> "07:30 PM";
            default -> "11:30 AM";
        };
    }

    private PackingList buildPackingList(Trip trip) {
        List<PackingCategory> templates = packingCategoryRepository.findByTemplateOnlyTrue();
        List<PackingCategory> categories = new ArrayList<>();

        for (PackingCategory template : templates) {
            List<PackingItem> items = template.getItems().stream()
                    .map(item -> PackingItem.builder()
                            .name(item.getName())
                            .checked(false)
                            .quantity(item.getQuantity())
                            .build())
                    .toList();

            categories.add(PackingCategory.builder()
                    .name(template.getName())
                    .icon(template.getIcon())
                    .templateOnly(false)
                    .checked(false)
                    .items(new ArrayList<>(items))
                    .build());
        }

        return PackingList.builder()
                .trip(trip)
                .selectedTransports(new ArrayList<>(List.of("car", "walking")))
                .categories(categories)
                .build();
    }

    private void validateDates(LocalDate startDate, LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            throw new BadRequestException("End date cannot be before start date");
        }
    }

    private int calculateDays(LocalDate startDate, LocalDate endDate) {
        return (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
    }

    private User getCurrentUser() {
        return authenticatedUserService.getCurrentUser();
    }

    private TripSummaryResponse mapTripSummary(Trip trip) {
        return new TripSummaryResponse(
                trip.getId(),
                trip.getCity(),
                imageUrlResolver.forTrip(trip),
                trip.getStartDate(),
                trip.getEndDate(),
                trip.getPlacesCount(),
                trip.getDays(),
                trip.getAdults(),
                trip.getKids(),
                trip.getTripStyle(),
                trip.getPurposeofTravel()
        );
    }

    private PlaceResponse mapPlace(Place place) {
        return new PlaceResponse(
                place.getId(),
                place.getName(),
                place.getCategory(),
                imageUrlResolver.forPlace(place),
                place.getTypicalDuration(),
                place.getLatitude(),
                place.getLongitude(),
                place.getRating(),
                place.getReviewsCount(),
                place.isVerified(),
                place.getAddress()
        );
    }


    private String safeValue(String value) {
        return value == null || value.isBlank() ? "general" : value;
    }

    private Place saveOrUpdateStartingPoint(TripStartingPointDto dto, String city) {
        if (dto == null) {
            return null;
        }

        Place place = null;

        // frontend id = googlePlaceId
        if (dto.id() != null && !dto.id().isBlank()) {
            place = placeRepository.findByGooglePlaceId(dto.id()).orElse(null);
        }

        if (place == null && dto.name() != null && !dto.name().isBlank()) {
            place = placeRepository
                    .findFirstByNameIgnoreCaseAndCityIgnoreCase(dto.name(), city)
                    .orElse(null);
        }

        if (place == null) {
            place = new Place();
        }

        place.setGooglePlaceId(dto.id());
        place.setName(dto.name());
        place.setCategory(dto.category());
        place.setImageUrl(dto.imageUrl());
        place.setTypicalDuration(
                dto.typicalDuration() == null || dto.typicalDuration().isBlank()
                        ? "2-3 hours"
                        : dto.typicalDuration()
        );
        place.setLatitude(dto.latitude());
        place.setLongitude(dto.longitude());
        place.setRating(dto.rating());
        place.setReviewsCount(dto.reviewsCount() == null ? 0 : dto.reviewsCount());
        place.setVerified(dto.verified() != null ? dto.verified() : true);
        place.setCity(city);
        place.setSuggested(false);
        place.setSourceQuery("starting_point_frontend");

        return placeRepository.save(place);
    }

    private TripStartingPointDto mapStartingPoint(Place place) {
        if (place == null) {
            return null;
        }

        return new TripStartingPointDto(
                place.getId(),
                place.getName(),
                place.getCategory(),
                imageUrlResolver.forPlace(place),
                place.getTypicalDuration(),
                place.getLatitude(),
                place.getLongitude(),
                place.getRating(),
                place.getReviewsCount(),
                place.isVerified()
        );
    }
}