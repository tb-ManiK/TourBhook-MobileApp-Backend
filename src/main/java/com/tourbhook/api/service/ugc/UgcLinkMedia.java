package com.tourbhook.api.service.ugc;
import com.tourbhook.api.entity.UgcLinkPlatform;

public final class UgcLinkMedia {

    private static final String INSTAGRAM_PREFIX = "https://www.instagram.com/";

    private UgcLinkMedia() {
    }

    public static String thumbnailUrl(UgcLinkPlatform platform, String externalId) {
        if (platform == UgcLinkPlatform.YOUTUBE && externalId != null && !externalId.isBlank()) {
            return "https://i.ytimg.com/vi/" + externalId + "/hqdefault.jpg";
        }
        return null;
    }

    public static String embedUrl(UgcLinkPlatform platform, String externalId, String url) {
        if (platform == UgcLinkPlatform.YOUTUBE && externalId != null && !externalId.isBlank()) {
            return "https://www.youtube.com/embed/" + externalId;
        }
        if (platform == UgcLinkPlatform.INSTAGRAM && url != null
                && url.startsWith(INSTAGRAM_PREFIX) && !url.contains("/share/") && url.endsWith("/")) {
            return url + "embed/";
        }
        return null;
    }
}