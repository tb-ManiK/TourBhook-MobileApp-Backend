package com.tourbhook.api.service.event;

import com.tourbhook.api.config.EventProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "events.sync", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EventSyncScheduler {

    private final EventSyncService syncService;
    private final EventProperties properties;

    @Scheduled(cron = "${events.sync.cron:0 0 */6 * * *}", zone = "${events.zone:Asia/Kolkata}")
    public void refreshEvents() {
        syncService.refreshAll();
    }

    @Scheduled(cron = "${events.sync.purge-cron:0 30 3 * * *}", zone = "${events.zone:Asia/Kolkata}")
    public void purgeEndedEvents() {
        syncService.purgeEnded();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void refreshOnStartup() {
        if (properties.getSync().isRunOnStartup()) {
            log.info("Starting the initial event sync in the background");
            CompletableFuture.runAsync(syncService::refreshAll);
        }
    }
}