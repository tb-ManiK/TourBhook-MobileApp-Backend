package com.tourbhook.api.dto.community;

import java.time.LocalDateTime;

public record ChatMessageResponse(
        String id,
        String groupId,
        String senderId,
        String senderName,
        String senderAvatarUrl,
        String content,
        LocalDateTime createdAt
) {
}