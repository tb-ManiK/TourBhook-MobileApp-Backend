package com.tourbhook.api.dto.dataimport;

import com.tourbhook.api.entity.ImportDraftStatus;
import com.tourbhook.api.entity.ImportSourceType;

import java.time.LocalDateTime;

public record ImportDraftResponse(
        String id,
        ImportSourceType sourceType,
        ImportDraftStatus status,
        String committedTripId,
        LocalDateTime createdAt,
        ItineraryDraft draft,
        BetaNotice beta
) {
}