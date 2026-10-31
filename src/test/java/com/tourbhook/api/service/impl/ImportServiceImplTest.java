package com.tourbhook.api.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourbhook.api.config.DataImportProperties;
import com.tourbhook.api.dto.dataimport.DraftActivity;
import com.tourbhook.api.dto.dataimport.DraftDay;
import com.tourbhook.api.dto.dataimport.BetaNotice;
import com.tourbhook.api.dto.dataimport.ImportCommitResponse;
import com.tourbhook.api.dto.dataimport.ImportDraftResponse;
import com.tourbhook.api.dto.dataimport.ImportEmailRequest;
import com.tourbhook.api.dto.dataimport.ImportedItemType;
import com.tourbhook.api.dto.dataimport.ItineraryDraft;
import com.tourbhook.api.dto.trip.CreateTripRequest;
import com.tourbhook.api.dto.trip.TripSummaryResponse;
import com.tourbhook.api.entity.ImportDraft;
import com.tourbhook.api.entity.ImportDraftStatus;
import com.tourbhook.api.entity.ImportSourceType;
import com.tourbhook.api.entity.Itinerary;
import com.tourbhook.api.entity.Trip;
import com.tourbhook.api.entity.User;
import com.tourbhook.api.repository.ImportDraftRepository;
import com.tourbhook.api.repository.ItineraryRepository;
import com.tourbhook.api.repository.TripRepository;
import com.tourbhook.api.repository.exception.BadRequestException;
import com.tourbhook.api.repository.exception.FeatureUnavailableException;
import com.tourbhook.api.repository.exception.ResourceNotFoundException;
import com.tourbhook.api.service.AuthenticatedUserService;
import com.tourbhook.api.service.TripService;
import com.tourbhook.api.service.dataimport.ConfirmationEmailParser;
import com.tourbhook.api.service.dataimport.CsvItineraryParser;
import com.tourbhook.api.service.dataimport.ImportCatalog;
import com.tourbhook.api.service.dataimport.ItineraryDraftMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImportServiceImplTest {

    private static final String EMAIL = """
            Airline: IndiGo
            Flight: 6E 204
            From: Chennai
            To: Delhi
            Departure: 12 Nov 2026 06:30
            Arrival: 12 Nov 2026 09:15
            """;

    @Mock private ImportDraftRepository importDraftRepository;
    @Mock private AuthenticatedUserService authenticatedUserService;
    @Mock private TripService tripService;
    @Mock private TripRepository tripRepository;
    @Mock private ItineraryRepository itineraryRepository;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final DataImportProperties properties = new DataImportProperties();

    private ImportServiceImpl service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new ImportServiceImpl(
                importDraftRepository, authenticatedUserService, tripService, tripRepository,
                itineraryRepository, objectMapper,
                List.of(new ConfirmationEmailParser(), new CsvItineraryParser()),
                new ItineraryDraftMapper(), properties, new ImportCatalog(properties));
        user = User.builder().id("user-1").build();
        lenient().when(authenticatedUserService.getCurrentUser()).thenReturn(user);
    }

    private ImportDraft storedDraft(ImportDraftStatus status) throws Exception {
        ItineraryDraft content = new ItineraryDraft(
                "Delhi", LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 12),
                List.of(new DraftDay(1, LocalDate.of(2026, 11, 12), List.of(
                        new DraftActivity("06:30 AM", "IndiGo 6E 204", "165 min", BigDecimal.ZERO,
                                "flight", ImportedItemType.FLIGHT, null)))),
                List.of());
        return ImportDraft.builder()
                .id("draft-1").user(user)
                .sourceType(ImportSourceType.CONFIRMATION_EMAIL)
                .status(status)
                .payloadJson(objectMapper.writeValueAsString(content))
                .build();
    }

    @Test
    void importFromEmail_savesDraftForCurrentUserAndReturnsParsedItinerary() {
        when(importDraftRepository.save(any(ImportDraft.class))).thenAnswer(call -> call.getArgument(0));

        ImportDraftResponse response = service.importFromEmail(new ImportEmailRequest(EMAIL, null));

        ArgumentCaptor<ImportDraft> saved = ArgumentCaptor.forClass(ImportDraft.class);
        verify(importDraftRepository).save(saved.capture());
        assertThat(saved.getValue().getUser()).isSameAs(user);
        assertThat(saved.getValue().getStatus()).isEqualTo(ImportDraftStatus.DRAFT);
        assertThat(response.draft().city()).isEqualTo("Delhi");
        assertThat(response.draft().days().get(0).activities().get(0).time()).isEqualTo("06:30 AM");
    }

    @Test
    void importFromFile_rejectsNonCsvAndEmptyFiles() {
        assertThatThrownBy(() -> service.importFromFile(
                new MockMultipartFile("file", "trip.xlsx", "application/octet-stream", "x".getBytes(StandardCharsets.UTF_8)), null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining(".csv");
        assertThatThrownBy(() -> service.importFromFile(
                new MockMultipartFile("file", "trip.csv", "text/csv", new byte[0]), null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void importFromFile_stripsByteOrderMarkSoTheFirstColumnIsStillRecognised() {
        when(importDraftRepository.save(any(ImportDraft.class))).thenAnswer(call -> call.getArgument(0));
        String csv = "\uFEFFdate,title,city\n2026-11-12,Visit Fort,Jaipur\n";

        ImportDraftResponse response = service.importFromFile(
                new MockMultipartFile("file", "trip.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)), null);

        assertThat(response.draft().city()).isEqualTo("Jaipur");
    }

    @Test
    void getDraft_ofAnotherUsersDraft_looksLikeNotFound() {
        when(importDraftRepository.findByIdAndUser("draft-1", user)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDraft("draft-1")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void commitDraft_createsTripThroughTripServiceThenSavesItineraryAndMarksDraftCommitted() throws Exception {
        ImportDraft draft = storedDraft(ImportDraftStatus.DRAFT);
        Trip trip = Trip.builder().id("trip-1").build();
        when(importDraftRepository.findByIdAndUserForUpdate("draft-1", user)).thenReturn(Optional.of(draft));
        when(tripService.createTrip(any(CreateTripRequest.class))).thenReturn(new TripSummaryResponse(
                "trip-1", "Delhi", null, LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 12),
                0, 1, 1, 0, "Balanced", "general"));
        when(tripRepository.findById("trip-1")).thenReturn(Optional.of(trip));
        when(itineraryRepository.save(any(Itinerary.class))).thenAnswer(call -> {
            Itinerary itinerary = call.getArgument(0);
            itinerary.setId("it-1");
            return itinerary;
        });

        ImportCommitResponse response = service.commitDraft("draft-1");

        ArgumentCaptor<CreateTripRequest> tripRequest = ArgumentCaptor.forClass(CreateTripRequest.class);
        verify(tripService).createTrip(tripRequest.capture());
        assertThat(tripRequest.getValue().city()).isEqualTo("Delhi");
        assertThat(tripRequest.getValue().start_date()).isEqualTo(LocalDate.of(2026, 11, 12));

        ArgumentCaptor<Itinerary> itinerary = ArgumentCaptor.forClass(Itinerary.class);
        verify(itineraryRepository).save(itinerary.capture());
        assertThat(itinerary.getValue().getTrip()).isSameAs(trip);
        assertThat(itinerary.getValue().getDays()).hasSize(1);
        assertThat(itinerary.getValue().getDays().get(0).getActivities().get(0).getTitle()).isEqualTo("IndiGo 6E 204");

        assertThat(draft.getStatus()).isEqualTo(ImportDraftStatus.COMMITTED);
        assertThat(draft.getCommittedTripId()).isEqualTo("trip-1");
        assertThat(response.tripId()).isEqualTo("trip-1");
        assertThat(response.itineraryId()).isEqualTo("it-1");
    }

    @Test
    void commitDraft_alreadyCommitted_createsNothingSecondTime() throws Exception {
        when(importDraftRepository.findByIdAndUserForUpdate("draft-1", user))
                .thenReturn(Optional.of(storedDraft(ImportDraftStatus.COMMITTED)));

        assertThatThrownBy(() -> service.commitDraft("draft-1"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("committed");
        verify(tripService, never()).createTrip(any());
        verify(itineraryRepository, never()).save(any());
    }

    @Test
    void discardDraft_marksDraftDiscarded_butRefusesCommittedOnes() throws Exception {
        ImportDraft open = storedDraft(ImportDraftStatus.DRAFT);
        when(importDraftRepository.findByIdAndUser("draft-1", user)).thenReturn(Optional.of(open));

        service.discardDraft("draft-1");
        assertThat(open.getStatus()).isEqualTo(ImportDraftStatus.DISCARDED);

        when(importDraftRepository.findByIdAndUser("draft-2", user))
                .thenReturn(Optional.of(storedDraft(ImportDraftStatus.COMMITTED)));
        assertThatThrownBy(() -> service.discardDraft("draft-2")).isInstanceOf(BadRequestException.class);
    }

    // ------------------------------------------------------------------ beta flag and kill switch

    @Test
    void everyImportResponseIsFlaggedAsBeta_withTheWayToGiveFeedback() throws Exception {
        when(importDraftRepository.save(any(ImportDraft.class))).thenAnswer(call -> call.getArgument(0));
        ImportDraft draft = storedDraft(ImportDraftStatus.DRAFT);
        Trip trip = Trip.builder().id("trip-1").build();
        when(importDraftRepository.findByIdAndUser("draft-1", user)).thenReturn(Optional.of(draft));
        when(importDraftRepository.findByIdAndUserForUpdate("draft-1", user)).thenReturn(Optional.of(draft));
        when(tripService.createTrip(any(CreateTripRequest.class))).thenReturn(new TripSummaryResponse(
                "trip-1", "Delhi", null, LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 12),
                0, 1, 1, 0, "Balanced", "general"));
        when(tripRepository.findById("trip-1")).thenReturn(Optional.of(trip));
        when(itineraryRepository.save(any(Itinerary.class))).thenAnswer(call -> call.getArgument(0));

        BetaNotice created = service.importFromEmail(new ImportEmailRequest(EMAIL, null)).beta();
        BetaNotice fetched = service.getDraft("draft-1").beta();
        BetaNotice committed = service.commitDraft("draft-1").beta();

        for (BetaNotice notice : List.of(created, fetched, committed)) {
            assertThat(notice.beta()).isTrue();
            assertThat(notice.label()).isEqualTo("Beta");
            assertThat(notice.message()).contains("beta");
            assertThat(notice.feedbackEndpoint()).isEqualTo("/api/v1/imports/feedback");
        }
    }

    @Test
    void whenTheFeatureIsSwitchedOff_noNewImportOrCommitCanStart() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> service.importFromEmail(new ImportEmailRequest(EMAIL, null)))
                .isInstanceOf(FeatureUnavailableException.class);
        assertThatThrownBy(() -> service.importFromFile(
                new MockMultipartFile("file", "trip.csv", "text/csv", "date,title\n2026-11-12,x".getBytes(StandardCharsets.UTF_8)), "Jaipur"))
                .isInstanceOf(FeatureUnavailableException.class);
        assertThatThrownBy(() -> service.commitDraft("draft-1")).isInstanceOf(FeatureUnavailableException.class);

        verify(importDraftRepository, never()).save(any());
        verify(tripService, never()).createTrip(any());
    }

    @Test
    void whenTheFeatureIsSwitchedOff_existingDraftsCanStillBeReadAndDiscarded() throws Exception {
        properties.setEnabled(false);
        ImportDraft draft = storedDraft(ImportDraftStatus.DRAFT);
        when(importDraftRepository.findByIdAndUser("draft-1", user)).thenReturn(Optional.of(draft));

        assertThat(service.getDraft("draft-1").id()).isEqualTo("draft-1");
        service.discardDraft("draft-1");

        assertThat(draft.getStatus()).isEqualTo(ImportDraftStatus.DISCARDED);
    }

    @Test
    void afterTheBetaEnds_theNoticeSaysSoAndDropsTheBetaWording() throws Exception {
        properties.setBeta(false);
        when(importDraftRepository.findByIdAndUser("draft-1", user)).thenReturn(Optional.of(storedDraft(ImportDraftStatus.DRAFT)));

        BetaNotice notice = service.getDraft("draft-1").beta();

        assertThat(notice.beta()).isFalse();
        assertThat(notice.label()).isNull();
        assertThat(notice.message()).isNull();
    }

    @Test
    void importFromFile_enforcesTheConfiguredSizeLimit() {
        properties.setMaxFileSizeBytes(10);

        assertThatThrownBy(() -> service.importFromFile(
                new MockMultipartFile("file", "trip.csv", "text/csv", "date,title\n2026-11-12,A long title".getBytes(StandardCharsets.UTF_8)), null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("10 bytes");
    }
}