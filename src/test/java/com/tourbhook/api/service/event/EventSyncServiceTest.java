package com.tourbhook.api.service.event;

import com.tourbhook.api.config.EventProperties;
import com.tourbhook.api.repository.exception.ExternalServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventSyncServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T06:30:00Z"), IST);   // 12:00 on 6 Oct
    private static final LocalDateTime STARTED_AT = LocalDateTime.of(2026, 10, 6, 12, 0);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);
    private static final LocalDate UNTIL = TODAY.plusDays(60);

    private static final SyncArea CHENNAI = new SyncArea("Chennai", 13.08, 80.27);
    private static final SyncArea GOA = new SyncArea("Goa", 15.29, 74.12);

    @Mock private EventProvider ticketmaster;
    @Mock private EventProvider ics;
    @Mock private SyncAreaResolver areaResolver;
    @Mock private EventStore eventStore;

    private final EventProperties properties = new EventProperties();
    private EventSyncService service;

    @BeforeEach
    void setUp() {
        service = new EventSyncService(List.of(ticketmaster, ics), areaResolver, eventStore, properties, CLOCK);
    }

    private ExternalEvent anEvent() {
        return new ExternalEvent("a", "Event", null, null, null, null, null, null, TODAY, null, null, null, null);
    }

    private void enabled(EventProvider provider, String name) {
        when(provider.isEnabled()).thenReturn(true);
        when(provider.sourceName()).thenReturn(name);
    }

    @Test
    void refreshAll_fetchesEveryAreaFromEveryEnabledProvider_thenSavesAndRetiresUnseenEvents() {
        enabled(ticketmaster, "ticketmaster");
        enabled(ics, "ics");
        when(areaResolver.resolve(TODAY, UNTIL)).thenReturn(List.of(CHENNAI, GOA));
        when(ticketmaster.fetch(any(), eq(TODAY), eq(UNTIL))).thenReturn(List.of(anEvent()));
        when(ics.fetch(any(), eq(TODAY), eq(UNTIL))).thenReturn(List.of(anEvent()));
        when(eventStore.upsert(any(), any(), any(), eq(STARTED_AT))).thenReturn(new EventStore.UpsertResult(2, 1));
        when(eventStore.deactivateUnseen(any(), any(), eq(STARTED_AT), eq(TODAY))).thenReturn(1);

        EventSyncSummary summary = service.refreshAll().orElseThrow();

        assertThat(summary).isEqualTo(new EventSyncSummary(2, 4, 0, 8, 4, 4));
        verify(eventStore).upsert(eq("ticketmaster"), eq(GOA), any(), eq(STARTED_AT));
        verify(eventStore).deactivateUnseen("ics", CHENNAI, STARTED_AT, TODAY);
    }

    @Test
    void refreshAll_aSourceThatFailsIsSkipped_andNothingIsRetiredForIt() {
        enabled(ticketmaster, "ticketmaster");
        enabled(ics, "ics");
        when(areaResolver.resolve(TODAY, UNTIL)).thenReturn(List.of(CHENNAI));
        when(ticketmaster.fetch(any(), any(), any())).thenThrow(new ExternalServiceException("HTTP 503"));
        when(ics.fetch(any(), any(), any())).thenReturn(List.of(anEvent()));
        when(eventStore.upsert(eq("ics"), any(), any(), any())).thenReturn(new EventStore.UpsertResult(1, 0));

        EventSyncSummary summary = service.refreshAll().orElseThrow();

        assertThat(summary.succeeded()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
        // A failed fetch must never look like "the source removed everything".
        verify(eventStore, never()).deactivateUnseen(eq("ticketmaster"), any(), any(), any());
        verify(eventStore).deactivateUnseen(eq("ics"), any(), any(), any());
    }

    @Test
    void refreshAll_anUnexpectedErrorInOneAreaDoesNotStopTheOthers() {
        enabled(ticketmaster, "ticketmaster");
        when(ics.isEnabled()).thenReturn(false);
        when(areaResolver.resolve(TODAY, UNTIL)).thenReturn(List.of(CHENNAI, GOA));
        when(ticketmaster.fetch(any(), any(), any())).thenReturn(List.of(anEvent()));
        when(eventStore.upsert(any(), eq(CHENNAI), any(), any())).thenThrow(new IllegalStateException("constraint violation"));
        when(eventStore.upsert(any(), eq(GOA), any(), any())).thenReturn(new EventStore.UpsertResult(1, 0));

        EventSyncSummary summary = service.refreshAll().orElseThrow();

        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.succeeded()).isEqualTo(1);
        assertThat(summary.created()).isEqualTo(1);
    }

    @Test
    void refreshAll_withNoEnabledProvider_doesNothing() {
        when(ticketmaster.isEnabled()).thenReturn(false);
        when(ics.isEnabled()).thenReturn(false);

        assertThat(service.refreshAll()).contains(new EventSyncSummary(0, 0, 0, 0, 0, 0));
        verify(areaResolver, never()).resolve(any(), any());
    }

    @Test
    void refreshAll_whenTheAreasCannotBeResolved_endsQuietlyInsteadOfThrowing() {
        when(ticketmaster.isEnabled()).thenReturn(true);
        when(ics.isEnabled()).thenReturn(false);
        when(areaResolver.resolve(any(), any())).thenThrow(new IllegalStateException("database is down"));

        assertThat(service.refreshAll()).isEmpty();
    }

    @Test
    void refreshAll_neverRunsTwiceAtOnce() {
        enabled(ticketmaster, "ticketmaster");
        when(ics.isEnabled()).thenReturn(false);
        when(areaResolver.resolve(TODAY, UNTIL)).thenReturn(List.of(CHENNAI));
        AtomicReference<Optional<EventSyncSummary>> nestedAttempt = new AtomicReference<>();
        when(ticketmaster.fetch(any(), any(), any())).thenAnswer(call -> {
            nestedAttempt.set(service.refreshAll());   // a second run starting while the first is mid-way
            return List.of();
        });
        when(eventStore.upsert(any(), any(), any(), any())).thenReturn(new EventStore.UpsertResult(0, 0));

        assertThat(service.refreshAll()).isPresent();
        assertThat(nestedAttempt.get()).isEmpty();
        assertThat(service.refreshAll()).isPresent();   // and the guard is released afterwards
    }

    @Test
    void purgeEnded_removesEventsThatEndedBeforeTheRetentionCutoff() {
        properties.getSync().setRetentionDays(7);
        when(eventStore.purgeEndedBefore(LocalDate.of(2026, 9, 29))).thenReturn(12);

        assertThat(service.purgeEnded()).isEqualTo(12);
    }

    @Test
    void purgeEnded_aFailureIsAbsorbed() {
        when(eventStore.purgeEndedBefore(any())).thenThrow(new IllegalStateException("db"));

        assertThat(service.purgeEnded()).isZero();
    }
}