package com.tourbhook.api.service.ugc;

import com.tourbhook.api.entity.UgcLinkPlatform;
import com.tourbhook.api.repository.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UgcLinkParserTest {

    private static final String VIDEO = "dQw4w9WgXcQ";
    private static final String WATCH = "https://www.youtube.com/watch?v=" + VIDEO;

    private final UgcLinkParser parser = new UgcLinkParser();

    private void assertYoutubeVideo(String input) {
        ParsedLink link = parser.parse(input);

        assertThat(link.platform()).as(input).isEqualTo(UgcLinkPlatform.YOUTUBE);
        assertThat(link.externalId()).as(input).isEqualTo(VIDEO);
        assertThat(link.canonicalUrl()).as(input).isEqualTo(WATCH);
    }

    // ------------------------------------------------------------------ YouTube

    @Test
    void parse_everyCommonWayOfWritingOneYoutubeVideo_endsUpAsTheSameCanonicalLink() {
        for (String input : List.of(
                WATCH,
                "https://youtube.com/watch?v=" + VIDEO,
                "https://m.youtube.com/watch?v=" + VIDEO,
                "https://www.youtube.com/watch?feature=share&v=" + VIDEO,           // v is not first
                "https://www.youtube.com/watch?v=" + VIDEO + "&t=43s&list=PL12345&si=abcDEF",
                "https://youtu.be/" + VIDEO,
                "https://youtu.be/" + VIDEO + "?si=tracking&t=10",
                "https://youtu.be/" + VIDEO + "/",
                "https://www.youtube.com/embed/" + VIDEO,
                "http://www.youtube.com/watch?v=" + VIDEO,                          // http is upgraded
                "HTTPS://WWW.YOUTUBE.COM/watch?v=" + VIDEO,                         // case does not matter for the host
                "  " + WATCH + "  ",
                "youtu.be/" + VIDEO,                                                // pasted without the scheme
                "www.youtube.com/watch?v=" + VIDEO)) {
            assertYoutubeVideo(input);
        }
    }

    @Test
    void parse_aYoutubeShort_keepsItsShortsForm() {
        ParsedLink link = parser.parse("https://www.youtube.com/shorts/abcDEF12345?feature=share");

        assertThat(link.platform()).isEqualTo(UgcLinkPlatform.YOUTUBE);
        assertThat(link.externalId()).isEqualTo("abcDEF12345");
        assertThat(link.canonicalUrl()).isEqualTo("https://www.youtube.com/shorts/abcDEF12345");
    }

    @Test
    void parse_rejectsYoutubeAddressesThatAreNotOneSpecificVideo() {
        for (String input : List.of(
                "https://www.youtube.com/@creator",
                "https://www.youtube.com/channel/UC1234567890",
                "https://www.youtube.com/playlist?list=PL1234567890",
                "https://www.youtube.com/watch",
                "https://www.youtube.com/watch?v=",
                "https://www.youtube.com/watch?v=tooShort",
                "https://www.youtube.com/watch?v=waaaaaaaaaaaaaaaytoolong",
                "https://www.youtube.com/watch?v=not valid id",
                "https://www.youtube.com/",
                "https://youtu.be/",
                "https://youtu.be/bad")) {
            assertThatThrownBy(() -> parser.parse(input)).as(input).isInstanceOf(BadRequestException.class);
        }
    }

    // ------------------------------------------------------------------ Instagram

    @Test
    void parse_anInstagramReel_dropsTrackingAndUserNameAndSettlesOnOneForm() {
        for (String input : List.of(
                "https://www.instagram.com/reel/C9xYz12AbCd/",
                "https://www.instagram.com/reel/C9xYz12AbCd",
                "https://www.instagram.com/reel/C9xYz12AbCd/?igsh=MWx0eXo",
                "https://instagram.com/reel/C9xYz12AbCd/",
                "https://www.instagram.com/reels/C9xYz12AbCd/",
                "https://www.instagram.com/some.user_1/reel/C9xYz12AbCd/",
                "instagram.com/reel/C9xYz12AbCd/")) {
            ParsedLink link = parser.parse(input);

            assertThat(link.platform()).as(input).isEqualTo(UgcLinkPlatform.INSTAGRAM);
            assertThat(link.externalId()).as(input).isEqualTo("C9xYz12AbCd");
            assertThat(link.canonicalUrl()).as(input).isEqualTo("https://www.instagram.com/reel/C9xYz12AbCd/");
        }
    }

    @Test
    void parse_rejectsInstagramAddressesThatAreNotAReel() {
        for (String input : List.of(
                "https://www.instagram.com/someone/",
                "https://www.instagram.com/p/ABC12345/",
                "https://www.instagram.com/stories/someone/123456789/",
                "https://www.instagram.com/reel/",
                "https://www.instagram.com/reel/abc/",
                "https://www.instagram.com/reel/has space/")) {
            assertThatThrownBy(() -> parser.parse(input)).as(input).isInstanceOf(BadRequestException.class);
        }
    }

    // ------------------------------------------------------------------ the host must be the real one

    @Test
    void parse_rejectsLookalikeHostsAndOtherSites() {
        for (String input : List.of(
                "https://vimeo.com/123456789",
                "https://youtube.com.evil.example/watch?v=" + VIDEO,
                "https://evil.example/www.youtube.com/watch?v=" + VIDEO,
                "https://notyoutube.com/watch?v=" + VIDEO,
                "https://www.youtube.com.evil.example/watch?v=" + VIDEO,
                "https://instagram.com.evil.example/reel/C9xYz12AbCd/",
                "https://www.youtube.com./watch?v=" + VIDEO,                         // trailing dot
                "https://www.youtube.com@evil.example/watch?v=" + VIDEO,             // really points at evil.example
                "https://music.youtube.com/watch?v=" + VIDEO)) {
            assertThatThrownBy(() -> parser.parse(input)).as(input).isInstanceOf(BadRequestException.class);
        }
    }

    @Test
    void parse_rejectsUserNamesPortsAndOtherSchemes() {
        for (String input : List.of(
                "https://someone@www.youtube.com/watch?v=" + VIDEO,
                "https://www.youtube.com:8443/watch?v=" + VIDEO,
                "https://www.youtube.com:443/watch?v=" + VIDEO,
                "ftp://www.youtube.com/watch?v=" + VIDEO,
                "javascript:alert(1)",
                "file:///etc/passwd",
                "data:text/html,<script>alert(1)</script>")) {
            assertThatThrownBy(() -> parser.parse(input)).as(input).isInstanceOf(BadRequestException.class);
        }
    }

    @Test
    void parse_rejectsTextThatIsNotAnAddressAtAll() {
        for (String input : List.of("hello world", "   ", "", "https://", "https:///watch?v=" + VIDEO, "https://www.youtube.com/watch?v=" + VIDEO + "\n<script>")) {
            assertThatThrownBy(() -> parser.parse(input)).as("[" + input + "]").isInstanceOf(BadRequestException.class);
        }
        assertThatThrownBy(() -> parser.parse(null)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void parse_rejectsAnAddressLongerThanTheLimit() {
        String tooLong = WATCH + "&x=" + "a".repeat(UgcLinkParser.MAX_URL_LENGTH);

        assertThatThrownBy(() -> parser.parse(tooLong)).isInstanceOf(BadRequestException.class).hasMessageContaining("too long");
    }
}