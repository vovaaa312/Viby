package com.example.viby.download;

import androidx.annotation.Nullable;

import java.util.List;

/** Pure scheduling rules for the sequential download queue. */
final class DownloadScheduler {

    private DownloadScheduler() {
    }

    @Nullable
    static DownloadJob nextRunnable(List<DownloadJob> jobs) {
        for (DownloadJob job : jobs) {
            if (job.status == DownloadJob.Status.QUEUED
                    && !job.pauseRequested && !job.cancelRequested) {
                return job;
            }
        }
        return null;
    }

    static long nextQueuePosition(List<DownloadJob> jobs) {
        long position = 0L;
        for (DownloadJob job : jobs) {
            if (job.isActive()) {
                position = Math.max(position, job.queuePosition + 1L);
            }
        }
        return position;
    }

    static void sort(List<DownloadJob> jobs) {
        jobs.sort((left, right) -> {
            if (left.isActive() != right.isActive()) {
                return left.isActive() ? -1 : 1;
            }
            return Long.compare(left.queuePosition, right.queuePosition);
        });
    }

    static void normalizeActivePositions(List<DownloadJob> jobs) {
        long position = 0L;
        for (DownloadJob job : jobs) {
            if (job.isActive()) {
                job.queuePosition = position++;
            }
        }
    }
}
