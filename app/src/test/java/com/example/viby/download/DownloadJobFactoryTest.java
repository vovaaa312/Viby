package com.example.viby.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.example.viby.data.PendingDownload;

import org.junit.Test;

public class DownloadJobFactoryTest {

    @Test
    public void processRestartRecreatesPausedJobFromDurableRecord() {
        DownloadJob beforeRestart = DownloadJobFactory.create(
                " https://youtube.com/playlist?list=PL1 ", "Road trip", true,
                42L, 7L, true, "[\"video-b\",\"video-a\"]", 99L);

        PendingDownload persisted = new PendingDownload();
        persisted.id = beforeRestart.pendingId;
        persisted.url = beforeRestart.url;
        persisted.playlistName = beforeRestart.playlistName;
        persisted.isPlaylist = beforeRestart.isPlaylist;
        persisted.queuePosition = beforeRestart.queuePosition;
        persisted.paused = beforeRestart.pauseRequested;
        persisted.trackOrderJson = beforeRestart.trackOrderJson;

        DownloadJob afterRestart = DownloadJobFactory.restore(persisted);

        assertNotEquals(beforeRestart.id, afterRestart.id);
        assertEquals(42L, afterRestart.pendingId);
        assertEquals("https://youtube.com/playlist?list=PL1", afterRestart.url);
        assertEquals("Road trip", afterRestart.playlistName);
        assertTrue(afterRestart.isPlaylist);
        assertEquals(7L, afterRestart.queuePosition);
        assertTrue(afterRestart.pauseRequested);
        assertEquals(DownloadJob.Status.PAUSED, afterRestart.status);
        assertEquals("[\"video-b\",\"video-a\"]", afterRestart.trackOrderJson);
        assertTrue(afterRestart.tracks.isEmpty());
    }

    @Test
    public void newJobUsesFallbackPositionAndStartsQueued() {
        DownloadJob job = DownloadJobFactory.create(
                "https://youtu.be/video", null, false,
                0L, -1L, false, null, 5L);

        assertEquals(0L, job.pendingId);
        assertEquals(5L, job.queuePosition);
        assertFalse(job.pauseRequested);
        assertEquals(DownloadJob.Status.QUEUED, job.status);
    }
}
