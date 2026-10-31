package com.tourbhook.api.service.dataimport;

import com.tourbhook.api.dto.dataimport.DraftActivity;
import com.tourbhook.api.dto.dataimport.DraftDay;
import com.tourbhook.api.dto.dataimport.ImportedItemType;
import com.tourbhook.api.dto.dataimport.ItineraryDraft;
import com.tourbhook.api.repository.exception.BadRequestException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Component
public class ItineraryDraftMapper {

    static final int MAX_TRIP_DAYS = 60;

    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_CITY_LENGTH = 120;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH);

    public ItineraryDraft toDraft(ParseResult parsed, String cityOverride) {
        List<String> warnings = new ArrayList<>(parsed.warnings());
        List<ImportedItem> items = chronological(usableItems(parsed.items(), warnings));

        if (items.isEmpty()) {
            throw new BadRequestException("None of the imported entries could be placed on a date.");
        }

        LocalDate start = items.get(0).date();
        LocalDate end = items.get(items.size() - 1).date();
        long dayCount = ChronoUnit.DAYS.between(start, end) + 1;
        if (dayCount > MAX_TRIP_DAYS) {
            throw new BadRequestException("The imported dates span " + dayCount + " days (" + start + " to " + end
                    + "). Trips can be at most " + MAX_TRIP_DAYS + " days; please check the dates.");
        }

        String city = resolveCity(items, cityOverride);

        TreeMap<LocalDate, List<ImportedItem>> byDate = items.stream()
                .collect(Collectors.groupingBy(ImportedItem::date, TreeMap::new, Collectors.toList()));

        List<DraftDay> days = new ArrayList<>();
        for (int offset = 0; offset < dayCount; offset++) {
            LocalDate date = start.plusDays(offset);
            List<DraftActivity> activities = byDate.getOrDefault(date, List.of()).stream()
                    .map(item -> toActivity(item, warnings))
                    .toList();
            days.add(new DraftDay(offset + 1, date, activities));
        }

        return new ItineraryDraft(city, start, end, List.copyOf(days), List.copyOf(warnings));
    }

    private List<ImportedItem> usableItems(List<ImportedItem> items, List<String> warnings) {
        List<ImportedItem> usable = new ArrayList<>();
        for (ImportedItem item : items.stream().distinct().toList()) {
            if (item.date() == null) {
                warnings.add("Skipped '" + item.title() + "' because it has no date.");
            } else {
                usable.add(item);
            }
        }
        return usable;
    }

    private List<ImportedItem> chronological(List<ImportedItem> items) {
        List<ImportedItem> sorted = new ArrayList<>(items);
        // List.sort is stable, so entries at the same moment keep the order they were imported in.
        sorted.sort(Comparator.comparing(ImportedItem::date).thenComparing(this::effectiveTime));
        return sorted;
    }

    private LocalTime effectiveTime(ImportedItem item) {
        return item.time() != null ? item.time() : item.type().defaultTime();
    }

    private DraftActivity toActivity(ImportedItem item, List<String> warnings) {
        String title = truncate(item.title(), MAX_TITLE_LENGTH);

        LocalTime time = item.time();
        if (time == null) {
            time = item.type().defaultTime();
            warnings.add("No time found for '" + title + "'; assumed " + time.format(TIME_FORMAT) + ".");
        }
        int minutes = item.durationMinutes() != null ? item.durationMinutes() : item.type().defaultDurationMinutes();

        return new DraftActivity(
                time.format(TIME_FORMAT),
                title,
                formatDuration(minutes),
                item.cost() != null ? item.cost() : BigDecimal.ZERO,
                item.type().icon(),
                item.type(),
                item.confirmationCode());
    }

    private String formatDuration(int minutes) {
        return minutes % 60 == 0 ? (minutes / 60) + " hr" : minutes + " min";
    }

    private String resolveCity(List<ImportedItem> chronological, String override) {
        if (override != null && !override.isBlank()) {
            return truncate(override.trim(), MAX_CITY_LENGTH);
        }
        return firstCity(chronological, ImportedItemType.HOTEL_CHECK_IN, ImportedItemType.HOTEL_CHECK_OUT)
                .or(() -> firstCity(chronological, ImportedItemType.FLIGHT))
                .or(() -> firstCity(chronological, ImportedItemType.values()))
                .map(city -> truncate(city, MAX_CITY_LENGTH))
                .orElseThrow(() -> new BadRequestException(
                        "The destination city could not be worked out from the imported data. "
                                + "Please send it explicitly in the 'city' field."));
    }

    private Optional<String> firstCity(List<ImportedItem> items, ImportedItemType... types) {
        Set<ImportedItemType> wanted = EnumSet.copyOf(Arrays.asList(types));
        return items.stream()
                .filter(item -> wanted.contains(item.type()))
                .map(ImportedItem::city)
                .filter(city -> city != null && !city.isBlank())
                .findFirst();
    }

    private String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }
}