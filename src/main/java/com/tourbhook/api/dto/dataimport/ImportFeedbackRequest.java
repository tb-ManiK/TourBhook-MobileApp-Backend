package com.tourbhook.api.dto.dataimport;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ImportFeedbackRequest(
        @NotNull(message = "rating is required")
        @Min(value = 1, message = "rating must be between 1 and 5")
        @Max(value = 5, message = "rating must be between 1 and 5")
        Integer rating,

        @Size(max = 1000, message = "comment must be at most 1000 characters")
        String comment,

        @Size(max = 36, message = "draftId is not valid")
        String draftId
) {
}