package com.tourbhook.api.dto.dataimport;

public record ImportCommitResponse(
        String draftId,
        String tripId,
        String itineraryId,
        int days,
        BetaNotice beta
) {
}