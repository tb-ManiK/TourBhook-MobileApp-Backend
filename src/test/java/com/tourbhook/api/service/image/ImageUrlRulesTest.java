package com.tourbhook.api.service.image;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImageUrlRulesTest {

    @Test
    void realHttpsImageIsUsable() {
        assertThat(ImageUrlRules.isUsable("https://lh3.googleusercontent.com/abc")).isTrue();
    }

    @Test
    void nullBlankHttpAndTooLongAreNotUsable() {
        assertThat(ImageUrlRules.isUsable(null)).isFalse();
        assertThat(ImageUrlRules.isUsable("  ")).isFalse();
        assertThat(ImageUrlRules.isUsable("http://example.com/a.jpg")).isFalse();
        assertThat(ImageUrlRules.isUsable("https://example.com/" + "a".repeat(2100))).isFalse();
    }

    @Test
    void deadPlaceholderKeyUrlAndDevHostAreNotUsable() {
        assertThat(ImageUrlRules.isUsable("https://via.placeholder.com/400x300?text=No+Image")).isFalse();
        assertThat(ImageUrlRules.isUsable("https://maps.googleapis.com/maps/api/place/photo?maxwidth=400&key=SECRET")).isFalse();
        assertThat(ImageUrlRules.isUsable("https://cdn.tourbhook.local/x.jpg")).isFalse();
    }

    @Test
    void buildsUrlsWithoutDoubleSlash() {
        assertThat(ImageUrlRules.placeImageUrl("https://api.example.com/", "abc"))
                .isEqualTo("https://api.example.com/api/v1/images/places/abc");
        assertThat(ImageUrlRules.defaultImageUrl(" https://api.example.com "))
                .isEqualTo("https://api.example.com/api/v1/images/default");
        assertThat(ImageUrlRules.normalizeBase(null)).isEmpty();
    }
}