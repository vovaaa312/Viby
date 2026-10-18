package com.example.viby.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DownloadQueueActionsTest {

    @Test
    public void pausingQueuedPlaylistDoesNotPauseAnyOtherPlaylist() {
        DownloadJob active = job("active", DownloadJob.Status.DOWNLOADING);
        DownloadJob selected = job("selected", DownloadJob.Status.QUEUED);
        List<DownloadJob> jobs = new ArrayList<>(Arrays.asList(active, selected));

        DownloadQueueActions.PauseResult result =
                DownloadQueueActions.setPaused(jobs, active, selected.id, true);

        assertTrue(result.changed());
        assertFalse(result.killActiveProcess);
        assertEquals(DownloadJob.Status.DOWNLOADING, active.status);
        assertFalse(active.pauseRequested);
        assertEquals(DownloadJob.Status.PAUSED, selected.status);
        assertTrue(selected.pauseRequested);
    }

    @Test
    public void pausingActivePlaylistInterruptsOnlyItsProcess() {
        DownloadJob selected = job("selected", DownloadJob.Status.DOWNLOADING);
        DownloadJob other = job("other", DownloadJob.Status.QUEUED);

        DownloadQueueActions.PauseResult result = DownloadQueueActions.setPaused(
                Arrays.asList(selected, other), selected, selected.id, true);

        assertTrue(result.killActiveProcess);
        assertTrue(selected.pauseRequested);
        assertTrue(selected.pauseInterrupted);
        assertFalse(other.pauseRequested);
    }

    @Test
    public void resumingOnePlaylistLeavesOtherPaused() {
        DownloadJob selected = job("selected", DownloadJob.Status.PAUSED);
        selected.pauseRequested = true;
        DownloadJob other = job("other", DownloadJob.Status.PAUSED);
        other.pauseRequested = true;

        DownloadQueueActions.setPaused(Arrays.asList(selected, other), null,
                selected.id, false);

        assertEquals(DownloadJob.Status.QUEUED, selected.status);
        assertFalse(selected.pauseRequested);
        assertEquals(DownloadJob.Status.PAUSED, other.status);
        assertTrue(other.pauseRequested);
    }

    @Test
    public void cancelingQueuedPlaylistDoesNotCancelActivePlaylist() {
        DownloadJob active = job("active", DownloadJob.Status.DOWNLOADING);
        DownloadJob selected = job("selected", DownloadJob.Status.QUEUED);

        DownloadQueueActions.CancelResult result = DownloadQueueActions.cancel(
                Arrays.asList(active, selected), active, selected.id);

        assertTrue(result.changed());
        assertFalse(result.killActiveProcess);
        assertTrue(result.finishImmediately);
        assertFalse(active.cancelRequested);
        assertEquals(DownloadJob.Status.DOWNLOADING, active.status);
        assertTrue(selected.cancelRequested);
        assertEquals(DownloadJob.Status.CANCELED, selected.status);
    }

    @Test
    public void cancelingActivePlaylistInterruptsOnlyActiveProcess() {
        DownloadJob selected = job("selected", DownloadJob.Status.DOWNLOADING);
        DownloadJob other = job("other", DownloadJob.Status.QUEUED);

        DownloadQueueActions.CancelResult result = DownloadQueueActions.cancel(
                Arrays.asList(selected, other), selected, selected.id);

        assertTrue(result.killActiveProcess);
        assertFalse(result.finishImmediately);
        assertTrue(selected.cancelRequested);
        assertFalse(other.cancelRequested);
    }

    @Test
    public void completedOrUnknownJobsIgnorePauseAndCancel() {
        DownloadJob done = job("done", DownloadJob.Status.DONE);
        List<DownloadJob> jobs = Arrays.asList(done);

        assertFalse(DownloadQueueActions.setPaused(jobs, null, done.id, true).changed());
        assertFalse(DownloadQueueActions.cancel(jobs, null, done.id).changed());
        assertFalse(DownloadQueueActions.cancel(jobs, null, Long.MAX_VALUE).changed());
    }

    @Test
    public void movingPlaylistChangesOnlyQueueOrder() {
        DownloadJob first = job("first", DownloadJob.Status.QUEUED);
        DownloadJob second = job("second", DownloadJob.Status.QUEUED);
        DownloadJob third = job("third", DownloadJob.Status.PAUSED);
        List<DownloadJob> jobs = new ArrayList<>(Arrays.asList(first, second, third));

        assertTrue(DownloadQueueActions.moveJob(jobs, third.id, first.id));

        assertSame(third, jobs.get(0));
        assertSame(first, jobs.get(1));
        assertSame(second, jobs.get(2));
        assertFalse(first.pauseRequested);
        assertFalse(second.pauseRequested);
    }

    @Test
    public void movingTrackAffectsOnlySelectedPlaylistAndOnlyWaitingItems() {
        DownloadJob selected = job("selected", DownloadJob.Status.QUEUED);
        DownloadJob other = job("other", DownloadJob.Status.QUEUED);
        addTracks(selected, "a", "b", "c");
        addTracks(other, "x", "y");

        assertTrue(DownloadQueueActions.moveTrack(selected, "c", "a"));
        assertEquals(Arrays.asList("c", "a", "b"), ids(selected));
        assertEquals(Arrays.asList("x", "y"), ids(other));

        selected.tracks.get(0).status = DownloadJob.TrackItem.Status.DOWNLOADING;
        assertFalse(DownloadQueueActions.moveTrack(selected, "c", "b"));
        assertEquals(Arrays.asList("c", "a", "b"), ids(selected));
    }

    private static DownloadJob job(String name, DownloadJob.Status status) {
        DownloadJob job = new DownloadJob("https://example/" + name, name, true);
        job.status = status;
        return job;
    }

    private static void addTracks(DownloadJob job, String... ids) {
        for (int i = 0; i < ids.length; i++) {
            job.tracks.add(new DownloadJob.TrackItem(ids[i], ids[i], "artist", 1, i));
        }
    }

    private static List<String> ids(DownloadJob job) {
        List<String> result = new ArrayList<>();
        for (DownloadJob.TrackItem item : job.tracks) {
            result.add(item.videoId);
        }
        return result;
    }
}
