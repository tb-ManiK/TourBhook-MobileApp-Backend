package com.tourbhook.api.dto.dataimport;

import java.time.LocalTime;
import java.util.Locale;
import java.util.Optional;

public enum ImportedItemType {

    FLIGHT("flight", LocalTime.of(9, 0), 120),
    HOTEL_CHECK_IN("hotel", LocalTime.of(14, 0), 30),
    HOTEL_CHECK_OUT("hotel", LocalTime.of(11, 0), 30),
    FOOD("food", LocalTime.of(13, 0), 60),
    ACTIVITY("explore", LocalTime.of(10, 0), 120);

    private final String icon;
    private final LocalTime defaultTime;
    private final int defaultDurationMinutes;

    ImportedItemType(String icon, LocalTime defaultTime, int defaultDurationMinutes) {
        this.icon = icon;
        this.defaultTime = defaultTime;
        this.defaultDurationMinutes = defaultDurationMinutes;
    }

    public String icon() {
        return icon;
    }

    public LocalTime defaultTime() {
        return defaultTime;
    }

    public int defaultDurationMinutes() {
        return defaultDurationMinutes;
    }

    public static Optional<ImportedItemType> fromLabel(String label) {
        if (label == null || label.isBlank()) {
            return Optional.empty();
        }
        String key = label.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        return switch (key) {
            case "flight" -> Optional.of(FLIGHT);
            case "hotel", "hotel_check_in", "check_in", "checkin" -> Optional.of(HOTEL_CHECK_IN);
            case "hotel_check_out", "check_out", "checkout" -> Optional.of(HOTEL_CHECK_OUT);
            case "food", "meal", "restaurant", "dining" -> Optional.of(FOOD);
            case "activity", "sightseeing", "attraction" -> Optional.of(ACTIVITY);
            default -> Optional.empty();
        };
    }
}