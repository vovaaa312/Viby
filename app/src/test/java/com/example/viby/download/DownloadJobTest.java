package com.example.viby.download;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DownloadJobTest {

    @Test
    public void newJobsHaveIndependentStateAndUniqueIds() {
        DownloadJob first = new DownloadJob("first", "A", true);
        DownloadJob second = new DownloadJob("second", "B", true);

        first.pauseRequested = true;
        first.tracks.add(new DownloadJob.TrackItem("video", "Track", "Artist", 1, 0));

        assertNotEquals(first.id, second.id);
        assertTrue(first.pauseRequested);
        assertFalse(second.pauseRequested);
        assertTrue(second.tracks.isEmpty());
    }

    @Test
    public void onlyUnfinishedStatesAreActive() {
        DownloadJob job = new DownloadJob("url", "playlist", true);
        for (DownloadJob.Status status : new DownloadJob.Status[]{
                DownloadJob.Status.QUEUED,
                DownloadJob.Status.PREPARING,
                DownloadJob.Status.DOWNLOADING,
                DownloadJob.Status.PAUSED}) {
            job.status = status;
            assertTrue(status.name(), job.isActive());
        }
        for (DownloadJob.Status status : new DownloadJob.Status[]{
                DownloadJob.Status.DONE,
                DownloadJob.Status.FAILED,
                DownloadJob.Status.CANCELED}) {
            job.status = status;
            assertFalse(status.name(), job.isActive());
        }
    }
}
