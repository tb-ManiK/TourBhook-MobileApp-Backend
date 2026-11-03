package com.tourbhook.api.service.image;

import java.util.List;
import java.util.Locale;

final class ImageUrlRules {

    static final String PLACE_IMAGE_PATH = "/api/v1/images/places/";
    static final String DEFAULT_IMAGE_PATH = "/api/v1/images/default";

    private static final int MAX_URL_LENGTH = 2000;

    private static final List<String> UNUSABLE_MARKERS = List.of(
            "via.placeholder.com",
            "maps.googleapis.com/maps/api/place/photo",
            "cdn.tourbhook.local"
    );

    private ImageUrlRules() {
    }

    static boolean isUsable(String url) {
        if (url == null) {
            return false;
        }
        String trimmed = url.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_URL_LENGTH) {
            return false;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("https://")) {
            return false;
        }
        for (String marker : UNUSABLE_MARKERS) {
            if (lower.contains(marker)) {
                return false;
            }
        }
        return true;
    }

    static String normalizeBase(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    static String placeImageUrl(String baseUrl, String placeId) {
        return normalizeBase(baseUrl) + PLACE_IMAGE_PATH + placeId;
    }

    static String defaultImageUrl(String baseUrl) {
        return normalizeBase(baseUrl) + DEFAULT_IMAGE_PATH;
    }
}