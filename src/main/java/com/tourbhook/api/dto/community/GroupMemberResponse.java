package com.tourbhook.api.dto.community;

import com.tourbhook.api.entity.GroupMemberRole;

import java.time.LocalDateTime;

public record GroupMemberResponse(
        String userId,
        String name,
        String avatarUrl,
        GroupMemberRole role,
        LocalDateTime joinedAt
) {
}