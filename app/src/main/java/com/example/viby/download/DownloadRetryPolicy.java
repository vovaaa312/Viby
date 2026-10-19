package com.example.viby.download;

/** Retry limits and increasing delays for transient per-track failures. */
public final class DownloadRetryPolicy {

    public static final int MAX_ATTEMPTS = 4;
    private static final long[] DELAYS_MS = {2_000L, 5_000L, 10_000L};

    private DownloadRetryPolicy() {
    }

    public static boolean shouldRetry(DownloadFailure failure, int completedAttempts) {
        return failure.retryable && completedAttempts < MAX_ATTEMPTS;
    }

    public static long delayAfterAttempt(int completedAttempts) {
        int index = Math.max(0, Math.min(DELAYS_MS.length - 1,
                completedAttempts - 1));
        return DELAYS_MS[index];
    }
}
