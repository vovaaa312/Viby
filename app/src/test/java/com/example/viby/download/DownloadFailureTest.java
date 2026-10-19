package com.example.viby.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

public class DownloadFailureTest {

    @Test
    public void classifiesPermanentYoutubeFailures() {
        assertCode(DownloadFailure.Code.VIDEO_PRIVATE,
                "ERROR: Private video. Sign in if you've been granted access");
        assertCode(DownloadFailure.Code.VIDEO_REMOVED,
                "This video has been removed due to a copyright claim");
        assertCode(DownloadFailure.Code.VIDEO_UNAVAILABLE,
                "ERROR: [youtube] -lkSxUsfrD8: Video unavailable");
        assertCode(DownloadFailure.Code.REGION_BLOCKED,
                "The uploader has not made this video available in your country");
        DownloadFailure age = DownloadFailure.classify(
                new IllegalStateException("This video is age-restricted"));
        assertEquals(DownloadFailure.Code.AGE_RESTRICTED, age.code);
        assertTrue(age.requiresAuthorization);
        assertFalse(age.retryable);
    }

    @Test
    public void classifiesTransientFailuresAsRetryable() {
        DownloadFailure timeout = DownloadFailure.classify(
                new SocketTimeoutException("timed out"));
        assertEquals(DownloadFailure.Code.NETWORK, timeout.code);
        assertTrue(timeout.retryable);

        DownloadFailure dns = DownloadFailure.classify(new UnknownHostException("youtube"));
        assertEquals(DownloadFailure.Code.NETWORK, dns.code);
        assertTrue(dns.retryable);

        DownloadFailure playlistWebpage = DownloadFailure.classify(
                new IllegalStateException("Playlists that require authentication may not "
                        + "extract correctly without a successful webpage download. "
                        + "Cookies may only be for the first account"));
        assertEquals(DownloadFailure.Code.NETWORK, playlistWebpage.code);
        assertTrue(playlistWebpage.retryable);
        assertFalse(playlistWebpage.requiresAuthorization);

        DownloadFailure limited = DownloadFailure.classify(
                new IllegalStateException("HTTP Error 429: Too Many Requests"));
        assertEquals(DownloadFailure.Code.RATE_LIMITED, limited.code);
        assertTrue(limited.retryable);
    }

    @Test
    public void detectsStorageAuthExtractorAndFfmpegFailures() {
        assertCode(DownloadFailure.Code.STORAGE_FULL, "ENOSPC: no space left on device");
        DownloadFailure auth = DownloadFailure.classify(
                new IllegalStateException("Login required; provide cookies"));
        assertEquals(DownloadFailure.Code.AUTH_REQUIRED, auth.code);
        assertTrue(auth.requiresAuthorization);
        assertCode(DownloadFailure.Code.EXTRACTOR_OUTDATED,
                "Please update yt-dlp to a newer version");
        assertCode(DownloadFailure.Code.FFMPEG, "ffmpeg exited with code 1");
        assertCode(DownloadFailure.Code.FORMAT_UNAVAILABLE,
                "Requested format is not available");
    }

    @Test
    public void retryPolicyStopsAfterFourthAttemptAndIncreasesDelay() {
        DownloadFailure transientFailure = DownloadFailure.classify(
                new SocketTimeoutException("timeout"));
        assertTrue(DownloadRetryPolicy.shouldRetry(transientFailure, 1));
        assertTrue(DownloadRetryPolicy.shouldRetry(transientFailure, 3));
        assertFalse(DownloadRetryPolicy.shouldRetry(transientFailure, 4));
        assertTrue(DownloadRetryPolicy.delayAfterAttempt(1)
                < DownloadRetryPolicy.delayAfterAttempt(2));
        assertTrue(DownloadRetryPolicy.delayAfterAttempt(2)
                < DownloadRetryPolicy.delayAfterAttempt(3));
    }

    private static void assertCode(DownloadFailure.Code expected, String message) {
        assertEquals(expected,
                DownloadFailure.classify(new IllegalStateException(message)).code);
    }
}
