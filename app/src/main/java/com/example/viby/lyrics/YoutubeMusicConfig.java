package com.example.viby.lyrics;

import androidx.annotation.Nullable;

/** Extracts the current public web-client configuration from YouTube Music HTML. */
final class YoutubeMusicConfig {

    private static final String API_KEY_PROPERTY = "\"INNERTUBE_API_KEY\"";

    private YoutubeMusicConfig() {
    }

    @Nullable
    static String extractApiKey(String html) {
        if (html == null) {
            return null;
        }
        int property = html.indexOf(API_KEY_PROPERTY);
        while (property >= 0) {
            int colon = html.indexOf(':', property + API_KEY_PROPERTY.length());
            if (colon < 0) {
                return null;
            }
            int quote = skipWhitespaceToQuote(html, colon + 1);
            if (quote >= 0) {
                int end = html.indexOf('"', quote + 1);
                if (end > quote + 1) {
                    String candidate = html.substring(quote + 1, end);
                    if (isSafeConfigToken(candidate)) {
                        return candidate;
                    }
                }
            }
            property = html.indexOf(API_KEY_PROPERTY,
                    property + API_KEY_PROPERTY.length());
        }
        return null;
    }

    private static int skipWhitespaceToQuote(String value, int start) {
        for (int i = start; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '"') {
                return i;
            }
            if (!Character.isWhitespace(character)) {
                return -1;
            }
        }
        return -1;
    }

    private static boolean isSafeConfigToken(String value) {
        if (value.length() < 20 || value.length() > 100) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (!Character.isLetterOrDigit(character)
                    && character != '_' && character != '-') {
                return false;
            }
        }
        return true;
    }
}
