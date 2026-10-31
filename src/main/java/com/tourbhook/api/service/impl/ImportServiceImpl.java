package com.tourbhook.api.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourbhook.api.config.DataImportProperties;
import com.tourbhook.api.dto.dataimport.DraftActivity;
import com.tourbhook.api.dto.dataimport.DraftDay;
import com.tourbhook.api.dto.dataimport.ImportCommitResponse;
import com.tourbhook.api.dto.dataimport.ImportDraftResponse;
import com.tourbhook.api.dto.dataimport.ImportEmailRequest;
import com.tourbhook.api.dto.dataimport.ItineraryDraft;
import com.tourbhook.api.dto.trip.CreateTripRequest;
import com.tourbhook.api.dto.trip.TripSummaryResponse;
import com.tourbhook.api.entity.Activity;
import com.tourbhook.api.entity.ImportDraft;
import com.tourbhook.api.entity.ImportDraftStatus;
import com.tourbhook.api.entity.ImportSourceType;
import com.tourbhook.api.entity.Itinerary;
import com.tourbhook.api.entity.ItineraryDay;
import com.tourbhook.api.entity.Trip;
import com.tourbhook.api.entity.User;
import com.tourbhook.api.repository.ImportDraftRepository;
import com.tourbhook.api.repository.ItineraryRepository;
import com.tourbhook.api.repository.TripRepository;
import com.tourbhook.api.repository.exception.BadRequestException;
import com.tourbhook.api.repository.exception.FeatureUnavailableException;
import com.tourbhook.api.repository.exception.ResourceNotFoundException;
import com.tourbhook.api.service.AuthenticatedUserService;
import com.tourbhook.api.service.ImportService;
import com.tourbhook.api.service.TripService;
import com.tourbhook.api.service.dataimport.ImportCatalog;
import com.tourbhook.api.service.dataimport.ImportParser;
import com.tourbhook.api.service.dataimport.ItineraryDraftMapper;
import com.tourbhook.api.service.dataimport.ParseResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ImportServiceImpl implements ImportService {

    private static final int MAX_CITY_LENGTH = 120;
    private static final char BYTE_ORDER_MARK = '\uFEFF';

    private final ImportDraftRepository importDraftRepository;
    private final AuthenticatedUserService authenticatedUserService;
    private final TripService tripService;
    private final TripRepository tripRepository;
    private final ItineraryRepository itineraryRepository;
    private final ObjectMapper objectMapper;
    private final List<ImportParser> parsers;
    private final ItineraryDraftMapper draftMapper;
    private final DataImportProperties dataImportProperties;
    private final ImportCatalog importCatalog;

    @Override
    public ImportDraftResponse importFromEmail(ImportEmailRequest request) {
        requireEnabled();
        return createDraft(ImportSourceType.CONFIRMATION_EMAIL, request.rawEmail(), request.city());
    }

    @Override
    public ImportDraftResponse importFromFile(MultipartFile file, String city) {
        requireEnabled();
        return createDraft(ImportSourceType.CSV_FILE, readCsv(file), city);
    }

    @Override
    @Transactional(readOnly = true)
    public ImportDraftResponse getDraft(String draftId) {
        User user = authenticatedUserService.getCurrentUser();
        ImportDraft draft = importDraftRepository.findByIdAndUser(draftId, user)
                .orElseThrow(() -> draftNotFound());
        return toResponse(draft, readPayload(draft));
    }

    @Override
    public ImportCommitResponse commitDraft(String draftId) {
        requireEnabled();
        User user = authenticatedUserService.getCurrentUser();
        ImportDraft draft = importDraftRepository.findByIdAndUserForUpdate(draftId, user)
                .orElseThrow(() -> draftNotFound());

        if (draft.getStatus() != ImportDraftStatus.DRAFT) {
            throw new BadRequestException("This import has already been "
                    + draft.getStatus().name().toLowerCase(Locale.ROOT) + ".");
        }

        ItineraryDraft content = readPayload(draft);

        // Reuse the normal trip creation path so imported trips behave like any other
        // (validation, defaults, packing list).
        TripSummaryResponse createdTrip = tripService.createTrip(new CreateTripRequest(
                content.city(), content.startDate(), content.endDate(), 1, 0, null, null, null));
        Trip trip = tripRepository.findById(createdTrip.id())
                .orElseThrow(() -> new IllegalStateException("Trip " + createdTrip.id() + " was not found right after creation"));

        Itinerary itinerary = itineraryRepository.save(Itinerary.builder()
                .trip(trip)
                .city(content.city())
                .title(content.city() + " Imported Itinerary")
                .days(toItineraryDays(content))
                .build());

        draft.setStatus(ImportDraftStatus.COMMITTED);
        draft.setCommittedTripId(trip.getId());
        importDraftRepository.save(draft);

        log.info("Import draft {} committed as trip {} for user {}", draft.getId(), trip.getId(), user.getId());
        return new ImportCommitResponse(draft.getId(), trip.getId(), itinerary.getId(), content.days().size(),
                importCatalog.betaNotice());
    }

    @Override
    public void discardDraft(String draftId) {
        User user = authenticatedUserService.getCurrentUser();
        ImportDraft draft = importDraftRepository.findByIdAndUser(draftId, user)
                .orElseThrow(() -> draftNotFound());

        if (draft.getStatus() == ImportDraftStatus.COMMITTED) {
            throw new BadRequestException("A committed import cannot be discarded. Delete the trip instead.");
        }
        if (draft.getStatus() == ImportDraftStatus.DRAFT) {
            draft.setStatus(ImportDraftStatus.DISCARDED);
            importDraftRepository.save(draft);
        }
    }

    // ------------------------------------------------------------------ creation

    private ImportDraftResponse createDraft(ImportSourceType sourceType, String content, String city) {
        User user = authenticatedUserService.getCurrentUser();

        ParseResult parsed = parserFor(sourceType).parse(content);
        ItineraryDraft itineraryDraft = draftMapper.toDraft(parsed, normaliseCity(city));

        ImportDraft saved = importDraftRepository.save(ImportDraft.builder()
                .user(user)
                .sourceType(sourceType)
                .status(ImportDraftStatus.DRAFT)
                .payloadJson(writePayload(itineraryDraft))
                .build());

        // Counts only: the source text contains personal booking details and must not reach the logs.
        log.info("Import draft {} created from {} for user {}: {} day(s), {} warning(s)",
                saved.getId(), sourceType, user.getId(), itineraryDraft.days().size(), itineraryDraft.warnings().size());
        return toResponse(saved, itineraryDraft);
    }

    private ImportParser parserFor(ImportSourceType sourceType) {
        return parsers.stream()
                .filter(parser -> parser.sourceType() == sourceType)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No import parser registered for " + sourceType));
    }

    private String readCsv(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("A CSV file is required.");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new BadRequestException("Only .csv files are supported.");
        }
        if (file.getSize() > dataImportProperties.getMaxFileSizeBytes()) {
            throw new BadRequestException("The CSV file must be smaller than "
                    + describeSize(dataImportProperties.getMaxFileSizeBytes()) + ".");
        }
        try {
            String text = new String(file.getBytes(), StandardCharsets.UTF_8);
            // Excel prefixes UTF-8 CSVs with a byte-order mark, which would corrupt the first column name.
            return !text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK ? text.substring(1) : text;
        } catch (IOException e) {
            log.error("Failed to read uploaded import file", e);
            throw new BadRequestException("The uploaded file could not be read.");
        }
    }

    /** The kill switch: nothing new can be imported or committed while the feature is off. */
    private void requireEnabled() {
        if (!dataImportProperties.isEnabled()) {
            throw new FeatureUnavailableException("Data import is temporarily unavailable. Please try again later.");
        }
    }

    private String describeSize(long bytes) {
        long kilobyte = 1024;
        long megabyte = kilobyte * 1024;
        if (bytes >= megabyte && bytes % megabyte == 0) {
            return (bytes / megabyte) + " MB";
        }
        return bytes >= kilobyte ? (bytes / kilobyte) + " KB" : bytes + " bytes";
    }

    private String normaliseCity(String city) {
        if (city == null || city.isBlank()) {
            return null;
        }
        String trimmed = city.trim();
        if (trimmed.length() > MAX_CITY_LENGTH) {
            throw new BadRequestException("city must be at most " + MAX_CITY_LENGTH + " characters.");
        }
        return trimmed;
    }

    // ------------------------------------------------------------------ commit helpers

    private List<ItineraryDay> toItineraryDays(ItineraryDraft content) {
        List<ItineraryDay> days = new ArrayList<>();
        for (DraftDay day : content.days()) {
            List<Activity> activities = new ArrayList<>();
            for (DraftActivity activity : day.activities()) {
                activities.add(Activity.builder()
                        .activityTime(activity.time())
                        .title(activity.title())
                        .duration(activity.duration())
                        .cost(activity.cost())
                        .icon(activity.icon())
                        .build());
            }
            days.add(ItineraryDay.builder()
                    .dayNumber(day.dayNumber())
                    .activities(activities)
                    .build());
        }
        return days;
    }

    // ------------------------------------------------------------------ mapping

    private ImportDraftResponse toResponse(ImportDraft draft, ItineraryDraft content) {
        return new ImportDraftResponse(
                draft.getId(),
                draft.getSourceType(),
                draft.getStatus(),
                draft.getCommittedTripId(),
                draft.getCreatedAt(),
                content,
                importCatalog.betaNotice());
    }

    private String writePayload(ItineraryDraft content) {
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise the import draft", e);
        }
    }

    private ItineraryDraft readPayload(ImportDraft draft) {
        try {
            return objectMapper.readValue(draft.getPayloadJson(), ItineraryDraft.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored import draft " + draft.getId() + " is unreadable", e);
        }
    }

    private ResourceNotFoundException draftNotFound() {
        return new ResourceNotFoundException("Import draft not found");
    }
}