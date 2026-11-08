package com.example.viby.library;

/**
 * Conservative free-space estimate for one track: yt-dlp keeps the source stream and the
 * converted file side by side until post-processing finishes, plus a cover image.
 */
public final class StorageSpaceGuard {

    /** 320 kbit/s MP3 (40 KB/s) + ~160 kbit/s source stream (20 KB/s). */
    static final long BYTES_PER_SECOND = 60L * 1024L;
    /** Used when YouTube does not report a duration (live mixes, odd uploads). */
    static final long UNKNOWN_DURATION_SECONDS = 15L * 60L;
    /** Never fill the device completely: Android and other apps need space too. */
    public static final long SAFETY_MARGIN_BYTES = 50L * 1024L * 1024L;

    private StorageSpaceGuard() {
    }

    public static long estimateDownloadBytes(long durationMs) {
        long seconds = durationMs > 0 ? (durationMs + 999L) / 1000L : UNKNOWN_DURATION_SECONDS;
        long media = seconds * BYTES_PER_SECOND;
        return media + media / 4L + 2L * 1024L * 1024L;
    }

    public static long requiredBytes(long durationMs) {
        return estimateDownloadBytes(durationMs) + SAFETY_MARGIN_BYTES;
    }

    public static boolean hasEnoughSpace(long usableBytes, long durationMs) {
        return usableBytes >= requiredBytes(durationMs);
    }
}
