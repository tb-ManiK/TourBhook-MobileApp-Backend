package com.tourbhook.api.service;

import com.tourbhook.api.dto.venue.VenueSuggestionResponse;

public interface VenueSuggestionService {

    VenueSuggestionResponse suggestNearestTopRatedVenue(Double latitude, Double longitude,
                                                        Integer radiusMeters, boolean openNowOnly);
}