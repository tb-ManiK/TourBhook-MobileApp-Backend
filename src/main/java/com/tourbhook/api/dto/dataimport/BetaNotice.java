package com.tourbhook.api.dto.dataimport;

public record BetaNotice(boolean beta, String label, String message, String feedbackEndpoint) {
}