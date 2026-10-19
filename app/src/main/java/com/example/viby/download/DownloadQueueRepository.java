package com.example.viby.download;

import com.example.viby.data.PendingDownload;
import com.example.viby.data.PendingDownloadDao;

import java.util.List;

/** Durable storage boundary for unfinished download jobs. */
final class DownloadQueueRepository {

    private final PendingDownloadDao dao;

    DownloadQueueRepository(PendingDownloadDao dao) {
        this.dao = dao;
    }

    List<PendingDownload> loadAll() {
        return dao.getAllSync();
    }

    void insert(DownloadJob job, long createdAt) {
        job.pendingId = dao.insert(toPending(job, createdAt));
    }

    void delete(long pendingId) {
        if (pendingId != 0L) {
            dao.delete(pendingId);
        }
    }

    void updatePaused(DownloadJob job) {
        if (job.pendingId != 0L) {
            dao.updatePaused(job.pendingId, job.pauseRequested);
        }
    }

    void updateQueuePosition(DownloadJob job) {
        if (job.pendingId != 0L && job.isActive()) {
            dao.updateQueuePosition(job.pendingId, job.queuePosition);
        }
    }

    void updateTrackOrder(DownloadJob job, String trackOrderJson) {
        if (job.pendingId != 0L) {
            dao.updateTrackOrder(job.pendingId, trackOrderJson);
        }
    }

    static PendingDownload toPending(DownloadJob job, long createdAt) {
        PendingDownload pending = new PendingDownload();
        pending.url = job.url;
        pending.playlistName = job.playlistName;
        pending.isPlaylist = job.isPlaylist;
        pending.createdAt = createdAt;
        pending.queuePosition = job.queuePosition;
        pending.paused = job.pauseRequested;
        pending.trackOrderJson = job.trackOrderJson;
        return pending;
    }
}
