package com.tourbhook.api.controller;

import com.tourbhook.api.dto.common.ApiResponse;
import com.tourbhook.api.dto.venue.VenueSuggestionResponse;
import com.tourbhook.api.service.VenueSuggestionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDateTime;


@RestController
@RequestMapping("/api/v1/venues")
@RequiredArgsConstructor
public class VenueController {

    private final VenueSuggestionService venueSuggestionService;

    @GetMapping("/nearest-top-rated")
    public ResponseEntity<ApiResponse<VenueSuggestionResponse>> nearestTopRated(
            @RequestParam Double lat,
            @RequestParam Double lng,
            @RequestParam(required = false) Integer radius,
            @RequestParam(defaultValue = "true") boolean openNow,
            HttpServletRequest request) {
        VenueSuggestionResponse suggestion =
                venueSuggestionService.suggestNearestTopRatedVenue(lat, lng, radius, openNow);

        return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.<VenueSuggestionResponse>builder()
                .success(true)
                .message("Venue suggestion fetched")
                .data(suggestion)
                .timestamp(LocalDateTime.now())
                .path(request.getRequestURI())
                .build());
    }
}