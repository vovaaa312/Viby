package com.example.viby.download;

import androidx.annotation.Nullable;

import java.util.List;

/** Pure state changes for the download queue, kept separate from Android service I/O. */
final class DownloadQueueActions {

    static final class PauseResult {
        @Nullable final DownloadJob job;
        final boolean killActiveProcess;

        PauseResult(@Nullable DownloadJob job, boolean killActiveProcess) {
            this.job = job;
            this.killActiveProcess = killActiveProcess;
        }

        boolean changed() {
            return job != null;
        }
    }

    static final class CancelResult {
        @Nullable final DownloadJob job;
        final boolean killActiveProcess;
        final boolean finishImmediately;

        CancelResult(@Nullable DownloadJob job, boolean killActiveProcess,
                     boolean finishImmediately) {
            this.job = job;
            this.killActiveProcess = killActiveProcess;
            this.finishImmediately = finishImmediately;
        }

        boolean changed() {
            return job != null;
        }
    }

    private DownloadQueueActions() {
    }

    static PauseResult setPaused(List<DownloadJob> jobs,
                                 @Nullable DownloadJob activeJob,
                                 long jobId, boolean paused) {
        DownloadJob job = findJob(jobs, jobId);
        if (job == null || !job.isActive() || job.cancelRequested) {
            return new PauseResult(null, false);
        }
        job.pauseRequested = paused;
        boolean kill = false;
        if (paused) {
            if (job == activeJob) {
                job.pauseInterrupted = true;
                kill = true;
            } else {
                job.status = DownloadJob.Status.PAUSED;
            }
        } else if (job.status == DownloadJob.Status.PAUSED) {
            job.status = DownloadJob.Status.QUEUED;
        }
        return new PauseResult(job, kill);
    }

    static CancelResult cancel(List<DownloadJob> jobs,
                               @Nullable DownloadJob activeJob, long jobId) {
        DownloadJob job = findJob(jobs, jobId);
        if (job == null || !job.isActive()) {
            return new CancelResult(null, false, false);
        }
        job.cancelRequested = true;
        job.pauseRequested = false;
        boolean kill = job == activeJob;
        boolean finishImmediately = !kill;
        if (finishImmediately) {
            job.status = DownloadJob.Status.CANCELED;
        }
        return new CancelResult(job, kill, finishImmediately);
    }

    static boolean moveJob(List<DownloadJob> jobs, long jobId, long targetJobId) {
        int from = indexOfJob(jobs, jobId);
        int to = indexOfJob(jobs, targetJobId);
        if (from < 0 || to < 0 || from == to
                || !jobs.get(from).isActive() || !jobs.get(to).isActive()) {
            return false;
        }
        DownloadJob moved = jobs.remove(from);
        jobs.add(to, moved);
        return true;
    }

    static boolean moveTrack(DownloadJob job, @Nullable String videoId,
                             @Nullable String targetVideoId) {
        if (videoId == null || targetVideoId == null || videoId.equals(targetVideoId)
                || !job.isPlaylist || !job.isActive()) {
            return false;
        }
        int from = indexOfTrack(job, videoId);
        int to = indexOfTrack(job, targetVideoId);
        if (from < 0 || to < 0
                || job.tracks.get(from).status != DownloadJob.TrackItem.Status.WAITING
                || job.tracks.get(to).status != DownloadJob.TrackItem.Status.WAITING) {
            return false;
        }
        DownloadJob.TrackItem moved = job.tracks.remove(from);
        job.tracks.add(to, moved);
        return true;
    }

    @Nullable
    private static DownloadJob findJob(List<DownloadJob> jobs, long jobId) {
        int index = indexOfJob(jobs, jobId);
        return index >= 0 ? jobs.get(index) : null;
    }

    private static int indexOfJob(List<DownloadJob> jobs, long jobId) {
        for (int i = 0; i < jobs.size(); i++) {
            if (jobs.get(i).id == jobId) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfTrack(DownloadJob job, String videoId) {
        for (int i = 0; i < job.tracks.size(); i++) {
            if (videoId.equals(job.tracks.get(i).videoId)) {
                return i;
            }
        }
        return -1;
    }
}
