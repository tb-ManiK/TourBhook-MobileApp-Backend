package com.tourbhook.api.dto.dataimport;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ImportEmailRequest(
        @NotBlank(message = "rawEmail is required")
        @Size(max = ImportEmailRequest.MAX_RAW_EMAIL_LENGTH, message = "rawEmail is too long")
        String rawEmail,

        @Size(max = 120, message = "city must be at most 120 characters")
        String city
) {

        public static final int MAX_RAW_EMAIL_LENGTH = 200_000;
}