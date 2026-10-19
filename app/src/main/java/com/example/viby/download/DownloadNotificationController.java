package com.example.viby.download;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.viby.R;
import com.example.viby.VibyApp;
import com.example.viby.util.Formats;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns download notifications and the foreground-service notification lifecycle. */
final class DownloadNotificationController {

    private static final int PROGRESS_NOTIFICATION_ID = 1;

    private final DownloadService service;
    private final NotificationManager manager;
    private final AtomicInteger resultIdSequence = new AtomicInteger(100);

    DownloadNotificationController(DownloadService service) {
        this.service = service;
        this.manager = service.getSystemService(NotificationManager.class);
    }

    void startForeground(DownloadJob job) {
        service.startForeground(PROGRESS_NOTIFICATION_ID, buildProgress(job));
    }

    void updateProgress(DownloadJob job) {
        manager.notify(PROGRESS_NOTIFICATION_ID, buildProgress(job));
    }

    void refreshForegroundState(int pendingCount, @Nullable DownloadJob activeJob,
                                List<DownloadJob> jobs) {
        if (pendingCount <= 0) {
            service.stopForeground(DownloadService.STOP_FOREGROUND_REMOVE);
            service.stopSelf();
            return;
        }
        DownloadJob notificationJob = selectNotificationJob(activeJob, jobs);
        if (notificationJob != null) {
            updateProgress(notificationJob);
        }
    }

    void postResult(DownloadJob job) {
        String text;
        switch (job.status) {
            case DONE:
            case COMPLETED_WITH_ERRORS:
                text = job.isPlaylist
                        ? service.getString(R.string.notif_download_summary,
                                job.totalCount - job.failedCount, job.failedCount)
                        : service.getString(R.string.notif_download_done, job.title);
                break;
            case FAILED:
                text = service.getString(R.string.notif_download_failed,
                        job.error != null ? job.error : job.title);
                break;
            default:
                return;
        }
        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(service, VibyApp.CHANNEL_DOWNLOADS)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle(job.title != null ? job.title : job.url)
                        .setContentText(text)
                        .setAutoCancel(true);
        if (!job.failedTitles.isEmpty()) {
            StringBuilder big = new StringBuilder(text).append('\n')
                    .append(service.getString(R.string.failed_tracks_header));
            synchronized (job.failedTitles) {
                for (String title : job.failedTitles) {
                    big.append("\n• ").append(title);
                }
            }
            builder.setStyle(new NotificationCompat.BigTextStyle().bigText(big.toString()));
        }
        manager.notify(resultIdSequence.getAndIncrement(), builder.build());
    }

    private Notification buildProgress(DownloadJob job) {
        int requestCode = (int) (job.id % 100_000L) * 2;
        PendingIntent cancelIntent = PendingIntent.getService(
                service, requestCode,
                new Intent(service, DownloadService.class)
                        .setAction(DownloadService.ACTION_CANCEL_JOB)
                        .putExtra(DownloadService.EXTRA_JOB_ID, job.id),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean paused = job.status == DownloadJob.Status.PAUSED || job.pauseRequested;
        PendingIntent pauseIntent = PendingIntent.getService(
                service, requestCode + 1,
                new Intent(service, DownloadService.class)
                        .setAction(paused
                                ? DownloadService.ACTION_RESUME_JOB
                                : DownloadService.ACTION_PAUSE_JOB)
                        .putExtra(DownloadService.EXTRA_JOB_ID, job.id),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String playlistProgress = job.isPlaylist && job.totalCount > 0
                ? service.getString(R.string.notif_download_playlist_progress,
                        job.currentIndex, job.totalCount) : null;
        String sizeProgress = job.totalBytes > 0
                ? Formats.size(service, job.downloadedBytes) + " / "
                        + Formats.size(service, job.totalBytes) : null;
        String text = joinProgressText(
                job.waitingForNetwork || job.networkRetrying
                        ? service.getString(R.string.status_waiting_for_network) : null,
                playlistProgress,
                sizeProgress,
                paused ? service.getString(R.string.status_paused) : null);

        return new NotificationCompat.Builder(service, VibyApp.CHANNEL_DOWNLOADS)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(service.getString(R.string.notif_downloading,
                        trackName(job)))
                .setContentText(text.isEmpty() ? null : text)
                .setProgress(100, job.progress,
                        job.status == DownloadJob.Status.PREPARING)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(0, service.getString(paused
                        ? R.string.btn_resume_download
                        : R.string.btn_pause_download), pauseIntent)
                .addAction(0, service.getString(R.string.btn_cancel), cancelIntent)
                .build();
    }

    static String trackName(DownloadJob job) {
        return job.currentTrackTitle != null
                ? job.currentTrackTitle : (job.title != null ? job.title : job.url);
    }

    static String joinProgressText(@Nullable String... parts) {
        StringBuilder text = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append(" · ");
            }
            text.append(part);
        }
        return text.toString();
    }

    @Nullable
    static DownloadJob selectNotificationJob(@Nullable DownloadJob activeJob,
                                             List<DownloadJob> jobs) {
        if (activeJob != null && activeJob.isActive()) {
            return activeJob;
        }
        synchronized (jobs) {
            for (DownloadJob candidate : jobs) {
                if (candidate.isActive()) {
                    return candidate;
                }
            }
        }
        return null;
    }
}
