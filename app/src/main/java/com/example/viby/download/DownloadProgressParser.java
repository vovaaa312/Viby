package com.example.viby.download;

import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps progress monotonic and recovers percentages from yt-dlp output when its callback is 0. */
final class DownloadProgressParser {

    private static final Pattern DOWNLOAD_PERCENT = Pattern.compile(
            "(?:\\[download]|download:)\\s*([0-9]{1,3}(?:\\.[0-9]+)?)%",
            Pattern.CASE_INSENSITIVE);

    private DownloadProgressParser() {
    }

    static int resolve(int previous, float callbackProgress, @Nullable String outputLine) {
        int resolved = clamp(Math.round(callbackProgress));
        if (outputLine != null) {
            Matcher matcher = DOWNLOAD_PERCENT.matcher(outputLine);
            if (matcher.find()) {
                try {
                    resolved = Math.max(resolved,
                            clamp(Math.round(Float.parseFloat(matcher.group(1)))));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return Math.max(clamp(previous), resolved);
    }

    private static int clamp(int progress) {
        return Math.max(0, Math.min(100, progress));
    }
}
