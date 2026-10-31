package com.tourbhook.api.dto.event;

public record EventRelevance(EventRelevanceType type, String tripId, String tripCity, Double distanceKm) {
}