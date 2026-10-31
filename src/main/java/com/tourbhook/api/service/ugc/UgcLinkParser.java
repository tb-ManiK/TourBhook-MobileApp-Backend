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

@Component
public class UgcLinkParser {

    static final int MAX_URL_LENGTH = 2000;

    private static final Set<String> YOUTUBE_HOSTS = Set.of("youtube.com", "www.youtube.com", "m.youtube.com");
    private static final String YOUTUBE_SHORT_HOST = "youtu.be";
    private static final Set<String> INSTAGRAM_HOSTS = Set.of("instagram.com", "www.instagram.com");

    private static final Pattern YOUTUBE_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final Pattern YOUTUBE_PATH = Pattern.compile("^/(shorts|embed)/([A-Za-z0-9_-]{11})/?$");

    private static final Pattern INSTAGRAM_REEL_PATH =
            Pattern.compile("^/(?:[A-Za-z0-9._]{1,30}/)?(?:reel|reels)/([A-Za-z0-9_-]{5,30})/?$");

    private static final String NOT_A_LINK = "That does not look like a valid link.";


    public ParsedLink parse(String rawUrl) {
        String candidate = rawUrl == null ? "" : rawUrl.trim();
        if (candidate.isEmpty()) {
            throw new BadRequestException("A link is required.");
        }
        if (candidate.length() > MAX_URL_LENGTH) {
            throw new BadRequestException("The link is too long.");
        }
        // People paste "youtu.be/abc" without the scheme; accept it rather than argue.
        if (!candidate.contains("://")) {
            candidate = "https://" + candidate;
        }

        URI uri = toUri(candidate);
        String scheme = uri.getScheme();
        boolean webScheme = "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);
        if (!webScheme || uri.getHost() == null || uri.getUserInfo() != null || uri.getPort() != -1) {
            throw new BadRequestException(NOT_A_LINK);
        }

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (YOUTUBE_SHORT_HOST.equals(host)) {
            return youtubeVideo(youtubeShortLinkId(uri));
        }
        if (YOUTUBE_HOSTS.contains(host)) {
            return youtubeLink(uri);
        }
        if (INSTAGRAM_HOSTS.contains(host)) {
            return instagramReel(uri);
        }
        throw new BadRequestException("Only YouTube and Instagram Reels links are supported.");
    }

    // ------------------------------------------------------------------ YouTube

    private ParsedLink youtubeLink(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        if ("/watch".equals(path)) {
            return youtubeVideo(queryParameter(uri.getRawQuery(), "v"));
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

    private String youtubeShortLinkId(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        String id = path.startsWith("/") ? path.substring(1) : path;
        return id.endsWith("/") ? id.substring(0, id.length() - 1) : id;
    }

    private ParsedLink youtubeVideo(String id) {
        if (id == null || !YOUTUBE_ID.matcher(id).matches()) {
            throw youtubeProblem();
        }
        return new ParsedLink(UgcLinkPlatform.YOUTUBE, id, "https://www.youtube.com/watch?v=" + id);
    }

    private BadRequestException youtubeProblem() {
        return new BadRequestException("Please share a link to a specific YouTube video "
                + "(a watch, youtu.be or Shorts link), not a channel or playlist.");
    }

    // ------------------------------------------------------------------ Instagram

    private ParsedLink instagramReel(URI uri) {
        Matcher matcher = INSTAGRAM_REEL_PATH.matcher(uri.getPath() == null ? "" : uri.getPath());
        if (!matcher.matches()) {
            throw new BadRequestException("Please share a link to an Instagram Reel (instagram.com/reel/...).");
        }
        String code = matcher.group(1);
        return new ParsedLink(UgcLinkPlatform.INSTAGRAM, code, "https://www.instagram.com/reel/" + code + "/");
    }

    // ------------------------------------------------------------------ helpers

    private URI toUri(String candidate) {
        try {
            return new URI(candidate);
        } catch (URISyntaxException e) {
            throw new BadRequestException(NOT_A_LINK);
        }
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