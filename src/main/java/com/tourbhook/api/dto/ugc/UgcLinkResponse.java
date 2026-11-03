package com.tourbhook.api.dto.ugc;

import com.tourbhook.api.entity.UgcLinkPlatform;
import java.time.LocalDateTime;

public record UgcLinkResponse(
        String id,
        String destination,
        String placeId,
        UgcLinkPlatform platform,
        String url,
        String title,
        String submittedBy,
        boolean mine,
        LocalDateTime createdAt,
        String thumbnailUrl,
        String embedUrl
) {
}