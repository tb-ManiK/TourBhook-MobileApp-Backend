package com.tourbhook.api.service.event;

import com.tourbhook.api.config.EventProperties;
import com.tourbhook.api.repository.exception.ExternalServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventSyncService {

    private final List<EventProvider> providers;
    private final SyncAreaResolver areaResolver;
    private final EventStore eventStore;
    private final EventProperties properties;
    private final Clock clock;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public Optional<EventSyncSummary> refreshAll() {
        if (!running.compareAndSet(false, true)) {
            log.warn("Event sync skipped: the previous run is still in progress");
            return Optional.empty();
        }
        try {
            return Optional.of(runRefresh());
        } catch (RuntimeException e) {
            log.error("Event sync aborted unexpectedly", e);
            return Optional.empty();
        } finally {
            running.set(false);
        }
    }

    public int purgeEnded() {
        try {
            LocalDate cutoff = LocalDate.now(clock).minusDays(properties.getSync().getRetentionDays());
            int deleted = eventStore.purgeEndedBefore(cutoff);
            log.info("Event purge removed {} event(s) that ended before {}", deleted, cutoff);
            return deleted;
        } catch (RuntimeException e) {
            log.error("Event purge failed", e);
            return 0;
        }
    }

    private EventSyncSummary runRefresh() {
        List<EventProvider> enabledProviders = providers.stream().filter(EventProvider::isEnabled).toList();
        if (enabledProviders.isEmpty()) {
            log.info("Event sync has nothing to do: no event provider is enabled");
            return EventSyncSummary.EMPTY;
        }

        LocalDateTime startedAt = LocalDateTime.now(clock);
        LocalDate today = startedAt.toLocalDate();
        LocalDate until = today.plusDays(properties.getSync().getLookaheadDays());
        List<SyncArea> areas = areaResolver.resolve(today, until);

        int succeeded = 0;
        int failed = 0;
        int created = 0;
        int updated = 0;
        int deactivated = 0;

        for (SyncArea area : areas) {
            for (EventProvider provider : enabledProviders) {
                try {
                    List<ExternalEvent> events = provider.fetch(area, today, until);
                    EventStore.UpsertResult saved = eventStore.upsert(provider.sourceName(), area, events, startedAt);
                    deactivated += eventStore.deactivateUnseen(provider.sourceName(), area, startedAt, today);
                    created += saved.created();
                    updated += saved.updated();
                    succeeded++;
                } catch (ExternalServiceException e) {
                    failed++;
                    log.warn("Event sync skipped {} for {}: {}", provider.sourceName(), area.city(), e.getMessage());
                } catch (RuntimeException e) {
                    failed++;
                    log.error("Event sync failed for {} / {}", provider.sourceName(), area.city(), e);
                }
            }
        }

        EventSyncSummary summary = new EventSyncSummary(areas.size(), succeeded, failed, created, updated, deactivated);
        log.info("Event sync finished: {}", summary);
        return summary;
    }
}