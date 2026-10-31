package com.tourbhook.api.controller;

import com.tourbhook.api.dto.common.ApiResponse;
import com.tourbhook.api.dto.dataimport.ImportCommitResponse;
import com.tourbhook.api.dto.dataimport.ImportDraftResponse;
import com.tourbhook.api.dto.dataimport.ImportEmailRequest;
import com.tourbhook.api.service.ImportService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1/imports")
@RequiredArgsConstructor
public class ImportController {

    private final ImportService importService;

    @PostMapping("/email")
    public ResponseEntity<ApiResponse<ImportDraftResponse>> importFromEmail(
            @Valid @RequestBody ImportEmailRequest body,
            HttpServletRequest request) {
        return respond(HttpStatus.CREATED, "Import draft created from confirmation email",
                importService.importFromEmail(body), request);
    }

    @PostMapping(value = "/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ImportDraftResponse>> importFromFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "city", required = false) String city,
            HttpServletRequest request) {
        return respond(HttpStatus.CREATED, "Import draft created from file",
                importService.importFromFile(file, city), request);
    }

    @GetMapping("/{draftId}")
    public ResponseEntity<ApiResponse<ImportDraftResponse>> getDraft(
            @PathVariable String draftId,
            HttpServletRequest request) {
        return respond(HttpStatus.OK, "Import draft fetched", importService.getDraft(draftId), request);
    }

    @PostMapping("/{draftId}/commit")
    public ResponseEntity<ApiResponse<ImportCommitResponse>> commitDraft(
            @PathVariable String draftId,
            HttpServletRequest request) {
        return respond(HttpStatus.CREATED, "Trip created from import", importService.commitDraft(draftId), request);
    }

    @DeleteMapping("/{draftId}")
    public ResponseEntity<ApiResponse<Void>> discardDraft(
            @PathVariable String draftId,
            HttpServletRequest request) {
        importService.discardDraft(draftId);
        return this.<Void>respond(HttpStatus.OK, "Import draft discarded", null, request);
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