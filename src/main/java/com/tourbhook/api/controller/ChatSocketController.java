package com.tourbhook.api.controller;

import com.tourbhook.api.dto.community.ChatErrorResponse;
import com.tourbhook.api.dto.community.SendMessageRequest;
import com.tourbhook.api.repository.exception.BadRequestException;
import com.tourbhook.api.repository.exception.ContentModerationException;
import com.tourbhook.api.repository.exception.ForbiddenOperationException;
import com.tourbhook.api.repository.exception.ResourceNotFoundException;
import com.tourbhook.api.repository.exception.TooManyRequestsException;
import com.tourbhook.api.service.GroupChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.invocation.MethodArgumentResolutionException;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;
import java.security.Principal;


@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatSocketController {

    private final GroupChatService chatService;

    @MessageMapping("/groups/{groupId}/messages")
    public void sendMessage(@DestinationVariable String groupId,
                            @Payload SendMessageRequest request,
                            Principal principal) {
        if (principal == null) {
            throw new ForbiddenOperationException("Not authenticated.");
        }
        // The user id comes from the authenticated STOMP session, never from the payload.
        chatService.sendMessage(principal.getName(), groupId, request.content());
    }

    @MessageExceptionHandler
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public ChatErrorResponse handleException(Exception ex) {
        if (ex instanceof ContentModerationException) {
            return new ChatErrorResponse("ACCOUNT_BLOCKED", ex.getMessage());
        }
        if (ex instanceof ForbiddenOperationException) {
            return new ChatErrorResponse("FORBIDDEN", ex.getMessage());
        }
        if (ex instanceof ResourceNotFoundException) {
            return new ChatErrorResponse("NOT_FOUND", ex.getMessage());
        }
        if (ex instanceof TooManyRequestsException) {
            return new ChatErrorResponse("RATE_LIMITED", ex.getMessage());
        }
        if (ex instanceof BadRequestException) {
            return new ChatErrorResponse("BAD_REQUEST", ex.getMessage());
        }
        if (ex instanceof MessageConversionException || ex instanceof MethodArgumentResolutionException) {
            return new ChatErrorResponse("BAD_REQUEST", "Malformed message. Expected {\"content\": \"...\"}.");
        }
        log.error("Unexpected error while handling a chat message", ex);
        return new ChatErrorResponse("INTERNAL_ERROR", "Something went wrong while sending your message.");
    }
}