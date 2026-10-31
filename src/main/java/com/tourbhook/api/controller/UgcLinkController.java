package com.tourbhook.api.controller;

import com.tourbhook.api.dto.common.ApiResponse;
import com.tourbhook.api.dto.ugc.SubmitUgcLinkRequest;
import com.tourbhook.api.dto.ugc.UgcLinkResponse;
import com.tourbhook.api.service.UgcLinkService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/ugc-links")
@RequiredArgsConstructor
public class UgcLinkController {

    private final UgcLinkService ugcLinkService;

    @PostMapping
    public ResponseEntity<ApiResponse<UgcLinkResponse>> submitLink(
            @Valid @RequestBody SubmitUgcLinkRequest body,
            HttpServletRequest request) {
        return respond(HttpStatus.CREATED, "Link shared", ugcLinkService.submitLink(body), request);
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<UgcLinkResponse>>> getLinksForDestination(
            @RequestParam String destination,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return respond(HttpStatus.OK, "Links fetched",
                ugcLinkService.getLinksForDestination(destination, page, size), request);
    }

    @GetMapping("/mine")
    public ResponseEntity<ApiResponse<List<UgcLinkResponse>>> getMyLinks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return respond(HttpStatus.OK, "Your links fetched", ugcLinkService.getMyLinks(page, size), request);
    }

    @DeleteMapping("/{linkId}")
    public ResponseEntity<ApiResponse<Void>> removeLink(
            @PathVariable String linkId,
            HttpServletRequest request) {
        ugcLinkService.removeLink(linkId);
        return this.<Void>respond(HttpStatus.OK, "Link removed", null, request);
    }

    private <T> ResponseEntity<ApiResponse<T>> respond(HttpStatus status, String message, T data,
                                                       HttpServletRequest request) {
        return ResponseEntity.status(status).body(ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .data(data)
                .timestamp(LocalDateTime.now())
                .path(request.getRequestURI())
                .build());
    }
}