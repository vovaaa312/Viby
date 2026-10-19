package com.example.viby.download;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

/** Stable, user-safe classification of failures reported by yt-dlp, FFmpeg and Android I/O. */
public final class DownloadFailure {

    public enum Code {
        VIDEO_REMOVED,
        VIDEO_UNAVAILABLE,
        VIDEO_PRIVATE,
        REGION_BLOCKED,
        AGE_RESTRICTED,
        EXTRACTOR_OUTDATED,
        AUTH_REQUIRED,
        RATE_LIMITED,
        NETWORK,
        FFMPEG,
        STORAGE_FULL,
        FILE_SYSTEM,
        FORMAT_UNAVAILABLE,
        UNKNOWN
    }

    public final Code code;
    public final boolean retryable;
    public final boolean requiresAuthorization;

    private DownloadFailure(Code code, boolean retryable,
                            boolean requiresAuthorization) {
        this.code = code;
        this.retryable = retryable;
        this.requiresAuthorization = requiresAuthorization;
    }

    public static DownloadFailure classify(Throwable error) {
        String message = allMessages(error).toLowerCase(Locale.US);

        if (containsAny(message, "no space left", "enospc", "disk full",
                "insufficient space", "not enough space")) {
            return failure(Code.STORAGE_FULL, false, false);
        }
        if (error instanceof UnknownHostException
                || error instanceof SocketTimeoutException
                || error instanceof ConnectException
                || containsAny(message,
                "playlists that require authentication may not extract correctly "
                        + "without a successful webpage download",
                "unable to download webpage", "unable to download api page",
                "failed to download webpage", "failed to download api page")
                || containsAny(message, "network is unreachable", "connection reset",
                "connection refused", "timed out", "timeout", "unable to resolve host",
                "temporary failure in name resolution", "remote host terminated")) {
            return failure(Code.NETWORK, true, false);
        }
        if (containsAny(message, "http error 429", "status code 429",
                "too many requests", "rate limit", "rate-limit", "confirm you’re not a bot",
                "confirm you're not a bot", "sign in to confirm you’re not a bot",
                "sign in to confirm you're not a bot", "po token")) {
            return failure(Code.RATE_LIMITED, true, false);
        }
        if (containsAny(message, "private video", "video is private",
                "this video is private", "members-only")) {
            return failure(Code.VIDEO_PRIVATE, false, true);
        }
        if (containsAny(message, "removed", "deleted", "terminated",
                "copyright claim", "copyright grounds")) {
            return failure(Code.VIDEO_REMOVED, false, false);
        }
        if (containsAny(message, "video unavailable", "this video is unavailable",
                "this content isn't available", "this content is not available")) {
            return failure(Code.VIDEO_UNAVAILABLE, false, false);
        }
        if (containsAny(message, "not available in your country",
                "not available in your region", "geo-restricted", "georestricted",
                "blocked in your country", "who has blocked it",
                "not made this video available in your country")) {
            return failure(Code.REGION_BLOCKED, false, false);
        }
        if (containsAny(message, "age-restricted", "age restricted",
                "inappropriate for some users")) {
            return failure(Code.AGE_RESTRICTED, false, true);
        }
        if (containsAny(message, "cookies", "login required", "sign in required",
                "authentication required", "this video requires payment")) {
            return failure(Code.AUTH_REQUIRED, false, true);
        }
        if (containsAny(message, "update yt-dlp", "update to a newer version",
                "please report this issue", "unsupported url")) {
            return failure(Code.EXTRACTOR_OUTDATED, false, false);
        }
        if (containsAny(message, "ffmpeg", "ffprobe", "postprocessing",
                "post-processing")) {
            return failure(Code.FFMPEG, false, false);
        }
        if (containsAny(message, "requested format is not available",
                "no video formats found", "no audio formats found")) {
            return failure(Code.FORMAT_UNAVAILABLE, false, false);
        }
        if (error instanceof FileNotFoundException
                || error instanceof SecurityException
                || error instanceof IOException
                || containsAny(message, "permission denied", "read-only file system",
                "file not found", "failed to open")) {
            return failure(Code.FILE_SYSTEM, false, false);
        }
        // Unknown extractor failures are retried because many upstream YouTube errors are brief.
        return failure(Code.UNKNOWN, true, false);
    }

    private static DownloadFailure failure(Code code, boolean retryable,
                                            boolean requiresAuthorization) {
        return new DownloadFailure(code, retryable, requiresAuthorization);
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String allMessages(Throwable error) {
        StringBuilder result = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 8) {
            if (current.getMessage() != null) {
                result.append(' ').append(current.getMessage());
            }
            result.append(' ').append(current.getClass().getSimpleName());
            current = current.getCause();
        }
        return result.toString();
    }
}
