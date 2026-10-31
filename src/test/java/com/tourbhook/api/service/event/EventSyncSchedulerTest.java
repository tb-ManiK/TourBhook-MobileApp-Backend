package com.tourbhook.api.service.event;

import com.tourbhook.api.config.EventProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;
import java.lang.reflect.Method;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EventSyncSchedulerTest {

    @Mock private EventSyncService syncService;

    private final EventProperties properties = new EventProperties();
    private EventSyncScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new EventSyncScheduler(syncService, properties);
    }

    /** The point of the feature: the refresh and the clean-up are really scheduled, not one-off. */
    @Test
    void theRefreshAndThePurgeAreRegisteredAsScheduledJobs() throws NoSuchMethodException {
        Scheduled refresh = scheduledOn("refreshEvents");
        Scheduled purge = scheduledOn("purgeEndedEvents");

        assertThat(refresh.cron()).contains("events.sync.cron");
        assertThat(purge.cron()).contains("events.sync.purge-cron");
        assertThat(refresh.zone()).contains("events.zone");
        assertThat(purge.zone()).contains("events.zone");
    }

    @Test
    void theDefaultSchedulesAreValidSixFieldCronExpressions() {
        assertThat(properties.getSync().getCron().split(" ")).hasSize(6);
        assertThat(properties.getSync().getPurgeCron().split(" ")).hasSize(6);
        assertThat(properties.getSync().getCron()).isEqualTo("0 0 */6 * * *");
    }

    @Test
    void theScheduledMethodsDelegateToTheSyncService() {
        scheduler.refreshEvents();
        scheduler.purgeEndedEvents();

        verify(syncService).refreshAll();
        verify(syncService).purgeEnded();
    }

    @Test
    void onStartup_runsOneBackgroundRefresh_whenEnabled() {
        scheduler.refreshOnStartup();

        verify(syncService, timeout(2000)).refreshAll();
    }

    @Test
    void onStartup_doesNothing_whenSwitchedOff() throws InterruptedException {
        properties.getSync().setRunOnStartup(false);

        scheduler.refreshOnStartup();
        Thread.sleep(200);

        verify(syncService, never()).refreshAll();
    }

    private Scheduled scheduledOn(String methodName) throws NoSuchMethodException {
        Method method = EventSyncScheduler.class.getMethod(methodName);
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        assertThat(scheduled).as("@Scheduled on " + methodName).isNotNull();
        return scheduled;
    }
}