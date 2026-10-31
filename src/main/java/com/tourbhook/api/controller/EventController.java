package com.tourbhook.api.controller;

import com.tourbhook.api.dto.common.ApiResponse;
import com.tourbhook.api.dto.event.EventFeedQuery;
import com.tourbhook.api.dto.event.EventFeedResponse;
import com.tourbhook.api.service.EventFeedService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDate;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventController {

    private final EventFeedService eventFeedService;

    @GetMapping
    public ResponseEntity<ApiResponse<EventFeedResponse>> getFeed(
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(required = false) Double radiusKm,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        EventFeedQuery query = new EventFeedQuery(lat, lng, radiusKm, city, from, to, category, page, size);
        return respond("Events fetched", eventFeedService.getFeed(query), request);
    }

    @GetMapping("/trips/{tripId}")
    public ResponseEntity<ApiResponse<EventFeedResponse>> getFeedForTrip(
            @PathVariable String tripId,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return respond("Trip events fetched", eventFeedService.getFeedForTrip(tripId, category, page, size), request);
    }

    private ResponseEntity<ApiResponse<EventFeedResponse>> respond(String message, EventFeedResponse data,
                                                                   HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.<EventFeedResponse>builder()
                .success(true)
                .message(message)
                .data(data)
                .timestamp(LocalDateTime.now())
                .path(request.getRequestURI())
                .build());
    }
}