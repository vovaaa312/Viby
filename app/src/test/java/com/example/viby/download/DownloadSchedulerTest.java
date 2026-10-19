package com.example.viby.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DownloadSchedulerTest {

    @Test
    public void nextRunnableSkipsPausedCanceledAndTerminalJobs() {
        DownloadJob paused = job("paused", DownloadJob.Status.PAUSED, 0L);
        paused.pauseRequested = true;
        DownloadJob canceled = job("canceled", DownloadJob.Status.QUEUED, 1L);
        canceled.cancelRequested = true;
        DownloadJob done = job("done", DownloadJob.Status.DONE, 2L);
        DownloadJob queued = job("queued", DownloadJob.Status.QUEUED, 3L);

        assertSame(queued, DownloadScheduler.nextRunnable(
                Arrays.asList(paused, canceled, done, queued)));
        assertNull(DownloadScheduler.nextRunnable(Arrays.asList(paused, canceled, done)));
    }

    @Test
    public void nextPositionIgnoresTerminalHistory() {
        DownloadJob queued = job("queued", DownloadJob.Status.QUEUED, 4L);
        DownloadJob paused = job("paused", DownloadJob.Status.PAUSED, 8L);
        DownloadJob oldDone = job("done", DownloadJob.Status.DONE, 100L);

        assertEquals(9L, DownloadScheduler.nextQueuePosition(
                Arrays.asList(queued, paused, oldDone)));
    }

    @Test
    public void sortPlacesActiveJobsFirstAndUsesStableQueuePositions() {
        DownloadJob done = job("done", DownloadJob.Status.DONE, 0L);
        DownloadJob second = job("second", DownloadJob.Status.PAUSED, 2L);
        DownloadJob first = job("first", DownloadJob.Status.QUEUED, 1L);
        List<DownloadJob> jobs = new ArrayList<>(Arrays.asList(done, second, first));

        DownloadScheduler.sort(jobs);

        assertSame(first, jobs.get(0));
        assertSame(second, jobs.get(1));
        assertSame(done, jobs.get(2));
    }

    @Test
    public void normalizationRenumbersOnlyActiveJobsInDisplayedOrder() {
        DownloadJob first = job("first", DownloadJob.Status.QUEUED, 20L);
        DownloadJob done = job("done", DownloadJob.Status.DONE, 77L);
        DownloadJob second = job("second", DownloadJob.Status.PAUSED, 40L);

        DownloadScheduler.normalizeActivePositions(Arrays.asList(first, done, second));

        assertEquals(0L, first.queuePosition);
        assertEquals(77L, done.queuePosition);
        assertEquals(1L, second.queuePosition);
    }

    private static DownloadJob job(String name, DownloadJob.Status status,
                                   long queuePosition) {
        DownloadJob job = new DownloadJob("https://example/" + name, name, true);
        job.status = status;
        job.queuePosition = queuePosition;
        return job;
    }
}
