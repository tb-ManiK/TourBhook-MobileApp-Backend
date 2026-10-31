package com.tourbhook.api.dto.community;

import jakarta.validation.constraints.NotBlank;

public record SendMessageRequest(
        @NotBlank(message = "content is required")
        String content
) {
}