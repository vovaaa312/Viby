package com.example.viby.util;

import androidx.annotation.Nullable;

import java.net.URI;
import java.net.URLDecoder;
import java.util.Locale;

/** Extracts a video ID from common YouTube share and watch URL variants. */
public final class YoutubeUrlParser {

    private YoutubeUrlParser() {
    }

    @Nullable
    public static String videoId(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        try {
            URI uri = new URI(url.trim());
            String host = uri.getHost();
            if (host == null) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            if (host.equals("youtu.be") || host.endsWith(".youtu.be")) {
                return firstPathSegment(uri.getPath());
            }
            if (!host.equals("youtube.com") && !host.endsWith(".youtube.com")) {
                return null;
            }
            String queryId = queryParameter(uri.getRawQuery(), "v");
            if (queryId != null) {
                return queryId;
            }
            String path = uri.getPath();
            String[] segments = path != null ? path.split("/") : new String[0];
            if (segments.length >= 3) {
                String type = segments[1].toLowerCase(Locale.ROOT);
                if (type.equals("shorts") || type.equals("embed")
                        || type.equals("live")) {
                    return clean(segments[2]);
                }
            }
        } catch (Exception ignored) {
            // yt-dlp validates less common URL formats itself.
        }
        return null;
    }

    @Nullable
    private static String firstPathSegment(@Nullable String path) {
        if (path == null) {
            return null;
        }
        for (String segment : path.split("/")) {
            String value = clean(segment);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    @Nullable
    private static String queryParameter(@Nullable String query, String name) {
        if (query == null) {
            return null;
        }
        for (String part : query.split("&")) {
            int separator = part.indexOf('=');
            if (separator <= 0 || !part.substring(0, separator).equals(name)) {
                continue;
            }
            try {
                return clean(URLDecoder.decode(part.substring(separator + 1), "UTF-8"));
            } catch (Exception ignored) {
                return clean(part.substring(separator + 1));
            }
        }
        return null;
    }

    @Nullable
    private static String clean(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }
}
