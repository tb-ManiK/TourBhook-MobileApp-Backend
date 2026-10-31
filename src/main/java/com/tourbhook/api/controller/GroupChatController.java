package com.tourbhook.api.controller;

import com.tourbhook.api.dto.common.ApiResponse;
import com.tourbhook.api.dto.community.ChatMessageResponse;
import com.tourbhook.api.dto.community.SendMessageRequest;
import com.tourbhook.api.service.AuthenticatedUserService;
import com.tourbhook.api.service.GroupChatService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/v1/community/groups/{groupId}/messages")
@RequiredArgsConstructor
public class GroupChatController {

    private final GroupChatService chatService;
    private final AuthenticatedUserService authenticatedUserService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<ChatMessageResponse>>> getMessages(
            @PathVariable String groupId,
            @RequestParam(required = false) String before,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        String userId = authenticatedUserService.getCurrentUser().getId();
        return respond(HttpStatus.OK, "Messages fetched",
                chatService.getMessages(userId, groupId, before, limit), request);
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ChatMessageResponse>> sendMessage(
            @PathVariable String groupId,
            @Valid @RequestBody SendMessageRequest body,
            HttpServletRequest request) {
        String userId = authenticatedUserService.getCurrentUser().getId();
        return respond(HttpStatus.CREATED, "Message sent",
                chatService.sendMessage(userId, groupId, body.content()), request);
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