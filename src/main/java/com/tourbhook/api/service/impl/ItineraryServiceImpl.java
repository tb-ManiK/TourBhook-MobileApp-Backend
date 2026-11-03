package com.tourbhook.api.service.impl;
import com.tourbhook.api.dto.itinerary.*;
import com.tourbhook.api.dto.place.PlaceResponse;
import com.tourbhook.api.entity.Activity;
import com.tourbhook.api.entity.Itinerary;
import com.tourbhook.api.entity.ItineraryDay;
import com.tourbhook.api.entity.Place;
import com.tourbhook.api.entity.Trip;
import com.tourbhook.api.entity.TripPlace;
import com.tourbhook.api.repository.ItineraryRepository;
import com.tourbhook.api.repository.PlaceRepository;
import com.tourbhook.api.repository.TripPlaceRepository;
import com.tourbhook.api.repository.TripRepository;
import com.tourbhook.api.repository.exception.ResourceNotFoundException;
import com.tourbhook.api.service.ItineraryService;
import com.tourbhook.api.service.TripService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.tourbhook.api.service.image.ImageUrlResolver;
import java.util.Locale;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ItineraryServiceImpl implements ItineraryService {

    private final ItineraryRepository itineraryRepository;
    private final TripService tripService;
    private final TripRepository tripRepository;
    private final TripPlaceRepository tripPlaceRepository;
    private final PlaceRepository placeRepository;
    private final ImageUrlResolver imageUrlResolver;

    @Override
    @Transactional(readOnly = true)
    public ItineraryResponse getItinerary(String id) {
        Itinerary itinerary = itineraryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Itinerary not found"));
        return mapItinerary(itinerary);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ItineraryRefResponse> getItineraries() {
        return itineraryRepository.findAllByOrderByCityAsc().stream()
                .map(i -> new ItineraryRefResponse(
                        i.getId(),
                        i.getTrip().getId(),
                        i.getCity(),
                        i.getTitle()
                ))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ActivityResponse> getActivities(String itineraryId) {
        Itinerary itinerary = itineraryRepository.findById(itineraryId)
                .orElseThrow(() -> new ResourceNotFoundException("Itinerary not found"));

        return itinerary.getDays().stream()
                .flatMap(day -> day.getActivities().stream().map(this::mapActivity))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ActivityResponse getActivity(String activityId) {
        return itineraryRepository.findAll().stream()
                .flatMap(i -> i.getDays().stream())
                .flatMap(day -> day.getActivities().stream())
                .filter(activity -> activity.getId().equals(activityId))
                .findFirst()
                .map(this::mapActivity)
                .orElseThrow(() -> new ResourceNotFoundException("Activity not found"));
    }

    @Override
    public ItineraryResponse generateItinerary(GenerateItineraryRequest request) {
        Trip trip = ((TripServiceImpl) tripService).getOwnedTrip(request.trip_id());

        List<Place> tripPlaces = tripPlaceRepository.findByTrip(trip).stream()
                .map(TripPlace::getPlace)
                .filter(Objects::nonNull)
                .toList();

        Integer totalDays = request.days();
        if (totalDays == null || totalDays < 1) {
            totalDays = trip.getDays() == null || trip.getDays() < 1 ? 1 : trip.getDays();
        }

        String city = request.city() == null ? "" : request.city().trim();

        Itinerary itinerary = Itinerary.builder()
                .trip(trip)
                .city(city)
                .title(city + " Generated Itinerary")
                .days(buildDayWiseDays(city, tripPlaces, totalDays))
                .build();

        return mapItinerary(itineraryRepository.save(itinerary));
    }

    @Override
    public ItineraryResponse updateItinerary(String itineraryId, UpdateItineraryRequest request) {
        if (!itineraryId.equals(request.itineraryId())) {
            throw new IllegalArgumentException("Path itineraryId and body itineraryId do not match");
        }

        Itinerary itinerary = itineraryRepository.findById(itineraryId)
                .orElseThrow(() -> new ResourceNotFoundException("Itinerary not found"));

        Trip trip = tripRepository.findById(request.tripId())
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found"));

        if (itinerary.getTrip() == null || !itinerary.getTrip().getId().equals(trip.getId())) {
            throw new IllegalArgumentException("This itinerary does not belong to the given trip");
        }

        String city = request.city() == null ? null : request.city().trim();
        if (city == null || city.isBlank()) {
            throw new IllegalArgumentException("City is required");
        }

        itinerary.setCity(city);
        itinerary.setTitle(city + " Generated Itinerary");

        List<Place> tripPlaces = tripPlaceRepository.findByTrip(trip).stream()
                .map(TripPlace::getPlace)
                .filter(Objects::nonNull)
                .toList();

        if (tripPlaces.isEmpty()) {
            throw new IllegalArgumentException("No places found for this trip");
        }

        Integer totalDays = request.days();
        if (totalDays == null || totalDays < 1) {
            totalDays = trip.getDays() == null || trip.getDays() < 1 ? 1 : trip.getDays();
        }

        List<ItineraryDay> rebuiltDays = buildDayWiseDays(city, tripPlaces, totalDays);
        replaceDaysInPlace(itinerary, rebuiltDays);

        Itinerary saved = itineraryRepository.save(itinerary);
        return mapItinerary(saved);
    }

    @Override
    public ItineraryResponse updateDay(String itineraryId, UpdateDayRequest request) {
        Itinerary itinerary = itineraryRepository.findById(itineraryId)
                .orElseThrow(() -> new ResourceNotFoundException("Itinerary not found"));

        ItineraryDay day = itinerary.getDays().stream()
                .filter(d -> d.getDayNumber().equals(request.day_number()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Day not found"));

        List<Activity> rebuiltActivities = request.activities() == null
                ? new ArrayList<>()
                : request.activities().stream()
                .map(a -> Activity.builder()
                        .activityTime(a.activityTime())
                        .title(a.title())
                        .duration(a.duration())
                        .cost(a.cost())
                        .icon(a.icon())
                        .placeId(a.placeId())
                        .build())
                .toList();

        replaceActivitiesInPlace(day, rebuiltActivities);

        return mapItinerary(itineraryRepository.save(itinerary));
    }

    @Override
    public ActivityResponse addActivity(String itineraryId, AddActivityRequest request) {
        Itinerary itinerary = itineraryRepository.findById(itineraryId)
                .orElseThrow(() -> new ResourceNotFoundException("Itinerary not found"));

        ItineraryDay day = itinerary.getDays().stream()
                .filter(d -> d.getDayNumber().equals(request.day_number()))
                .findFirst()
                .orElseGet(() -> {
                    ItineraryDay created = ItineraryDay.builder()
                            .dayNumber(request.day_number())
                            .activities(new ArrayList<>())
                            .build();
                    itinerary.getDays().add(created);
                    return created;
                });

        if (day.getActivities() == null) {
            day.setActivities(new ArrayList<>());
        }

        Activity activity = Activity.builder()
                .activityTime(request.activity_time())
                .title(request.title())
                .duration(request.duration())
                .cost(request.cost())
                .icon(request.icon())
                .placeId(request.placeId())
                .build();

        day.getActivities().add(activity);
        itineraryRepository.save(itinerary);

        return mapActivity(activity);
    }

    @Override
    public void removeActivity(String activityId) {
        Itinerary itinerary = itineraryRepository.findAll().stream()
                .filter(i -> i.getDays().stream()
                        .anyMatch(d -> d.getActivities().stream()
                                .anyMatch(a -> a.getId().equals(activityId))))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Activity not found"));

        itinerary.getDays().forEach(d -> {
            if (d.getActivities() != null) {
                d.getActivities().removeIf(a -> a.getId().equals(activityId));
            }
        });

        itineraryRepository.save(itinerary);
    }

    @Override
    public ItineraryResponse reoptimize(String itineraryId, ReoptimizeRequest request) {
        Itinerary itinerary = itineraryRepository.findById(itineraryId)
                .orElseThrow(() -> new ResourceNotFoundException("Itinerary not found"));

        String strategy = request.strategy() == null
                ? "smart"
                : request.strategy().trim().toLowerCase();

        log.info("Reoptimize called. itineraryId={}, strategy={}", itineraryId, strategy);

        Map<String, Place> placeMap = placeRepository.findAllById(
                        itinerary.getDays().stream()
                                .flatMap(day -> day.getActivities().stream())
                                .map(Activity::getPlaceId)
                                .filter(id -> id != null && !id.isBlank())
                                .distinct()
                                .toList()
                ).stream()
                .collect(Collectors.toMap(Place::getId, Function.identity()));

        itinerary.getDays().forEach(day -> {
            List<Activity> activities = new ArrayList<>(day.getActivities());

            switch (strategy) {
                case "time" -> activities.sort(activityTimeComparator());
                case "title" -> activities.sort(Comparator.comparing(a -> safeLower(a.getTitle())));
                case "duration" -> activities.sort(Comparator.comparingInt(a -> parseDurationMinutes(a.getDuration())));
                case "low_budget" -> activities.sort(
                        Comparator.comparing(
                                Activity::getCost,
                                Comparator.nullsLast(BigDecimal::compareTo)
                        ).thenComparing(activityTimeComparator())
                );
                case "distance", "nearest" -> activities = sortByNearestPlaces(activities, placeMap);
                case "traffic" -> activities = trafficAwareSort(activities, placeMap);
                case "morning_temple" -> activities = prioritizeMorningTemples(activities, placeMap);
                case "evening_food" -> activities = prioritizeEveningFood(activities, placeMap);
                case "smart" -> activities = smartOptimize(activities, placeMap);
                default -> activities = smartOptimize(activities, placeMap);
            }

            reassignSuggestedTimes(activities, placeMap, strategy);
            replaceActivitiesInPlace(day, activities);
        });

        return mapItinerary(itineraryRepository.save(itinerary));
    }

    private void replaceDaysInPlace(Itinerary itinerary, List<ItineraryDay> rebuiltDays) {
        if (itinerary.getDays() == null) {
            itinerary.setDays(new ArrayList<>());
        } else {
            itinerary.getDays().clear();
        }
        itinerary.getDays().addAll(rebuiltDays);
    }

    private void replaceActivitiesInPlace(ItineraryDay day, List<Activity> rebuiltActivities) {
        if (day.getActivities() == null) {
            day.setActivities(new ArrayList<>());
        } else {
            day.getActivities().clear();
        }
        day.getActivities().addAll(rebuiltActivities);
    }

    private List<ItineraryDay> buildDayWiseDays(String city, List<Place> places, Integer totalDays) {
        int daysCount = (totalDays == null || totalDays < 1) ? 1 : totalDays;

        List<ItineraryDay> result = new ArrayList<>();
        for (int i = 1; i <= daysCount; i++) {
            result.add(ItineraryDay.builder()
                    .dayNumber(i)
                    .activities(new ArrayList<>())
                    .build());
        }

        if (places == null || places.isEmpty()) {
            for (ItineraryDay day : result) {
                day.getActivities().add(Activity.builder()
                        .activityTime("10:00 AM")
                        .title("Explore " + city)
                        .duration("2 hr")
                        .cost(BigDecimal.ZERO)
                        .icon("map")
                        .build());
            }
            return result;
        }

        int placesPerDay = (int) Math.ceil((double) places.size() / daysCount);
        int placeIndex = 0;

        for (ItineraryDay day : result) {
            day.getActivities().add(Activity.builder()
                    .activityTime("08:00 AM")
                    .title("Start day " + day.getDayNumber())
                    .duration("30 min")
                    .cost(BigDecimal.ZERO)
                    .icon("sun")
                    .build());

            for (int i = 0; i < placesPerDay && placeIndex < places.size(); i++) {
                Place place = places.get(placeIndex++);

                day.getActivities().add(Activity.builder()
                        .activityTime(nextVisitTime(day.getActivities().size()))
                        .title(place.getName())
                        .duration(place.getTypicalDuration() == null || place.getTypicalDuration().isBlank()
                                ? "1-2 hr"
                                : place.getTypicalDuration())
                        .cost(BigDecimal.ZERO)
                        .icon(resolveIcon(place.getCategory()))
                        .placeId(place.getId())
                        .build());
            }

            day.getActivities().add(Activity.builder()
                    .activityTime(nextBreakTime(day.getActivities().size()))
                    .title("Lunch / Rest Break")
                    .duration("1 hr")
                    .cost(BigDecimal.ZERO)
                    .icon("food")
                    .build());
        }

        return result;
    }

    private ItineraryResponse mapItinerary(Itinerary itinerary) {
        Map<String, Place> placeMap = placeRepository.findAllById(
                        itinerary.getDays().stream()
                                .flatMap(day -> day.getActivities().stream())
                                .map(Activity::getPlaceId)
                                .filter(id -> id != null && !id.isBlank())
                                .distinct()
                                .toList()
                ).stream()
                .collect(Collectors.toMap(Place::getId, Function.identity()));

        return new ItineraryResponse(
                itinerary.getId(),
                itinerary.getTrip().getId(),
                itinerary.getCity(),
                itinerary.getTitle(),
                itinerary.getDays().stream()
                        .sorted(Comparator.comparing(ItineraryDay::getDayNumber, Comparator.nullsLast(Comparator.naturalOrder())))
                        .map(day -> new ItineraryDayResponse(
                                day.getDayNumber(),
                                day.getActivities().stream().sorted(activityTimeComparator()).map(this::mapActivity).toList(),
                                day.getActivities().stream()
                                        .sorted(activityTimeComparator())
                                        .map(Activity::getPlaceId)
                                        .filter(id -> id != null && !id.isBlank())
                                        .distinct()
                                        .map(placeMap::get)
                                        .filter(Objects::nonNull)
                                        .map(this::mapPlace)
                                        .toList()
                        ))
                        .toList(),
                imageUrlResolver.forTrip(itinerary.getTrip())
        );
    }

    private ActivityResponse mapActivity(Activity activity) {
        return new ActivityResponse(
                activity.getId(),
                activity.getActivityTime(),
                activity.getTitle(),
                activity.getDuration(),
                activity.getCost(),
                activity.getIcon(),
                activity.getPlaceId()
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

    private String resolveIcon(String category) {
        if (category == null) {
            return "map";
        }

        return switch (category.toLowerCase()) {
            case "hindu_temple", "temple" -> "temple";
            case "restaurant", "cafe" -> "food";
            case "park" -> "park";
            case "museum" -> "museum";
            default -> "map";
        };
    }

    private String nextVisitTime(int currentActivitiesSize) {
        return switch (currentActivitiesSize) {
            case 1 -> "10:00 AM";
            case 2 -> "12:00 PM";
            case 3 -> "02:00 PM";
            case 4 -> "04:00 PM";
            default -> "10:00 AM";
        };
    }

    private List<Activity> smartOptimize(List<Activity> activities, Map<String, Place> placeMap) {
        List<Activity> templeMorning = new ArrayList<>();
        List<Activity> foodEvening = new ArrayList<>();
        List<Activity> others = new ArrayList<>();

        for (Activity activity : activities) {
            Place place = getPlace(activity, placeMap);

            if (isTemple(place, activity)) {
                templeMorning.add(activity);
            } else if (isFood(place, activity)) {
                foodEvening.add(activity);
            } else {
                others.add(activity);
            }
        }

        others = sortByNearestPlaces(others, placeMap);
        templeMorning = sortByNearestPlaces(templeMorning, placeMap);
        foodEvening = sortByNearestPlaces(foodEvening, placeMap);

        List<Activity> result = new ArrayList<>();
        result.addAll(templeMorning);
        result.addAll(others);
        result.addAll(foodEvening);
        return result;
    }

    private List<Activity> prioritizeMorningTemples(List<Activity> activities, Map<String, Place> placeMap) {
        List<Activity> temples = new ArrayList<>();
        List<Activity> others = new ArrayList<>();

        for (Activity activity : activities) {
            Place place = getPlace(activity, placeMap);
            if (isTemple(place, activity)) {
                temples.add(activity);
            } else {
                others.add(activity);
            }
        }

        temples = sortByNearestPlaces(temples, placeMap);
        others = sortByNearestPlaces(others, placeMap);

        List<Activity> result = new ArrayList<>();
        result.addAll(temples);
        result.addAll(others);
        return result;
    }

    private List<Activity> prioritizeEveningFood(List<Activity> activities, Map<String, Place> placeMap) {
        List<Activity> foods = new ArrayList<>();
        List<Activity> others = new ArrayList<>();

        for (Activity activity : activities) {
            Place place = getPlace(activity, placeMap);
            if (isFood(place, activity)) {
                foods.add(activity);
            } else {
                others.add(activity);
            }
        }

        others = sortByNearestPlaces(others, placeMap);
        foods = sortByNearestPlaces(foods, placeMap);

        List<Activity> result = new ArrayList<>();
        result.addAll(others);
        result.addAll(foods);
        return result;
    }

    private List<Activity> trafficAwareSort(List<Activity> activities, Map<String, Place> placeMap) {
        List<Activity> morningPreferred = new ArrayList<>();
        List<Activity> neutral = new ArrayList<>();
        List<Activity> eveningPreferred = new ArrayList<>();

        for (Activity activity : activities) {
            Place place = getPlace(activity, placeMap);

            if (isTemple(place, activity) || isNature(place, activity) || isHistorical(place, activity)) {
                morningPreferred.add(activity);
            } else if (isFood(place, activity)) {
                eveningPreferred.add(activity);
            } else {
                neutral.add(activity);
            }
        }

        morningPreferred = sortByNearestPlaces(morningPreferred, placeMap);
        neutral = sortByNearestPlaces(neutral, placeMap);
        eveningPreferred = sortByNearestPlaces(eveningPreferred, placeMap);

        List<Activity> result = new ArrayList<>();
        result.addAll(morningPreferred);
        result.addAll(neutral);
        result.addAll(eveningPreferred);
        return result;
    }

    private List<Activity> sortByNearestPlaces(List<Activity> activities, Map<String, Place> placeMap) {
        if (activities.size() <= 1) {
            return activities;
        }

        List<Activity> remaining = new ArrayList<>(activities);
        List<Activity> sorted = new ArrayList<>();

        Activity current = remaining.remove(0);
        sorted.add(current);

        while (!remaining.isEmpty()) {
            Activity nearest = findNearestActivity(current, remaining, placeMap);
            remaining.remove(nearest);
            sorted.add(nearest);
            current = nearest;
        }

        return sorted;
    }

    private Activity findNearestActivity(Activity current, List<Activity> candidates, Map<String, Place> placeMap) {
        Place currentPlace = getPlace(current, placeMap);
        if (currentPlace == null) {
            return candidates.get(0);
        }

        Activity nearest = candidates.get(0);
        double minDistance = Double.MAX_VALUE;

        for (Activity candidate : candidates) {
            Place candidatePlace = getPlace(candidate, placeMap);

            if (candidatePlace == null) {
                continue;
            }

            double distance = distanceKm(
                    currentPlace.getLatitude(),
                    currentPlace.getLongitude(),
                    candidatePlace.getLatitude(),
                    candidatePlace.getLongitude()
            );

            if (distance < minDistance) {
                minDistance = distance;
                nearest = candidate;
            }
        }

        return nearest;
    }

    private void reassignSuggestedTimes(List<Activity> activities, Map<String, Place> placeMap, String strategy) {
        int morningHour = 8;
        int afternoonHour = 13;
        int eveningHour = 18;
        int generalHour = 8;

        for (Activity activity : activities) {
            Place place = getPlace(activity, placeMap);

            if ("morning_temple".equals(strategy) && isTemple(place, activity)) {
                activity.setActivityTime(formatHour(morningHour++));
                continue;
            }

            if ("evening_food".equals(strategy) && isFood(place, activity)) {
                activity.setActivityTime(formatHour(eveningHour++));
                continue;
            }

            if ("smart".equals(strategy)) {
                if (isTemple(place, activity)) {
                    activity.setActivityTime(formatHour(morningHour++));
                } else if (isFood(place, activity)) {
                    activity.setActivityTime(formatHour(eveningHour++));
                } else {
                    activity.setActivityTime(formatHour(generalHour++));
                }
                continue;
            }

            if ("traffic".equals(strategy)) {
                if (isTemple(place, activity) || isNature(place, activity) || isHistorical(place, activity)) {
                    activity.setActivityTime(formatHour(morningHour++));
                } else if (isFood(place, activity)) {
                    activity.setActivityTime(formatHour(eveningHour++));
                } else {
                    activity.setActivityTime(formatHour(afternoonHour++));
                }
                continue;
            }

            if ("low_budget".equals(strategy) || "distance".equals(strategy) || "nearest".equals(strategy)) {
                activity.setActivityTime(formatHour(generalHour++));
            }
        }
    }

    private String formatHour(int hour24) {
        int normalizedHour = ((hour24 - 1) % 12) + 1;
        String suffix = hour24 >= 12 ? "PM" : "AM";
        return String.format("%02d:00 %s", normalizedHour, suffix);
    }

    private Place getPlace(Activity activity, Map<String, Place> placeMap) {
        if (activity == null || activity.getPlaceId() == null || activity.getPlaceId().isBlank()) {
            return null;
        }
        return placeMap.get(activity.getPlaceId());
    }

    private boolean isTemple(Place place, Activity activity) {
        String category = place != null ? safeLower(place.getCategory()) : "";
        String title = activity != null ? safeLower(activity.getTitle()) : "";
        return category.contains("religious")
                || category.contains("temple")
                || title.contains("temple")
                || title.contains("mandir")
                || title.contains("ganesh")
                || title.contains("mahadev");
    }

    private boolean isFood(Place place, Activity activity) {
        String category = place != null ? safeLower(place.getCategory()) : "";
        String title = activity != null ? safeLower(activity.getTitle()) : "";
        String icon = activity != null ? safeLower(activity.getIcon()) : "";
        return category.contains("food")
                || category.contains("restaurant")
                || category.contains("cafe")
                || title.contains("food")
                || title.contains("lunch")
                || title.contains("dinner")
                || title.contains("breakfast")
                || title.contains("cafe")
                || icon.contains("food");
    }

    private boolean isNature(Place place, Activity activity) {
        String category = place != null ? safeLower(place.getCategory()) : "";
        String title = activity != null ? safeLower(activity.getTitle()) : "";
        return category.contains("nature")
                || category.contains("park")
                || title.contains("park")
                || title.contains("waterfall")
                || title.contains("lake");
    }

    private boolean isHistorical(Place place, Activity activity) {
        String category = place != null ? safeLower(place.getCategory()) : "";
        String title = activity != null ? safeLower(activity.getTitle()) : "";
        return category.contains("historical")
                || category.contains("museum")
                || title.contains("palace")
                || title.contains("fort")
                || title.contains("museum");
    }

    private String safeLower(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private int parseDurationMinutes(String duration) {
        if (duration == null || duration.isBlank()) {
            return Integer.MAX_VALUE;
        }

        String value = duration.trim().toLowerCase();

        try {
            if (value.contains("hr")) {
                String number = value.replaceAll("[^0-9]", "");
                if (number.isBlank()) {
                    return 60;
                }
                return Integer.parseInt(number) * 60;
            }

            if (value.contains("min")) {
                String number = value.replaceAll("[^0-9]", "");
                if (number.isBlank()) {
                    return 30;
                }
                return Integer.parseInt(number);
            }
        } catch (Exception ignored) {
        }

        return Integer.MAX_VALUE;
    }

    private double distanceKm(Double lat1, Double lon1, Double lat2, Double lon2) {
        if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) {
            return Double.MAX_VALUE;
        }

        final double earthRadius = 6371.0;

        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return earthRadius * c;
    }

    private Comparator<Activity> activityTimeComparator() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH);
        return Comparator.comparing(activity -> {
            try {
                String time = activity.getActivityTime();
                if (time == null || time.isBlank()) {
                    return LocalTime.MAX;
                }
                return LocalTime.parse(time.trim().toUpperCase(), formatter);
            } catch (Exception e) {
                return LocalTime.MAX;
            }
        });
    }

    private String nextBreakTime(int currentActivitiesSize) {
        return switch (currentActivitiesSize) {
            case 2 -> "01:00 PM";
            case 4 -> "05:00 PM";
            default -> "01:00 PM";
        };
    }
}