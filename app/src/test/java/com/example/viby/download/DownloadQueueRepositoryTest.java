package com.example.viby.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.example.viby.data.PendingDownload;
import com.example.viby.data.PendingDownloadDao;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class DownloadQueueRepositoryTest {

    @Test
    public void durableSnapshotContainsRestartState() {
        DownloadJob job = DownloadJobFactory.create(
                "https://youtube.com/playlist?list=PL1", "Saved", true,
                0L, -1L, true, "[\"b\",\"a\"]", 3L);
        job.progress = 73;
        job.error = "runtime only";
        job.networkRetrying = true;

        PendingDownload pending = DownloadQueueRepository.toPending(job, 1234L);

        assertEquals(job.url, pending.url);
        assertEquals("Saved", pending.playlistName);
        assertTrue(pending.isPlaylist);
        assertEquals(1234L, pending.createdAt);
        assertEquals(3L, pending.queuePosition);
        assertTrue(pending.paused);
        assertEquals("[\"b\",\"a\"]", pending.trackOrderJson);
    }

    @Test
    public void repositoryOwnsAllDurableQueueOperations() {
        FakeDao dao = new FakeDao();
        DownloadQueueRepository repository = new DownloadQueueRepository(dao);
        DownloadJob job = DownloadJobFactory.create(
                "https://youtu.be/a", "Queue", false,
                0L, -1L, false, null, 4L);

        repository.insert(job, 99L);
        assertEquals(1L, job.pendingId);
        assertEquals(1, repository.loadAll().size());

        job.pauseRequested = true;
        job.queuePosition = 2L;
        repository.updatePaused(job);
        repository.updateQueuePosition(job);
        repository.updateTrackOrder(job, "[\"a\"]");
        assertTrue(dao.stored.paused);
        assertEquals(2L, dao.stored.queuePosition);
        assertEquals("[\"a\"]", dao.stored.trackOrderJson);

        repository.delete(job.pendingId);
        assertEquals(0, repository.loadAll().size());
    }

    private static final class FakeDao implements PendingDownloadDao {
        PendingDownload stored;

        @Override
        public long insert(PendingDownload pending) {
            pending.id = 1L;
            stored = pending;
            return pending.id;
        }

        @Override
        public void delete(long id) {
            if (stored != null && stored.id == id) {
                stored = null;
            }
        }

        @Override
        public List<PendingDownload> getAllSync() {
            List<PendingDownload> result = new ArrayList<>();
            if (stored != null) {
                result.add(stored);
            }
            return result;
        }

        @Override
        public void updateQueuePosition(long id, long position) {
            stored.queuePosition = position;
        }

        @Override
        public void updatePaused(long id, boolean paused) {
            stored.paused = paused;
        }

        @Override
        public void updateTrackOrder(long id, String trackOrderJson) {
            stored.trackOrderJson = trackOrderJson;
        }
    }
}
