package com.example.viby.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.MediaItem;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.example.viby.data.PlaylistInfo;
import com.example.viby.data.Track;
import com.example.viby.download.DownloadJob;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public class AdapterStateInstrumentedTest {

    @Test
    public void tracksFilterAndSelectionUseStableTrackIds() {
        List<Integer> selectionCounts = new ArrayList<>();
        TracksAdapter adapter = new TracksAdapter(new TracksAdapter.Listener() {
            @Override public void onTrackClick(Track track, int position) { }
            @Override public void onTrackLongClick(Track track) { }
            @Override public void onSelectionChanged(int selectedCount) {
                selectionCounts.add(selectedCount);
            }
        });
        Track first = track(1, "Alpha", "First artist");
        Track second = track(2, "Beta", "Second artist");
        Track third = track(3, "Gamma", null);
        adapter.submit(Arrays.asList(first, second, third));

        adapter.setQuery("second");
        assertEquals(1, adapter.getItemCount());
        adapter.enterSelectionMode(second);
        adapter.selectAll(true);
        assertEquals(Arrays.asList(second), adapter.getSelectedTracks());

        adapter.setQuery("");
        assertEquals(3, adapter.getItemCount());
        adapter.toggleSelection(first);
        assertEquals(Arrays.asList(first, second), adapter.getSelectedTracks());

        adapter.submit(Arrays.asList(first, third));
        assertEquals(Arrays.asList(first), adapter.getSelectedTracks());
        assertEquals(Integer.valueOf(1), selectionCounts.get(selectionCounts.size() - 1));
        adapter.exitSelectionMode();
        assertFalse(adapter.isSelectionMode());
        assertTrue(adapter.getSelectedTracks().isEmpty());
    }

    @Test
    public void downloadAdaptersOnlyMoveEligibleRows() {
        DownloadsAdapter jobsAdapter = new DownloadsAdapter(new DownloadsAdapter.Listener() {
            @Override public void onStartDrag(RecyclerView.ViewHolder holder) { }
            @Override public void onOpenTracks(long jobId) { }
        });
        DownloadJob first = job("first", DownloadJob.Status.QUEUED);
        DownloadJob done = job("done", DownloadJob.Status.DONE);
        DownloadJob third = job("third", DownloadJob.Status.PAUSED);
        jobsAdapter.submit(Arrays.asList(first, done, third));

        jobsAdapter.move(0, 1);
        assertEquals(first.id, jobsAdapter.getJobId(0));
        jobsAdapter.move(0, 2);
        assertEquals(done.id, jobsAdapter.getJobId(0));
        assertEquals(third.id, jobsAdapter.getJobId(1));
        assertEquals(first.id, jobsAdapter.getJobId(2));

        DownloadTrackQueueAdapter tracksAdapter =
                new DownloadTrackQueueAdapter(holder -> { });
        DownloadJob.TrackItem waitingA = item("a", DownloadJob.TrackItem.Status.WAITING);
        DownloadJob.TrackItem downloading =
                item("busy", DownloadJob.TrackItem.Status.DOWNLOADING);
        DownloadJob.TrackItem waitingB = item("b", DownloadJob.TrackItem.Status.WAITING);
        tracksAdapter.submit(Arrays.asList(waitingA, downloading, waitingB));
        tracksAdapter.move(0, 1);
        assertEquals("a", tracksAdapter.getVideoId(0));
        tracksAdapter.move(0, 2);
        assertEquals("busy", tracksAdapter.getVideoId(0));
        assertEquals("b", tracksAdapter.getVideoId(1));
        assertEquals("a", tracksAdapter.getVideoId(2));
    }

    @Test
    public void playbackQueueReorderKeepsPlayerIndices() {
        PlaybackQueueAdapter adapter = new PlaybackQueueAdapter(
                new PlaybackQueueAdapter.Listener() {
                    @Override public void onPlay(int position) { }
                    @Override public void onRemove(int position) { }
                    @Override public void onStartDrag(RecyclerView.ViewHolder holder) { }
                });
        adapter.submit(Arrays.asList(
                queueItem("a", 4), queueItem("b", 8), queueItem("c", 12)), "b");

        adapter.move(0, 2);
        assertArrayEquals(new int[]{8, 12, 4}, adapter.getPlayerOrder());
        adapter.move(-1, 0);
        adapter.move(0, 99);
        assertArrayEquals(new int[]{8, 12, 4}, adapter.getPlayerOrder());
    }

    @Test
    public void playlistsAdapterReflectsSubmittedSnapshot() {
        AtomicInteger clicked = new AtomicInteger();
        PlaylistsAdapter adapter = new PlaylistsAdapter(new PlaylistsAdapter.Listener() {
            @Override public void onPlaylistClick(String playlistName) {
                clicked.incrementAndGet();
            }

            @Override public void onPlaylistDelete(PlaylistInfo playlist) { }
        });
        PlaylistInfo first = new PlaylistInfo();
        first.playlistName = "First";
        PlaylistInfo second = new PlaylistInfo();
        second.playlistName = "Second";
        adapter.submit(Arrays.asList(first, second));
        assertEquals(2, adapter.getItemCount());
        adapter.submit(Arrays.asList(second));
        assertEquals(1, adapter.getItemCount());
        assertEquals(0, clicked.get());
    }

    private static Track track(long id, String title, String uploader) {
        Track track = new Track();
        track.id = id;
        track.title = title;
        track.uploader = uploader;
        track.playlistName = "Playlist";
        track.filePath = "/tmp/" + id;
        return track;
    }

    private static DownloadJob job(String name, DownloadJob.Status status) {
        DownloadJob job = new DownloadJob("https://example.test/" + name, name, true);
        job.status = status;
        return job;
    }

    private static DownloadJob.TrackItem item(
            String id, DownloadJob.TrackItem.Status status) {
        DownloadJob.TrackItem item = new DownloadJob.TrackItem(id, id, null, 1, 0);
        item.status = status;
        return item;
    }

    private static PlaybackQueueAdapter.QueueItem queueItem(String id, int playerIndex) {
        return new PlaybackQueueAdapter.QueueItem(
                new MediaItem.Builder().setMediaId(id).setUri("https://example.test/" + id)
                        .build(), playerIndex);
    }
}
