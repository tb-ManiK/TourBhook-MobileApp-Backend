package com.tourbhook.api.service.ugc;

import com.tourbhook.api.entity.UgcLinkPlatform;
import com.tourbhook.api.repository.exception.BadRequestException;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;

@Component
public class UgcLinkParser {

    static final int MAX_URL_LENGTH = 2000;

    private static final Set<String> YOUTUBE_HOSTS = Set.of("youtube.com", "www.youtube.com", "m.youtube.com");
    private static final String YOUTUBE_SHORT_HOST = "youtu.be";
    private static final Set<String> INSTAGRAM_HOSTS = Set.of("instagram.com", "www.instagram.com", "m.instagram.com", "instagr.am", "www.instagr.am");

    private static final Pattern YOUTUBE_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final Pattern YOUTUBE_PATH = Pattern.compile("^/(shorts|embed|live|v)/([A-Za-z0-9_-]{11})/?$");
    private static final String CODE = "([A-Za-z0-9_-]{5,30})";
    private static final Pattern INSTAGRAM_CONTENT_PATH =
            Pattern.compile("^/(?:[A-Za-z0-9._]{1,30}/)?(reel|reels|p|tv)/" + CODE + "/?$");
    private static final Pattern INSTAGRAM_SHARE_PATH =
            Pattern.compile("^/share/(?:(reel|p)/)?" + CODE + "/?$");
    private static final Set<String> NOT_A_CODE = Set.of("reel", "reels", "p", "tv");
    private static final Pattern FULL_URL = Pattern.compile("(?i)https?://[^\\s<>\"']+");
    private static final Pattern BARE_URL = Pattern.compile(
            "(?i)(?:^|\\s)((?:www\\.|m\\.)?(?:youtube\\.com|youtu\\.be|instagram\\.com|instagr\\.am)/[^\\s<>\"']*)");
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[.,;:!?)\\]}>]+$");
    private static final String NOT_A_LINK = "That does not look like a valid link.";


    public ParsedLink parse(String rawText) {
        String text  = rawText == null ? "" : rawText.trim();
        if (text.isEmpty()) {
            throw new BadRequestException("A link is required.");
        }
        if (text.length() > MAX_URL_LENGTH) {
            throw new BadRequestException("The link is too long.");
        }
        // People paste "youtu.be/abc" without the scheme; accept it rather than argue.
        BadRequestException firstProblem = null;
        for (String candidate : candidates(text)) {
            try {
                return parseOne(candidate);
            } catch (BadRequestException e) {
                if (firstProblem == null) {
                    firstProblem = e;
                }
            }
        }
        throw firstProblem != null ? firstProblem : new BadRequestException(NOT_A_LINK);
    }

    private List<String> candidates(String text) {
        List<String> found = new ArrayList<>();
        Matcher full = FULL_URL.matcher(text);
        while (full.find()) {
            found.add(stripTrailingPunctuation(full.group()));
        }
        Matcher bare = BARE_URL.matcher(text);
        while (bare.find()) {
            found.add(stripTrailingPunctuation(bare.group(1)));
        }
        return found;
    }

    private String stripTrailingPunctuation(String url) {
        return TRAILING_PUNCTUATION.matcher(url).replaceFirst("");
    }

    private ParsedLink parseOne(String candidate) {
        if (!candidate.contains("://")) {
            candidate = "https://" + candidate;
        }

        String fragmentFree = cutAt(candidate, '#');
        String rawQuery = null;
        int queryStart = fragmentFree.indexOf('?');
        String withoutQuery = fragmentFree;
        if (queryStart >= 0) {
            rawQuery = fragmentFree.substring(queryStart + 1);
            withoutQuery = fragmentFree.substring(0, queryStart);
        }
        URI uri = toUri(withoutQuery);
        String scheme = uri.getScheme();
        boolean webScheme = "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);
        if (!webScheme || uri.getHost() == null || uri.getUserInfo() != null || uri.getPort() != -1) {
            throw new BadRequestException(NOT_A_LINK);
        }

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (YOUTUBE_SHORT_HOST.equals(host)) {
            return youtubeVideo(youtubeShortLinkId(path));
        }
        if (YOUTUBE_HOSTS.contains(host)) {
            return youtubeLink(path, rawQuery);
        }
        if (INSTAGRAM_HOSTS.contains(host)) {
            return instagramLink(path);
        }
        throw new BadRequestException("Only YouTube and Instagram links are supported.");
    }

    // ------------------------------------------------------------------ YouTube

    private ParsedLink youtubeLink(String path, String rawQuery) {
        if ("/watch".equals(path)) {
            return youtubeVideo(queryParameter(rawQuery, "v"));
        }
        Matcher matcher = YOUTUBE_PATH.matcher(path);
        if (matcher.matches()) {
            String id = matcher.group(2);
            return "shorts".equals(matcher.group(1))
                    ? new ParsedLink(UgcLinkPlatform.YOUTUBE, id, "https://www.youtube.com/shorts/" + id)
                    : youtubeVideo(id);
        }
        throw youtubeProblem();
    }

    private String youtubeShortLinkId(String path) {
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        int slash = trimmed.indexOf('/');
        return slash >= 0 ? trimmed.substring(0, slash) : trimmed;
    }

    private ParsedLink youtubeVideo(String id) {
        if (id == null || !YOUTUBE_ID.matcher(id).matches()) {
            throw youtubeProblem();
        }
        return new ParsedLink(UgcLinkPlatform.YOUTUBE, id, "https://www.youtube.com/watch?v=" + id);
    }

    private BadRequestException youtubeProblem() {
        return new BadRequestException("Please share a link to a specific YouTube video "
                + "(a watch, youtu.be, Shorts or live link), not a channel or playlist.");
    }

    // ------------------------------------------------------------------ Instagram

    private ParsedLink instagramLink(String path) {
        Matcher share = INSTAGRAM_SHARE_PATH.matcher(path);
        if (share.matches() && !NOT_A_CODE.contains(share.group(2).toLowerCase(Locale.ROOT))) {
            String type = share.group(1);
            String code = share.group(2);
            String canonical = "https://www.instagram.com/share/" + (type == null ? "" : type + "/") + code + "/";
            return new ParsedLink(UgcLinkPlatform.INSTAGRAM, "share_" + code, canonical);
        }
        Matcher content = INSTAGRAM_CONTENT_PATH.matcher(path);
        if (!content.matches()) {
            throw new BadRequestException(
                    "Please share a link to an Instagram Reel or video post (instagram.com/reel/...).");
        }
        String type = content.group(1);
        String code = content.group(2);
        String segment = "p".equals(type) ? "p" : "tv".equals(type) ? "tv" : "reel";
        return new ParsedLink(UgcLinkPlatform.INSTAGRAM, code,
                "https://www.instagram.com/" + segment + "/" + code + "/");
    }

    // ------------------------------------------------------------------ helpers

    private URI toUri(String candidate) {
        try {
            return new URI(candidate);
        } catch (URISyntaxException e) {
            throw new BadRequestException(NOT_A_LINK);
        }
    }

    private String cutAt(String value, char marker) {
        int index = value.indexOf(marker);
        return index >= 0 ? value.substring(0, index) : value;
    }

    private String queryParameter(String rawQuery, String name) {
        if (rawQuery == null) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(name)) {
                return pair.substring(equals + 1);
            }
        }
        return null;
    }
}