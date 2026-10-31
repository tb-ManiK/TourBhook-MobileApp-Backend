package com.tourbhook.api.controller;

import com.tourbhook.api.dto.common.ApiResponse;
import com.tourbhook.api.dto.event.PersonalizedEventFeedResponse;
import com.tourbhook.api.dto.event.PersonalizedFeedQuery;
import com.tourbhook.api.service.PersonalizedEventFeedService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDate;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class PersonalizedEventController {

    private final PersonalizedEventFeedService personalizedFeedService;

    @GetMapping("/for-me")
    public ResponseEntity<ApiResponse<PersonalizedEventFeedResponse>> getFeedForMe(
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(required = false) Double radiusKm,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "true") boolean includeItinerary,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        PersonalizedFeedQuery query =
                new PersonalizedFeedQuery(lat, lng, radiusKm, from, to, includeItinerary, category, page, size);

        return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.<PersonalizedEventFeedResponse>builder()
                .success(true)
                .message("Personalised events fetched")
                .data(personalizedFeedService.getFeedForMe(query))
                .timestamp(LocalDateTime.now())
                .path(request.getRequestURI())
                .build());
    }
}