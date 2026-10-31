package com.tourbhook.api.service.event;

import com.tourbhook.api.entity.LocalEvent;
import com.tourbhook.api.repository.LocalEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class EventStore {

    private static final int MAX_TITLE = 300;
    private static final int MAX_DESCRIPTION = 1000;
    private static final int MAX_CATEGORY = 60;
    private static final int MAX_VENUE = 200;
    private static final int MAX_ADDRESS = 500;
    private static final int MAX_CITY = 120;
    private static final int MAX_URL = 2000;
    private static final int MAX_EXTERNAL_ID = 300;

    private final LocalEventRepository repository;

    public record UpsertResult(int created, int updated) {
    }

    @Transactional
    public UpsertResult upsert(String source, SyncArea area, List<ExternalEvent> incoming, LocalDateTime syncStartedAt) {
        Map<String, ExternalEvent> byExternalId = new LinkedHashMap<>();
        for (ExternalEvent event : incoming) {
            if (isUsable(event)) {
                byExternalId.putIfAbsent(truncate(event.externalId().trim(), MAX_EXTERNAL_ID), event);
            }
        }
        if (byExternalId.isEmpty()) {
            return new UpsertResult(0, 0);
        }

        Map<String, LocalEvent> known = repository.findBySourceAndExternalIdIn(source, byExternalId.keySet()).stream()
                .collect(Collectors.toMap(LocalEvent::getExternalId, Function.identity(), (a, b) -> a));

        List<LocalEvent> toSave = new ArrayList<>();
        int created = 0;
        for (Map.Entry<String, ExternalEvent> entry : byExternalId.entrySet()) {
            LocalEvent event = known.get(entry.getKey());
            if (event == null) {
                event = new LocalEvent();
                event.setSource(source);
                event.setExternalId(entry.getKey());
                created++;
            }
            apply(event, entry.getValue(), area, syncStartedAt);
            toSave.add(event);
        }
        repository.saveAll(toSave);
        return new UpsertResult(created, toSave.size() - created);
    }

    @Transactional
    public int deactivateUnseen(String source, SyncArea area, LocalDateTime syncStartedAt, LocalDate today) {
        return repository.deactivateUnseen(source, area.key(), syncStartedAt, today);
    }

    @Transactional
    public int purgeEndedBefore(LocalDate cutoff) {
        return repository.deleteEndedBefore(cutoff);
    }

    // ------------------------------------------------------------------ helpers

    private boolean isUsable(ExternalEvent event) {
        return event.externalId() != null && !event.externalId().isBlank()
                && event.title() != null && !event.title().isBlank()
                && event.startDate() != null;
    }

    private void apply(LocalEvent target, ExternalEvent source, SyncArea area, LocalDateTime syncStartedAt) {
        target.setTitle(truncate(source.title().trim(), MAX_TITLE));
        target.setDescription(truncate(source.description(), MAX_DESCRIPTION));
        target.setCategory(truncate(source.category(), MAX_CATEGORY));
        target.setVenueName(truncate(source.venueName(), MAX_VENUE));
        target.setAddress(truncate(source.address(), MAX_ADDRESS));
        target.setCity(truncate(area.city().trim(), MAX_CITY));
        target.setCityKey(truncate(area.key(), MAX_CITY));

        // An event with no usable position of its own is placed at the area's centre, which keeps
        // it findable by a radius search around that city.
        boolean hasPosition = isValidPosition(source.latitude(), source.longitude());
        target.setLatitude(hasPosition ? source.latitude() : area.latitude());
        target.setLongitude(hasPosition ? source.longitude() : area.longitude());

        target.setStartDate(source.startDate());
        target.setStartTime(source.startTime());
        target.setEndDate(source.endDate() == null || source.endDate().isBefore(source.startDate())
                ? source.startDate() : source.endDate());

        target.setUrl(webUrl(source.url()));
        target.setImageUrl(webUrl(source.imageUrl()));
        target.setActive(true);
        target.setLastSyncedAt(syncStartedAt);
    }

    private boolean isValidPosition(Double latitude, Double longitude) {
        return latitude != null && longitude != null
                && Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
    }

    private String webUrl(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        boolean web = trimmed.regionMatches(true, 0, "https://", 0, 8) || trimmed.regionMatches(true, 0, "http://", 0, 7);
        return web ? truncate(trimmed, MAX_URL) : null;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}