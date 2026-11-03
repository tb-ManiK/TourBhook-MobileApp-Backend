package com.tourbhook.api.service.ugc;

import com.tourbhook.api.entity.UgcLinkPlatform;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class UgcLinkMediaTest {

    private static final String VIDEO = "dQw4w9WgXcQ";
    private static final String REEL = "https://www.instagram.com/reel/C9xYz12AbCd/";

    @Test
    void youtube_hasAThumbnailAndAPlayerAddress() {
        assertThat(UgcLinkMedia.thumbnailUrl(UgcLinkPlatform.YOUTUBE, VIDEO))
                .isEqualTo("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg");
        assertThat(UgcLinkMedia.embedUrl(UgcLinkPlatform.YOUTUBE, VIDEO, "https://www.youtube.com/watch?v=" + VIDEO))
                .isEqualTo("https://www.youtube.com/embed/dQw4w9WgXcQ");
    }

    @Test
    void instagram_hasNoThumbnail_butAReelOrPostHasAPlayerAddress() {
        assertThat(UgcLinkMedia.thumbnailUrl(UgcLinkPlatform.INSTAGRAM, "C9xYz12AbCd")).isNull();
        assertThat(UgcLinkMedia.embedUrl(UgcLinkPlatform.INSTAGRAM, "C9xYz12AbCd", REEL))
                .isEqualTo(REEL + "embed/");
        assertThat(UgcLinkMedia.embedUrl(UgcLinkPlatform.INSTAGRAM, "ABC12345", "https://www.instagram.com/p/ABC12345/"))
                .isEqualTo("https://www.instagram.com/p/ABC12345/embed/");
    }

    @Test
    void instagram_aShareLinkHasNoPlayerAddress_becauseItsRealPostIsNotKnown() {
        assertThat(UgcLinkMedia.embedUrl(UgcLinkPlatform.INSTAGRAM, "share_BAbCdEfGh1",
                "https://www.instagram.com/share/reel/BAbCdEfGh1/")).isNull();
    }

    @Test
    void missingData_givesNull_insteadOfAFailure() {
        assertThat(UgcLinkMedia.thumbnailUrl(null, null)).isNull();
        assertThat(UgcLinkMedia.thumbnailUrl(UgcLinkPlatform.YOUTUBE, null)).isNull();
        assertThat(UgcLinkMedia.embedUrl(UgcLinkPlatform.YOUTUBE, null, null)).isNull();
        assertThat(UgcLinkMedia.embedUrl(UgcLinkPlatform.INSTAGRAM, "x", null)).isNull();
    }
}