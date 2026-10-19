package com.example.viby.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class DownloadNotificationControllerTest {

    @Test
    public void trackNamePrefersCurrentTrackThenJobTitleThenUrl() {
        DownloadJob job = new DownloadJob("url", "playlist", true);
        assertEquals("url", DownloadNotificationController.trackName(job));

        job.title = "Playlist";
        assertEquals("Playlist", DownloadNotificationController.trackName(job));

        job.currentTrackTitle = "Current track";
        assertEquals("Current track", DownloadNotificationController.trackName(job));
    }

    @Test
    public void progressTextKeepsStatusOrderAndSkipsMissingParts() {
        assertEquals("Network · 2 / 10 · 4 MB / 8 MB · Paused",
                DownloadNotificationController.joinProgressText(
                        "Network", "2 / 10", "4 MB / 8 MB", "Paused"));
        assertEquals("2 / 10 · Paused",
                DownloadNotificationController.joinProgressText(
                        null, "2 / 10", "", "Paused"));
        assertEquals("", DownloadNotificationController.joinProgressText(null, ""));
    }

    @Test
    public void activeJobWinsForegroundSelection() {
        DownloadJob active = job(DownloadJob.Status.DOWNLOADING);
        DownloadJob queued = job(DownloadJob.Status.QUEUED);

        assertSame(active, DownloadNotificationController.selectNotificationJob(
                active, Arrays.asList(queued, active)));
    }

    @Test
    public void selectionFallsBackToFirstActiveQueuedJob() {
        DownloadJob completed = job(DownloadJob.Status.DONE);
        DownloadJob queued = job(DownloadJob.Status.QUEUED);
        DownloadJob paused = job(DownloadJob.Status.PAUSED);

        assertSame(queued, DownloadNotificationController.selectNotificationJob(
                completed, Arrays.asList(completed, queued, paused)));
        assertNull(DownloadNotificationController.selectNotificationJob(
                completed, Collections.singletonList(completed)));
    }

    private static DownloadJob job(DownloadJob.Status status) {
        DownloadJob job = new DownloadJob("url", "playlist", false);
        job.status = status;
        return job;
    }
}
