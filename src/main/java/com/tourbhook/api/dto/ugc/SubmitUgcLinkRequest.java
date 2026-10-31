package com.tourbhook.api.dto.ugc;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SubmitUgcLinkRequest(
        @NotBlank(message = "url is required")
        @Size(max = 2000, message = "url is too long")
        String url,

        @NotBlank(message = "destination is required")
        @Size(max = 120, message = "destination must be at most 120 characters")
        String destination,

        @Size(max = 36, message = "placeId is not valid")
        String placeId,

        @Size(max = 300, message = "title must be at most 300 characters")
        String title
) {
}