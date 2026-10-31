package com.tourbhook.api.service.event;

import com.tourbhook.api.entity.LocalEvent;
import com.tourbhook.api.repository.LocalEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventStoreTest {

    private static final SyncArea CHENNAI = new SyncArea("Chennai", 13.0827, 80.2707);
    private static final LocalDateTime SYNC_START = LocalDateTime.of(2026, 10, 6, 12, 0);

    @Mock private LocalEventRepository repository;

    private EventStore store;

    @BeforeEach
    void setUp() {
        store = new EventStore(repository);
    }

    private ExternalEvent event(String id, String title) {
        return new ExternalEvent(id, title, "desc", "Music", "Arena", "1 Main Rd", 13.1, 80.3,
                LocalDate.of(2026, 11, 12), LocalTime.of(19, 0), LocalDate.of(2026, 11, 13), "https://e.example/1", "https://e.example/1.jpg");
    }

    @SuppressWarnings("unchecked")
    private List<LocalEvent> savedEvents() {
        ArgumentCaptor<Iterable<LocalEvent>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(repository).saveAll(captor.capture());
        List<LocalEvent> saved = new java.util.ArrayList<>();
        captor.getValue().forEach(saved::add);
        return saved;
    }

    @Test
    void upsert_createsNewEventsAndStampsThemWithTheRun() {
        when(repository.findBySourceAndExternalIdIn(eq("ticketmaster"), anyCollection())).thenReturn(List.of());

        EventStore.UpsertResult result = store.upsert("ticketmaster", CHENNAI, List.of(event("a", "Indie Night")), SYNC_START);

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.updated()).isZero();
        LocalEvent saved = savedEvents().get(0);
        assertThat(saved.getSource()).isEqualTo("ticketmaster");
        assertThat(saved.getExternalId()).isEqualTo("a");
        assertThat(saved.getCity()).isEqualTo("Chennai");
        assertThat(saved.getCityKey()).isEqualTo("chennai");
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getLastSyncedAt()).isEqualTo(SYNC_START);
    }

    @Test
    void upsert_updatesAKnownEventInPlace_andReactivatesIt() {
        LocalEvent known = LocalEvent.builder().id("row-1").source("ticketmaster").externalId("a")
                .title("Old title").active(false).build();
        when(repository.findBySourceAndExternalIdIn(eq("ticketmaster"), anyCollection())).thenReturn(List.of(known));

        EventStore.UpsertResult result = store.upsert("ticketmaster", CHENNAI, List.of(event("a", "New title")), SYNC_START);

        assertThat(result.created()).isZero();
        assertThat(result.updated()).isEqualTo(1);
        assertThat(savedEvents().get(0)).isSameAs(known);
        assertThat(known.getId()).isEqualTo("row-1");
        assertThat(known.getTitle()).isEqualTo("New title");
        assertThat(known.isActive()).isTrue();
    }

    @Test
    void upsert_storesAnEventRepeatedInOneAnswerOnce_andIgnoresUnusableOnes() {
        when(repository.findBySourceAndExternalIdIn(eq("ticketmaster"), anyCollection())).thenReturn(List.of());
        ExternalEvent noTitle = new ExternalEvent("b", " ", null, null, null, null, null, null,
                LocalDate.of(2026, 11, 12), null, null, null, null);
        ExternalEvent noDate = new ExternalEvent("c", "No date", null, null, null, null, null, null,
                null, null, null, null, null);

        EventStore.UpsertResult result = store.upsert("ticketmaster", CHENNAI,
                List.of(event("a", "First"), event("a", "Repeat"), noTitle, noDate), SYNC_START);

        assertThat(result.created()).isEqualTo(1);
        assertThat(savedEvents()).extracting(LocalEvent::getTitle).containsExactly("First");
    }

    @Test
    void upsert_withNothingUsable_doesNotTouchTheDatabase() {
        EventStore.UpsertResult result = store.upsert("ics", CHENNAI, List.of(), SYNC_START);

        assertThat(result.created() + result.updated()).isZero();
        verify(repository, never()).saveAll(any());
    }

    @Test
    void upsert_placesAnEventWithoutAPositionAtTheAreaCentre() {
        when(repository.findBySourceAndExternalIdIn(eq("ics"), anyCollection())).thenReturn(List.of());
        ExternalEvent noPosition = new ExternalEvent("a", "Festival", null, null, null, null, null, null,
                LocalDate.of(2026, 11, 12), null, null, null, null);
        ExternalEvent badPosition = new ExternalEvent("b", "Nonsense", null, null, null, null, 123.0, 400.0,
                LocalDate.of(2026, 11, 12), null, null, null, null);

        store.upsert("ics", CHENNAI, List.of(noPosition, badPosition), SYNC_START);

        assertThat(savedEvents()).allSatisfy(saved -> {
            assertThat(saved.getLatitude()).isEqualTo(13.0827);
            assertThat(saved.getLongitude()).isEqualTo(80.2707);
        });
    }

    @Test
    void upsert_keepsAnEventsOwnPosition() {
        when(repository.findBySourceAndExternalIdIn(eq("ticketmaster"), anyCollection())).thenReturn(List.of());

        store.upsert("ticketmaster", CHENNAI, List.of(event("a", "Indie Night")), SYNC_START);

        assertThat(savedEvents().get(0).getLatitude()).isEqualTo(13.1);
    }

    @Test
    void upsert_fixesAMissingOrBackwardsEndDate() {
        when(repository.findBySourceAndExternalIdIn(eq("ics"), anyCollection())).thenReturn(List.of());
        ExternalEvent noEnd = new ExternalEvent("a", "No end", null, null, null, null, null, null,
                LocalDate.of(2026, 11, 12), null, null, null, null);
        ExternalEvent backwards = new ExternalEvent("b", "Backwards", null, null, null, null, null, null,
                LocalDate.of(2026, 11, 12), null, LocalDate.of(2026, 11, 10), null, null);

        store.upsert("ics", CHENNAI, List.of(noEnd, backwards), SYNC_START);

        assertThat(savedEvents()).extracting(LocalEvent::getEndDate)
                .containsOnly(LocalDate.of(2026, 11, 12));
    }

    @Test
    void upsert_dropsLinksThatAreNotWebLinks_andCutsOverlongText() {
        when(repository.findBySourceAndExternalIdIn(eq("ics"), anyCollection())).thenReturn(List.of());
        ExternalEvent risky = new ExternalEvent("a", "t".repeat(500), "d".repeat(5000), "c".repeat(200), null, null,
                null, null, LocalDate.of(2026, 11, 12), null, null, "javascript:alert(1)", "data:image/png;base64,AAAA");
        ExternalEvent upperCaseScheme = new ExternalEvent("b", "Fine", null, null, null, null,
                null, null, LocalDate.of(2026, 11, 12), null, null, "HTTPS://example.org/x", null);

        store.upsert("ics", CHENNAI, List.of(risky, upperCaseScheme), SYNC_START);

        List<LocalEvent> saved = savedEvents();
        assertThat(saved.get(0).getUrl()).isNull();
        assertThat(saved.get(0).getImageUrl()).isNull();
        assertThat(saved.get(0).getTitle()).hasSize(300);
        assertThat(saved.get(0).getDescription()).hasSize(1000);
        assertThat(saved.get(0).getCategory()).hasSize(60);
        assertThat(saved.get(1).getUrl()).isEqualTo("HTTPS://example.org/x");
    }

    @Test
    void deactivateUnseen_isScopedToTheSourceAndTheAreaOfTheRun() {
        when(repository.deactivateUnseen("ics", "chennai", SYNC_START, LocalDate.of(2026, 10, 6))).thenReturn(3);

        assertThat(store.deactivateUnseen("ics", CHENNAI, SYNC_START, LocalDate.of(2026, 10, 6))).isEqualTo(3);
    }
}