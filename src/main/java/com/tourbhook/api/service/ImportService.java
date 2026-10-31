package com.tourbhook.api.service;

import com.tourbhook.api.dto.dataimport.ImportCommitResponse;
import com.tourbhook.api.dto.dataimport.ImportDraftResponse;
import com.tourbhook.api.dto.dataimport.ImportEmailRequest;
import org.springframework.web.multipart.MultipartFile;

public interface ImportService {

    /** Parses a booking confirmation email into an itinerary draft owned by the current user. */
    ImportDraftResponse importFromEmail(ImportEmailRequest request);

    /** Parses an uploaded CSV file into an itinerary draft owned by the current user. */
    ImportDraftResponse importFromFile(MultipartFile file, String city);

    ImportDraftResponse getDraft(String draftId);

    /** Turns a draft into a real trip with an itinerary and returns where to find them. */
    ImportCommitResponse commitDraft(String draftId);

    void discardDraft(String draftId);
}