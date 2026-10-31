package com.tourbhook.api.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourbhook.api.config.DataImportProperties;
import com.tourbhook.api.dto.dataimport.DraftActivity;
import com.tourbhook.api.dto.dataimport.ImportCommitResponse;
import com.tourbhook.api.dto.dataimport.ImportDraftResponse;
import com.tourbhook.api.dto.dataimport.ImportEmailRequest;
import com.tourbhook.api.dto.trip.CreateTripRequest;
import com.tourbhook.api.dto.trip.TripSummaryResponse;
import com.tourbhook.api.entity.Activity;
import com.tourbhook.api.entity.ImportDraft;
import com.tourbhook.api.entity.ImportDraftStatus;
import com.tourbhook.api.entity.Itinerary;
import com.tourbhook.api.entity.ItineraryDay;
import com.tourbhook.api.entity.Trip;
import com.tourbhook.api.entity.User;
import com.tourbhook.api.repository.ImportDraftRepository;
import com.tourbhook.api.repository.ItineraryRepository;
import com.tourbhook.api.repository.TripRepository;
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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImportEndToEndTest {

    private static final String CONFIRMATION_EMAIL = """
            Subject: Your bookings are confirmed

            Booking Reference: XY12AB
            Airline: IndiGo
            Flight: 6E 204
            From: Chennai (MAA)
            To: Udaipur (UDR)
            Departure: 12 Nov 2026 06:30
            Arrival: 12 Nov 2026 08:45

            Hotel: Lake View Residency
            City: Udaipur
            Check-in: 12 Nov 2026
            Check-out: 14 Nov 2026
            Total: INR 22,500
            """;

    @Mock private ImportDraftRepository importDraftRepository;
    @Mock private AuthenticatedUserService authenticatedUserService;
    @Mock private TripService tripService;
    @Mock private TripRepository tripRepository;
    @Mock private ItineraryRepository itineraryRepository;

    private final AtomicReference<ImportDraft> storedDraft = new AtomicReference<>();
    private final DataImportProperties properties = new DataImportProperties();
    private ImportServiceImpl service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new ImportServiceImpl(importDraftRepository, authenticatedUserService, tripService, tripRepository,
                itineraryRepository, new ObjectMapper().findAndRegisterModules(),
                List.of(new ConfirmationEmailParser(), new CsvItineraryParser()),
                new ItineraryDraftMapper(), properties, new ImportCatalog(properties));
        user = User.builder().id("u1").build();

        lenient().when(authenticatedUserService.getCurrentUser()).thenReturn(user);
        // a one-row "database" for drafts
        lenient().when(importDraftRepository.save(any(ImportDraft.class))).thenAnswer(call -> {
            ImportDraft draft = call.getArgument(0);
            if (draft.getId() == null) {
                draft.setId("draft-1");
            }
            storedDraft.set(draft);
            return draft;
        });
        lenient().when(importDraftRepository.findByIdAndUser(any(), any())).thenAnswer(call -> Optional.ofNullable(storedDraft.get()));
        lenient().when(importDraftRepository.findByIdAndUserForUpdate(any(), any())).thenAnswer(call -> Optional.ofNullable(storedDraft.get()));
        lenient().when(tripService.createTrip(any(CreateTripRequest.class))).thenAnswer(call -> {
            CreateTripRequest request = call.getArgument(0);
            return new TripSummaryResponse("trip-1", request.city(), null, request.start_date(), request.end_date(),
                    0, 3, 1, 0, "Balanced", "general");
        });
        lenient().when(tripRepository.findById("trip-1")).thenReturn(Optional.of(Trip.builder().id("trip-1").build()));
        lenient().when(itineraryRepository.save(any(Itinerary.class))).thenAnswer(call -> {
            Itinerary itinerary = call.getArgument(0);
            itinerary.setId("it-1");
            return itinerary;
        });
    }

    private Itinerary savedItinerary() {
        ArgumentCaptor<Itinerary> captor = ArgumentCaptor.forClass(Itinerary.class);
        verify(itineraryRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void confirmationEmail_toDraft_toReviewedTrip_andItineraryInTheExistingModel() {
        // 1. the user pastes the email and gets a reviewable draft, flagged as beta
        ImportDraftResponse created = service.importFromEmail(new ImportEmailRequest(CONFIRMATION_EMAIL, null));

        assertThat(created.status()).isEqualTo(ImportDraftStatus.DRAFT);
        assertThat(created.beta().beta()).isTrue();
        assertThat(created.draft().city()).isEqualTo("Udaipur");
        assertThat(created.draft().startDate()).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(created.draft().endDate()).isEqualTo(LocalDate.of(2026, 11, 14));
        assertThat(created.draft().days()).hasSize(3);

        // 2. they open it again later: same content
        assertThat(service.getDraft("draft-1").draft()).isEqualTo(created.draft());

        // 3. they commit it
        ImportCommitResponse committed = service.commitDraft("draft-1");

        assertThat(committed.tripId()).isEqualTo("trip-1");
        assertThat(committed.itineraryId()).isEqualTo("it-1");
        assertThat(committed.days()).isEqualTo(3);
        assertThat(committed.beta().feedbackEndpoint()).isEqualTo("/api/v1/imports/feedback");
        assertThat(storedDraft.get().getStatus()).isEqualTo(ImportDraftStatus.COMMITTED);
        assertThat(storedDraft.get().getCommittedTripId()).isEqualTo("trip-1");

        // the trip was created through the normal trip service with the imported dates
        ArgumentCaptor<CreateTripRequest> tripRequest = ArgumentCaptor.forClass(CreateTripRequest.class);
        verify(tripService).createTrip(tripRequest.capture());
        assertThat(tripRequest.getValue().city()).isEqualTo("Udaipur");
        assertThat(tripRequest.getValue().start_date()).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(tripRequest.getValue().end_date()).isEqualTo(LocalDate.of(2026, 11, 14));

        // and the itinerary uses the app's own Itinerary / ItineraryDay / Activity entities
        Itinerary itinerary = savedItinerary();
        assertThat(itinerary.getCity()).isEqualTo("Udaipur");
        assertThat(itinerary.getDays()).extracting(ItineraryDay::getDayNumber).containsExactly(1, 2, 3);

        List<Activity> day1 = itinerary.getDays().get(0).getActivities();
        assertThat(day1).extracting(Activity::getTitle).containsExactly(
                "IndiGo 6E 204: Chennai to Udaipur", "Hotel check-in: Lake View Residency");
        assertThat(day1).extracting(Activity::getActivityTime).containsExactly("06:30 AM", "02:00 PM");
        assertThat(day1).extracting(Activity::getIcon).containsExactly("flight", "hotel");
        assertThat(day1.get(0).getDuration()).isEqualTo("135 min");
        assertThat(day1.get(0).getCost()).isEqualByComparingTo("22500");

        assertThat(itinerary.getDays().get(1).getActivities()).isEmpty();
        assertThat(itinerary.getDays().get(2).getActivities()).extracting(Activity::getTitle)
                .containsExactly("Hotel check-out: Lake View Residency");
    }

    @Test
    void csvTemplate_goesThroughTheSameJourney() {
        byte[] file = new ImportCatalog(properties).csvTemplate().getBytes(StandardCharsets.UTF_8);

        ImportDraftResponse created = service.importFromFile(new MockMultipartFile("file", "plan.csv", "text/csv", file), null);
        ImportCommitResponse committed = service.commitDraft(created.id());

        assertThat(created.draft().warnings()).isEmpty();
        assertThat(committed.days()).isEqualTo(3);
        Itinerary itinerary = savedItinerary();
        assertThat(itinerary.getDays().stream().mapToInt(day -> day.getActivities().size()).sum()).isEqualTo(4);
        assertThat(itinerary.getDays().get(1).getActivities().get(0).getTitle()).isEqualTo("Visit City Palace");
        assertThat(itinerary.getDays().get(1).getActivities().get(0).getCost()).isEqualByComparingTo("700");
    }

    @Test
    void theCommittedItineraryCanBeEditedLikeAnyOther() {
        service.importFromEmail(new ImportEmailRequest(CONFIRMATION_EMAIL, null));
        service.commitDraft("draft-1");

        Itinerary itinerary = savedItinerary();
        itinerary.getDays().get(1).getActivities().add(Activity.builder().title("Added later").build());
        itinerary.getDays().get(0).getActivities().remove(0);

        assertThat(itinerary.getDays().get(1).getActivities()).hasSize(1);
        assertThat(itinerary.getDays().get(0).getActivities()).hasSize(1);
    }

    @Test
    void theDraftKeepsEveryActivityTheItineraryWillGet() {
        ImportDraftResponse created = service.importFromEmail(new ImportEmailRequest(CONFIRMATION_EMAIL, null));
        service.commitDraft("draft-1");

        int draftActivities = created.draft().days().stream().mapToInt(day -> day.activities().size()).sum();
        int itineraryActivities = savedItinerary().getDays().stream().mapToInt(day -> day.getActivities().size()).sum();
        List<String> draftTitles = created.draft().days().stream()
                .flatMap(day -> day.activities().stream()).map(DraftActivity::title).toList();

        assertThat(itineraryActivities).isEqualTo(draftActivities).isEqualTo(3);
        assertThat(draftTitles).hasSize(3);
    }
}