package com.tourbhook.api.controller;

import com.tourbhook.api.dto.common.ApiResponse;
import com.tourbhook.api.dto.dataimport.ImportFeedbackRequest;
import com.tourbhook.api.dto.dataimport.ImportFeedbackResponse;
import com.tourbhook.api.dto.dataimport.ImportInfoResponse;
import com.tourbhook.api.service.ImportFeedbackService;
import com.tourbhook.api.service.dataimport.ImportCatalog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1/imports")
@RequiredArgsConstructor
public class ImportBetaController {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final ImportCatalog importCatalog;
    private final ImportFeedbackService feedbackService;

    @GetMapping("/info")
    public ResponseEntity<ApiResponse<ImportInfoResponse>> info(HttpServletRequest request) {
        return respond(HttpStatus.OK, "Import info fetched", importCatalog.info(), request);
    }

    @GetMapping("/templates/csv")
    public ResponseEntity<byte[]> csvTemplate() {
        return ResponseEntity.ok()
                .contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("tourbhook-import-template.csv").build().toString())
                .body(importCatalog.csvTemplate().getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/feedback")
    public ResponseEntity<ApiResponse<ImportFeedbackResponse>> submitFeedback(
            @Valid @RequestBody ImportFeedbackRequest body,
            HttpServletRequest request) {
        return respond(HttpStatus.CREATED, "Feedback received", feedbackService.submitFeedback(body), request);
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