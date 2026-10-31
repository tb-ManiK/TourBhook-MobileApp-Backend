package com.tourbhook.api.dto.community;

import com.tourbhook.api.entity.GroupMemberRole;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record GroupResponse(
        String id,
        String name,
        String description,
        String city,
        LocalDate startDate,
        LocalDate endDate,
        int maxMembers,
        long memberCount,
        GroupMemberRole myRole,
        LocalDateTime createdAt
) {
}