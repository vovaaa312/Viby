package com.example.viby.download;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.viby.R;
import com.example.viby.VibyApp;
import com.example.viby.data.Track;
import com.example.viby.data.TrackDao;
import com.example.viby.data.VibyDatabase;
import com.example.viby.util.Formats;
import com.example.viby.util.StorageHelper;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;
import com.yausername.youtubedl_android.mapper.VideoInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kotlin.Unit;

/**
 * Foreground-сервис с последовательной очередью загрузок yt-dlp.
 * Треки и плейлисты добавляются интентом ACTION_ENQUEUE; загрузку можно
 * ставить на паузу (процесс yt-dlp убивается, докачка продолжается
 * с .part-файла) и возобновлять.
 */
public class DownloadService extends Service {

    private static final String TAG = "DownloadService";

    public static final String ACTION_ENQUEUE = "com.example.viby.action.ENQUEUE";
    public static final String ACTION_CANCEL_JOB = "com.example.viby.action.CANCEL_JOB";
    public static final String ACTION_PAUSE_JOB = "com.example.viby.action.PAUSE_JOB";
    public static final String ACTION_RESUME_JOB = "com.example.viby.action.RESUME_JOB";
    public static final String ACTION_MOVE_JOB = "com.example.viby.action.MOVE_JOB";
    public static final String ACTION_MOVE_TRACK = "com.example.viby.action.MOVE_TRACK";
    public static final String ACTION_PRIORITIZE_TRACK =
            "com.example.viby.action.PRIORITIZE_TRACK";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_PLAYLIST = "playlist";
    public static final String EXTRA_IS_PLAYLIST = "is_playlist";
    public static final String EXTRA_PENDING_ID = "pending_id";
    public static final String EXTRA_JOB_ID = "job_id";
    public static final String EXTRA_TARGET_JOB_ID = "target_job_id";
    public static final String EXTRA_TRACK_ID = "track_id";
    public static final String EXTRA_TARGET_TRACK_ID = "target_track_id";
    private static final String EXTRA_QUEUE_POSITION = "queue_position";
    private static final String EXTRA_PAUSED = "paused";
    private static final String EXTRA_TRACK_ORDER = "track_order";

    private static final int NOTIF_PROGRESS_ID = 1;
    private static final int ENGINE_WAIT_SECONDS = 60;

    /** Клиенты для повторной попытки: tv/mweb обходят ложное «Video unavailable». */
    private static final String ALT_PLAYER_CLIENTS = "default,tv,mweb";

    /** "45.2% of ~ 4.32MiB at ..." — вытаскиваем общий размер трека. */
    private static final Pattern SIZE_PATTERN =
            Pattern.compile("of\\s+~?\\s*([0-9.]+)(KiB|MiB|GiB)");

    private static final MutableLiveData<List<DownloadJob>> jobsLive =
            new MutableLiveData<>(new ArrayList<>());

    public static LiveData<List<DownloadJob>> getJobs() {
        return jobsLive;
    }

    public static void enqueue(Context context, String url, @Nullable String playlist,
                               boolean isPlaylist) {
        Intent intent = new Intent(context, DownloadService.class)
                .setAction(ACTION_ENQUEUE)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_PLAYLIST, playlist)
                .putExtra(EXTRA_IS_PLAYLIST, isPlaylist);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void sendJobAction(Context context, String action, long jobId) {
        context.startService(new Intent(context, DownloadService.class)
                .setAction(action)
                .putExtra(EXTRA_JOB_ID, jobId));
    }

    public static void moveJob(Context context, long jobId, long targetJobId) {
        context.startService(new Intent(context, DownloadService.class)
                .setAction(ACTION_MOVE_JOB)
                .putExtra(EXTRA_JOB_ID, jobId)
                .putExtra(EXTRA_TARGET_JOB_ID, targetJobId));
    }

    public static void moveTrack(Context context, long jobId,
                                 String videoId, String targetVideoId) {
        context.startService(new Intent(context, DownloadService.class)
                .setAction(ACTION_MOVE_TRACK)
                .putExtra(EXTRA_JOB_ID, jobId)
                .putExtra(EXTRA_TRACK_ID, videoId)
                .putExtra(EXTRA_TARGET_TRACK_ID, targetVideoId));
    }

    public static void prioritizeTrack(Context context, String playlist, String videoId) {
        context.startService(new Intent(context, DownloadService.class)
                .setAction(ACTION_PRIORITIZE_TRACK)
                .putExtra(EXTRA_PLAYLIST, playlist)
                .putExtra(EXTRA_TRACK_ID, videoId));
    }

    /**
     * Восстановить незавершённые загрузки после краша/перезагрузки/обновления.
     * Уже скачанные треки плейлистов пропускаются, так что докачка продолжается
     * ровно с того места, где остановилась.
     */
    public static void restorePending(Context context) {
        Context app = context.getApplicationContext();
        VibyDatabase.dbExecutor.execute(() -> {
            for (com.example.viby.data.PendingDownload pending :
                    VibyDatabase.get(app).pendingDownloadDao().getAllSync()) {
                Intent intent = new Intent(app, DownloadService.class)
                        .setAction(ACTION_ENQUEUE)
                        .putExtra(EXTRA_URL, pending.url)
                        .putExtra(EXTRA_PLAYLIST, pending.playlistName)
                        .putExtra(EXTRA_IS_PLAYLIST, pending.isPlaylist)
                        .putExtra(EXTRA_PENDING_ID, pending.id)
                        .putExtra(EXTRA_QUEUE_POSITION, pending.queuePosition)
                        .putExtra(EXTRA_PAUSED, pending.paused)
                        .putExtra(EXTRA_TRACK_ORDER, pending.trackOrderJson);
                try {
                    ContextCompat.startForegroundService(app, intent);
                } catch (Exception e) {
                    Log.w(TAG, "restore enqueue failed", e);
                }
            }
        });
    }

    private final List<DownloadJob> jobs = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger pendingCount = new AtomicInteger(0);
    private final AtomicInteger notifIdSeq = new AtomicInteger(100);
    private boolean dispatcherRunning;
    private volatile String activeProcessId;
    @Nullable
    private volatile DownloadJob activeJob;

    private NotificationManager notificationManager;
    private TrackDao dao;
    private long lastPublishMs;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = getSystemService(NotificationManager.class);
        dao = VibyDatabase.get(this).trackDao();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            return START_NOT_STICKY;
        }
        switch (intent.getAction()) {
            case ACTION_CANCEL_JOB:
                cancelJob(intent.getLongExtra(EXTRA_JOB_ID, -1L));
                break;
            case ACTION_PAUSE_JOB:
                setJobPaused(intent.getLongExtra(EXTRA_JOB_ID, -1L), true);
                break;
            case ACTION_RESUME_JOB:
                setJobPaused(intent.getLongExtra(EXTRA_JOB_ID, -1L), false);
                break;
            case ACTION_MOVE_JOB:
                moveJobInternal(intent.getLongExtra(EXTRA_JOB_ID, -1L),
                        intent.getLongExtra(EXTRA_TARGET_JOB_ID, -1L));
                break;
            case ACTION_MOVE_TRACK:
                moveTrackInternal(intent.getLongExtra(EXTRA_JOB_ID, -1L),
                        intent.getStringExtra(EXTRA_TRACK_ID),
                        intent.getStringExtra(EXTRA_TARGET_TRACK_ID));
                break;
            case ACTION_PRIORITIZE_TRACK:
                prioritizeTrackInternal(intent.getStringExtra(EXTRA_PLAYLIST),
                        intent.getStringExtra(EXTRA_TRACK_ID));
                break;
            case ACTION_ENQUEUE:
                String url = intent.getStringExtra(EXTRA_URL);
                String playlist = intent.getStringExtra(EXTRA_PLAYLIST);
                boolean isPlaylist = intent.getBooleanExtra(EXTRA_IS_PLAYLIST, false);
                long pendingId = intent.getLongExtra(EXTRA_PENDING_ID, 0L);
                long restoredPosition = intent.getLongExtra(EXTRA_QUEUE_POSITION, -1L);
                boolean restoredPaused = intent.getBooleanExtra(EXTRA_PAUSED, false);
                String restoredTrackOrder = intent.getStringExtra(EXTRA_TRACK_ORDER);
                if (url != null && !url.trim().isEmpty()
                        && !hasActiveJob(url.trim(), playlist)) {
                    DownloadJob job = new DownloadJob(url.trim(), playlist, isPlaylist);
                    job.pendingId = pendingId;
                    synchronized (jobs) {
                        job.queuePosition = restoredPosition >= 0
                                ? restoredPosition : nextQueuePositionLocked();
                        job.pauseRequested = restoredPaused;
                        job.trackOrderJson = restoredTrackOrder;
                        job.status = restoredPaused
                                ? DownloadJob.Status.PAUSED : DownloadJob.Status.QUEUED;
                        jobs.add(job);
                        sortJobsLocked();
                    }
                    if (pendingId == 0) {
                        persistPending(job);
                    }
                    pendingCount.incrementAndGet();
                    publish(true);
                    startForeground(NOTIF_PROGRESS_ID, buildProgressNotification(job));
                    scheduleDispatcher();
                }
                break;
            default:
                break;
        }
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------- queue

    private void scheduleDispatcher() {
        synchronized (jobs) {
            if (dispatcherRunning) {
                return;
            }
            dispatcherRunning = true;
        }
        worker.execute(this::dispatchJobs);
    }

    private void dispatchJobs() {
        while (true) {
            DownloadJob next;
            synchronized (jobs) {
                next = nextRunnableJobLocked();
                if (next == null) {
                    dispatcherRunning = false;
                    return;
                }
            }
            runJob(next);
        }
    }

    @Nullable
    private DownloadJob nextRunnableJobLocked() {
        for (DownloadJob job : jobs) {
            if (job.status == DownloadJob.Status.QUEUED
                    && !job.pauseRequested && !job.cancelRequested) {
                return job;
            }
        }
        return null;
    }

    private void runJob(DownloadJob job) {
        activeJob = job;
        boolean terminal = false;
        try {
            if (job.cancelRequested) {
                job.status = DownloadJob.Status.CANCELED;
                terminal = true;
                return;
            }
            throwIfPaused(job);
            waitForEngine();
            throwIfPaused(job);
            if (job.isPlaylist) {
                runPlaylistJob(job);
            } else {
                runSingleJob(job);
            }
            terminal = job.status == DownloadJob.Status.DONE
                    || job.status == DownloadJob.Status.CANCELED;
        } catch (Exception e) {
            if (job.cancelRequested) {
                job.status = DownloadJob.Status.CANCELED;
                terminal = true;
            } else if (job.pauseInterrupted || e instanceof JobPausedException) {
                job.pauseInterrupted = false;
                job.status = job.pauseRequested
                        ? DownloadJob.Status.PAUSED : DownloadJob.Status.QUEUED;
            } else if (job.yieldInterrupted || e instanceof JobYieldException) {
                job.yieldInterrupted = false;
                job.status = DownloadJob.Status.QUEUED;
            } else {
                Log.e(TAG, "job failed: " + job.url, e);
                job.status = DownloadJob.Status.FAILED;
                job.error = shortError(e);
                terminal = true;
            }
        } finally {
            activeJob = null;
            if (!terminal) {
                persistPaused(job);
            }
            removePending(job); // задание завершилось — восстанавливать больше нечего
            publish(true);
            if (terminal) {
                if (job.status == DownloadJob.Status.CANCELED) {
                    cleanupPendingPlaceholders(job);
                }
                postResultNotification(job);
            }
            refreshForegroundState();
        }
    }

    private static final class JobPausedException extends Exception {
    }

    private static final class JobYieldException extends Exception {
    }

    /** Куки залогиненного аккаунта — снимают возрастные ограничения. */
    private void applyCookies(YoutubeDLRequest request) {
        if (com.example.viby.util.YtCookies.isLoggedIn(this)) {
            request.addOption("--cookies",
                    com.example.viby.util.YtCookies.file(this).getAbsolutePath());
        }
    }

    private boolean hasActiveJob(String url, @Nullable String playlist) {
        synchronized (jobs) {
            for (DownloadJob job : jobs) {
                if (job.isActive() && job.url.equals(url)
                        && java.util.Objects.equals(job.playlistName, playlist)) {
                    return true;
                }
            }
        }
        return false;
    }

    private long nextQueuePositionLocked() {
        long position = 0L;
        for (DownloadJob job : jobs) {
            if (job.isActive()) {
                position = Math.max(position, job.queuePosition + 1L);
            }
        }
        return position;
    }

    private void sortJobsLocked() {
        jobs.sort((left, right) -> {
            if (left.isActive() != right.isActive()) {
                return left.isActive() ? -1 : 1;
            }
            return Long.compare(left.queuePosition, right.queuePosition);
        });
    }

    private void persistPending(DownloadJob job) {
        VibyDatabase.dbExecutor.execute(() -> {
            com.example.viby.data.PendingDownload pending =
                    new com.example.viby.data.PendingDownload();
            pending.url = job.url;
            pending.playlistName = job.playlistName;
            pending.isPlaylist = job.isPlaylist;
            pending.createdAt = System.currentTimeMillis();
            pending.queuePosition = job.queuePosition;
            pending.paused = job.pauseRequested;
            pending.trackOrderJson = job.trackOrderJson;
            job.pendingId = VibyDatabase.get(this).pendingDownloadDao().insert(pending);
        });
    }

    private void removePending(DownloadJob job) {
        synchronized (job) {
            if (job.completionHandled || job.isActive()) {
                return;
            }
            job.completionHandled = true;
        }
        // dbExecutor последовательный: insert из persistPending выполнится раньше
        VibyDatabase.dbExecutor.execute(() -> {
            if (job.pendingId != 0) {
                VibyDatabase.get(this).pendingDownloadDao().delete(job.pendingId);
            }
        });
        pendingCount.decrementAndGet();
    }

    private void persistPaused(DownloadJob job) {
        VibyDatabase.dbExecutor.execute(() -> {
            if (job.pendingId != 0) {
                VibyDatabase.get(this).pendingDownloadDao()
                        .updatePaused(job.pendingId, job.pauseRequested);
            }
        });
    }

    private void persistQueueOrder() {
        List<DownloadJob> snapshot;
        synchronized (jobs) {
            long position = 0L;
            for (DownloadJob job : jobs) {
                if (job.isActive()) {
                    job.queuePosition = position++;
                }
            }
            snapshot = new ArrayList<>(jobs);
        }
        VibyDatabase.dbExecutor.execute(() -> {
            com.example.viby.data.PendingDownloadDao pendingDao =
                    VibyDatabase.get(this).pendingDownloadDao();
            for (DownloadJob job : snapshot) {
                if (job.isActive() && job.pendingId != 0) {
                    pendingDao.updateQueuePosition(job.pendingId, job.queuePosition);
                }
            }
        });
    }

    private void runSingleJob(DownloadJob job) throws Exception {
        job.status = DownloadJob.Status.PREPARING;
        publish(true);
        updateProgressNotification(job);

        YoutubeDLRequest infoRequest = new YoutubeDLRequest(job.url);
        applyCookies(infoRequest);
        VideoInfo info = YoutubeDL.getInstance().getInfo(infoRequest);
        String videoId = info.getId();
        String title = info.getTitle() != null ? info.getTitle() : job.url;
        job.title = title;
        job.currentTrackTitle = title;
        String playlist = job.playlistName != null && !job.playlistName.isEmpty()
                ? job.playlistName : getString(R.string.default_playlist);
        job.playlistName = playlist;
        publish(true);

        if (videoId != null && dao.isDownloaded(playlist, videoId)) {
            job.status = DownloadJob.Status.DONE;
            return;
        }

        long durationMs = info.getDuration() * 1000L;
        downloadTrack(job, job.url, videoId, title, info.getUploader(), durationMs,
                playlist, null);
        job.status = job.cancelRequested
                ? DownloadJob.Status.CANCELED : DownloadJob.Status.DONE;
    }

    private void runPlaylistJob(DownloadJob job) throws Exception {
        job.status = DownloadJob.Status.PREPARING;
        publish(true);
        updateProgressNotification(job);

        YoutubeDLRequest infoRequest = new YoutubeDLRequest(job.url);
        infoRequest.addOption("--flat-playlist");
        infoRequest.addOption("--dump-single-json");
        infoRequest.addOption("--no-warnings");
        applyCookies(infoRequest);
        String processId = "job-" + job.id + "-info";
        activeProcessId = processId;
        YoutubeDLResponse response;
        try {
            response = YoutubeDL.getInstance().execute(infoRequest, processId, null);
        } finally {
            activeProcessId = null;
        }

        JSONObject root = new JSONObject(response.getOut());
        String playlistTitle = root.optString("title", "Playlist");
        String playlist = job.playlistName != null && !job.playlistName.isEmpty()
                ? job.playlistName : StorageHelper.sanitize(playlistTitle);
        job.playlistName = playlist;
        job.title = playlistTitle;

        // запоминаем источник — для кнопки «обновить плейлист»
        com.example.viby.data.PlaylistSource source = new com.example.viby.data.PlaylistSource();
        source.playlistName = playlist;
        source.sourceUrl = job.url;
        source.updatedAt = System.currentTimeMillis();
        VibyDatabase.get(this).playlistSourceDao().upsert(source);

        JSONArray entries = root.optJSONArray("entries");
        if (entries == null || entries.length() == 0) {
            throw new IllegalStateException(getString(R.string.error_playlist_empty));
        }
        job.totalCount = entries.length();
        publish(true);

        java.util.Map<String, Integer> youtubePositions = new java.util.HashMap<>();
        for (int i = 0; i < entries.length(); i++) {
            String videoId = entries.getJSONObject(i).optString("id", "");
            if (!videoId.isEmpty()) {
                youtubePositions.put(videoId, i);
            }
        }
        prepareTrackQueue(job, entries);
        ensurePlaceholderTracks(job, playlist);
        applyYoutubeOrder(playlist, youtubePositions);

        while (true) {
            if (job.cancelRequested) {
                break;
            }
            waitWhilePaused(job);
            if (job.cancelRequested) {
                break;
            }
            DownloadJob.TrackItem item = nextTrack(job);
            if (item == null) {
                break;
            }
            item.status = DownloadJob.TrackItem.Status.DOWNLOADING;
            item.progress = 0;
            job.activeTrack = item;
            job.currentIndex = completedTrackCount(job) + 1;
            job.progress = 0;
            job.downloadedBytes = 0;
            job.totalBytes = 0;
            job.currentTrackTitle = item.title;
            publish(true);
            updateProgressNotification(job);
            try {
                if (item.videoId.isEmpty() || dao.isDownloaded(playlist, item.videoId)) {
                    item.progress = 100;
                    item.status = DownloadJob.TrackItem.Status.DONE;
                    continue;
                }
                String videoUrl = "https://www.youtube.com/watch?v=" + item.videoId;
                downloadTrack(job, videoUrl, item.videoId, item.title, item.uploader,
                        item.durationMs, playlist, item.youtubePosition);
                item.progress = 100;
                item.status = DownloadJob.TrackItem.Status.DONE;
            } catch (Exception e) {
                if (job.cancelRequested) {
                    item.status = DownloadJob.TrackItem.Status.WAITING;
                    break;
                }
                if (job.pauseRequested || job.pauseInterrupted || job.yieldInterrupted
                        || e instanceof JobPausedException || e instanceof JobYieldException) {
                    item.status = DownloadJob.TrackItem.Status.WAITING;
                    throw e;
                }
                item.status = DownloadJob.TrackItem.Status.FAILED;
                Log.w(TAG, "playlist entry failed: " + item.title, e);
                job.failedCount++;
                job.failedTitles.add(item.title);
            } finally {
                if (job.activeTrack == item) {
                    job.activeTrack = null;
                }
            }
        }
        applyYoutubeOrder(playlist, youtubePositions);
        job.status = job.cancelRequested
                ? DownloadJob.Status.CANCELED : DownloadJob.Status.DONE;
    }

    private void prepareTrackQueue(DownloadJob job, JSONArray entries) throws Exception {
        synchronized (job.tracks) {
            if (!job.tracks.isEmpty()) {
                return;
            }
            java.util.Map<String, DownloadJob.TrackItem> byId =
                    new java.util.LinkedHashMap<>();
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                String videoId = entry.optString("id", "");
                if (videoId.isEmpty()) {
                    continue;
                }
                String title = entry.optString("title", videoId);
                String uploader = entry.optString("uploader",
                        entry.optString("channel", null));
                long durationMs = (long) (entry.optDouble("duration", 0) * 1000);
                byId.put(videoId, new DownloadJob.TrackItem(
                        videoId, title, uploader, durationMs, i));
            }
            if (job.trackOrderJson != null && !job.trackOrderJson.isEmpty()) {
                JSONArray restoredOrder = new JSONArray(job.trackOrderJson);
                for (int i = 0; i < restoredOrder.length(); i++) {
                    DownloadJob.TrackItem restored = byId.remove(
                            restoredOrder.optString(i, ""));
                    if (restored != null) {
                        job.tracks.add(restored);
                    }
                }
            }
            job.tracks.addAll(byId.values());
            job.trackOrderJson = serializeTrackOrderLocked(job);
        }
        persistTrackOrder(job);
        publish(true);
    }

    @Nullable
    private DownloadJob.TrackItem nextTrack(DownloadJob job) {
        synchronized (job.tracks) {
            for (DownloadJob.TrackItem item : job.tracks) {
                if (item.status == DownloadJob.TrackItem.Status.WAITING) {
                    return item;
                }
            }
        }
        return null;
    }

    private int completedTrackCount(DownloadJob job) {
        int count = 0;
        synchronized (job.tracks) {
            for (DownloadJob.TrackItem item : job.tracks) {
                if (item.status == DownloadJob.TrackItem.Status.DONE
                        || item.status == DownloadJob.TrackItem.Status.FAILED) {
                    count++;
                }
            }
        }
        return count;
    }

    private void ensurePlaceholderTracks(DownloadJob job, String playlist) {
        synchronized (job.tracks) {
            for (DownloadJob.TrackItem item : job.tracks) {
                Track existing = dao.getByVideoIdSync(playlist, item.videoId);
                if (existing != null) {
                    if (existing.downloaded) {
                        item.status = DownloadJob.TrackItem.Status.DONE;
                    } else {
                        existing.title = item.title;
                        existing.uploader = item.uploader;
                        existing.durationMs = item.durationMs;
                        existing.youtubePosition = item.youtubePosition;
                        existing.thumbnailUrl = "https://i.ytimg.com/vi/"
                                + item.videoId + "/hqdefault.jpg";
                        existing.sourceUrl = "https://www.youtube.com/watch?v="
                                + item.videoId;
                        dao.update(existing);
                    }
                    continue;
                }
                Track placeholder = new Track();
                placeholder.videoId = item.videoId;
                placeholder.title = item.title;
                placeholder.uploader = item.uploader;
                placeholder.durationMs = item.durationMs;
                placeholder.playlistName = playlist;
                placeholder.thumbnailUrl = "https://i.ytimg.com/vi/"
                        + item.videoId + "/hqdefault.jpg";
                placeholder.position = dao.nextPosition(playlist);
                placeholder.youtubePosition = item.youtubePosition;
                placeholder.createdAt = System.currentTimeMillis();
                placeholder.downloaded = false;
                placeholder.sourceUrl = "https://www.youtube.com/watch?v=" + item.videoId;
                dao.insert(placeholder);
            }
        }
    }

    private void applyYoutubeOrder(String playlist,
                                   java.util.Map<String, Integer> youtubePositions) {
        java.util.List<Track> tracks = dao.getPlaylistSync(playlist);
        com.example.viby.data.YoutubePlaylistOrder.apply(tracks, youtubePositions);
        if (!tracks.isEmpty()) {
            dao.updateAll(tracks);
        }
    }

    // ------------------------------------------------------------- download

    private void downloadTrack(DownloadJob job, String videoUrl, String videoId,
                               String title, @Nullable String uploader,
                               long durationMs, String playlist,
                               @Nullable Integer youtubePosition) throws Exception {
        job.status = DownloadJob.Status.DOWNLOADING;
        job.currentTrackTitle = title;
        job.progress = 0;
        job.downloadedBytes = 0;
        job.totalBytes = 0;
        File dir = StorageHelper.playlistDir(this, playlist);
        String baseName = StorageHelper.sanitize(title);
        // защита от одинаковых названий разных видео
        if (new File(dir, baseName + ".mp3").exists()
                || new File(dir, baseName + ".m4a").exists()) {
            baseName = baseName + " [" + videoId + "]";
        }

        File resultFile;
        try {
            resultFile = downloadWithPauseRetry(job, videoUrl, dir, baseName, "mp3", null);
        } catch (Exception e) {
            if (job.cancelRequested || job.pauseRequested || job.pauseInterrupted
                    || job.yieldInterrupted || e instanceof JobPausedException
                    || e instanceof JobYieldException) {
                throw e;
            }
            // Многие «Video unavailable» — это блокировка дефолтного клиента yt-dlp,
            // а не мёртвое видео. Пробуем другие player-client (tv/mweb их обходят).
            // Реально удалённые/заблокированные видео повторять смысла нет.
            if (isPermanentlyUnavailable(e)) {
                throw e;
            }
            Log.w(TAG, "retrying with alternate player clients: " + title, e);
            try {
                resultFile = downloadWithPauseRetry(job, videoUrl, dir, baseName,
                        "mp3", ALT_PLAYER_CLIENTS);
            } catch (Exception e2) {
                if (job.cancelRequested || job.pauseRequested || job.pauseInterrupted
                        || job.yieldInterrupted || e2 instanceof JobPausedException
                        || e2 instanceof JobYieldException) {
                    throw e2;
                }
                // последний шанс: другой контейнер + альтернативные клиенты
                Log.w(TAG, "mp3 failed, retrying as m4a: " + title, e2);
                resultFile = downloadWithPauseRetry(job, videoUrl, dir, baseName,
                        "m4a", ALT_PLAYER_CLIENTS);
            }
        }

        Track track = videoId != null
                ? dao.getByVideoIdSync(playlist, videoId) : null;
        boolean updateExisting = track != null;
        if (track == null) {
            track = new Track();
            track.position = dao.nextPosition(playlist);
            track.createdAt = System.currentTimeMillis();
        }
        track.videoId = videoId;
        track.title = title;
        track.uploader = uploader;
        track.durationMs = durationMs;
        track.filePath = resultFile.getAbsolutePath();
        track.playlistName = playlist;
        track.thumbnailUrl = videoId != null && !videoId.isEmpty()
                ? "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg" : null;
        track.youtubePosition = youtubePosition;
        track.downloaded = true;
        track.sourceUrl = videoUrl;
        if (updateExisting) {
            dao.update(track);
        } else {
            dao.insert(track);
        }
    }

    /** Пауза = убить процесс yt-dlp; при возобновлении он докачивает .part-файл. */
    private File downloadWithPauseRetry(DownloadJob job, String videoUrl, File dir,
                                        String baseName, String format,
                                        @Nullable String playerClients) throws Exception {
        while (true) {
            waitWhilePaused(job);
            if (job.cancelRequested) {
                throw new InterruptedException("canceled");
            }
            try {
                return executeDownload(job, videoUrl, dir, baseName, format, playerClients);
            } catch (Exception e) {
                if (job.pauseRequested && !job.cancelRequested) {
                    continue; // процесс убит паузой — подождём и продолжим
                }
                throw e;
            }
        }
    }

    private void waitWhilePaused(DownloadJob job)
            throws JobPausedException, JobYieldException {
        if (job.yieldInterrupted && !job.cancelRequested) {
            throw new JobYieldException();
        }
        if (!job.pauseRequested || job.cancelRequested) {
            return;
        }
        job.status = DownloadJob.Status.PAUSED;
        publish(true);
        updateProgressNotification(job);
        throw new JobPausedException();
    }

    private void throwIfPaused(DownloadJob job)
            throws JobPausedException, JobYieldException {
        if (job.yieldInterrupted && !job.cancelRequested) {
            throw new JobYieldException();
        }
        if (job.pauseRequested && !job.cancelRequested) {
            throw new JobPausedException();
        }
    }

    private File executeDownload(DownloadJob job, String videoUrl, File dir,
                                 String baseName, String format,
                                 @Nullable String playerClients) throws Exception {
        // % в названии сломал бы шаблон yt-dlp
        String template = new File(dir, baseName.replace("%", "%%") + ".%(ext)s")
                .getAbsolutePath();
        YoutubeDLRequest request = new YoutubeDLRequest(videoUrl);
        request.addOption("--no-playlist");
        request.addOption("-x");
        request.addOption("--audio-format", format);
        request.addOption("--audio-quality", "0");
        request.addOption("--embed-thumbnail");
        request.addOption("--embed-metadata");
        request.addOption("--no-mtime");
        request.addOption("-o", template);
        if (playerClients != null) {
            request.addOption("--extractor-args",
                    "youtube:player_client=" + playerClients);
        }
        applyCookies(request);

        String processId = "job-" + job.id;
        activeProcessId = processId;
        try {
            YoutubeDL.getInstance().execute(request, processId,
                    (progress, etaSeconds, line) -> {
                        job.progress = Math.max(0, Math.min(100, Math.round(progress)));
                        DownloadJob.TrackItem activeTrack = job.activeTrack;
                        if (activeTrack != null) {
                            activeTrack.progress = job.progress;
                        }
                        parseSizes(job, line);
                        publish(false);
                        updateProgressNotification(job);
                        return Unit.INSTANCE;
                    });
        } finally {
            activeProcessId = null;
        }

        File expected = new File(dir, baseName + "." + format);
        if (expected.exists()) {
            return expected;
        }
        // на всякий случай: yt-dlp мог сохранить с другим расширением
        String finalBaseName = baseName;
        File[] candidates = dir.listFiles((d, name) -> {
            int dot = name.lastIndexOf('.');
            return dot > 0 && name.substring(0, dot).equals(finalBaseName);
        });
        if (candidates != null && candidates.length > 0) {
            return candidates[0];
        }
        throw new IllegalStateException("Файл не найден после загрузки: " + baseName);
    }

    private static void parseSizes(DownloadJob job, @Nullable String line) {
        if (line == null) {
            return;
        }
        Matcher matcher = SIZE_PATTERN.matcher(line);
        if (matcher.find()) {
            try {
                double value = Double.parseDouble(matcher.group(1));
                switch (matcher.group(2)) {
                    case "GiB":
                        value *= 1024 * 1024 * 1024;
                        break;
                    case "MiB":
                        value *= 1024 * 1024;
                        break;
                    default:
                        value *= 1024;
                        break;
                }
                job.totalBytes = (long) value;
                job.downloadedBytes = (long) (value * job.progress / 100.0);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    // ------------------------------------------------------- pause / cancel

    private void setJobPaused(long jobId, boolean paused) {
        DownloadJob job;
        boolean killProcess = false;
        synchronized (jobs) {
            job = findJobLocked(jobId);
            if (job == null || !job.isActive() || job.cancelRequested) {
                return;
            }
            job.pauseRequested = paused;
            if (paused) {
                if (job == activeJob) {
                    job.pauseInterrupted = true;
                    killProcess = true;
                } else {
                    job.status = DownloadJob.Status.PAUSED;
                }
            } else if (job.status == DownloadJob.Status.PAUSED) {
                job.status = DownloadJob.Status.QUEUED;
            }
        }
        persistPaused(job);
        if (killProcess) {
            killActiveProcess();
        }
        publish(true);
        refreshForegroundState();
        scheduleDispatcher();
    }

    private void cancelJob(long jobId) {
        DownloadJob job;
        boolean killProcess = false;
        boolean finishImmediately = false;
        synchronized (jobs) {
            job = findJobLocked(jobId);
            if (job == null || !job.isActive()) {
                return;
            }
            job.cancelRequested = true;
            job.pauseRequested = false;
            if (job == activeJob) {
                killProcess = true;
            } else {
                job.status = DownloadJob.Status.CANCELED;
                finishImmediately = true;
            }
        }
        if (killProcess) {
            killActiveProcess();
        }
        if (finishImmediately) {
            cleanupPendingPlaceholders(job);
            removePending(job);
        }
        publish(true);
        refreshForegroundState();
        scheduleDispatcher();
    }

    private void moveJobInternal(long jobId, long targetJobId) {
        synchronized (jobs) {
            int from = indexOfJobLocked(jobId);
            int to = indexOfJobLocked(targetJobId);
            if (from < 0 || to < 0 || from == to
                    || !jobs.get(from).isActive() || !jobs.get(to).isActive()) {
                return;
            }
            DownloadJob moved = jobs.remove(from);
            jobs.add(to, moved);
        }
        persistQueueOrder();
        publish(true);
    }

    private void moveTrackInternal(long jobId, @Nullable String videoId,
                                   @Nullable String targetVideoId) {
        if (videoId == null || targetVideoId == null || videoId.equals(targetVideoId)) {
            return;
        }
        DownloadJob job;
        synchronized (jobs) {
            job = findJobLocked(jobId);
        }
        if (job == null || !job.isPlaylist || !job.isActive()) {
            return;
        }
        synchronized (job.tracks) {
            int from = indexOfTrackLocked(job, videoId);
            int to = indexOfTrackLocked(job, targetVideoId);
            if (from < 0 || to < 0
                    || job.tracks.get(from).status != DownloadJob.TrackItem.Status.WAITING
                    || job.tracks.get(to).status != DownloadJob.TrackItem.Status.WAITING) {
                return;
            }
            DownloadJob.TrackItem moved = job.tracks.remove(from);
            job.tracks.add(to, moved);
            job.trackOrderJson = serializeTrackOrderLocked(job);
        }
        persistTrackOrder(job);
        publish(true);
    }

    private void prioritizeTrackInternal(@Nullable String playlist,
                                         @Nullable String videoId) {
        if (playlist == null || videoId == null) {
            return;
        }
        DownloadJob targetJob = null;
        synchronized (jobs) {
            for (DownloadJob candidate : jobs) {
                if (candidate.isPlaylist && candidate.isActive()
                        && playlist.equals(candidate.playlistName)) {
                    synchronized (candidate.tracks) {
                        if (indexOfTrackLocked(candidate, videoId) >= 0) {
                            targetJob = candidate;
                            break;
                        }
                    }
                }
            }
            if (targetJob == null) {
                return;
            }
            targetJob.pauseRequested = false;
            if (targetJob.status == DownloadJob.Status.PAUSED) {
                targetJob.status = DownloadJob.Status.QUEUED;
            }
            jobs.remove(targetJob);
            int firstActive = 0;
            while (firstActive < jobs.size() && !jobs.get(firstActive).isActive()) {
                firstActive++;
            }
            jobs.add(firstActive, targetJob);
        }

        boolean alreadyDownloading;
        synchronized (targetJob.tracks) {
            int from = indexOfTrackLocked(targetJob, videoId);
            if (from < 0) {
                return;
            }
            DownloadJob.TrackItem target = targetJob.tracks.get(from);
            alreadyDownloading = target.status == DownloadJob.TrackItem.Status.DOWNLOADING;
            if (target.status == DownloadJob.TrackItem.Status.WAITING) {
                targetJob.tracks.remove(from);
                int firstWaiting = 0;
                while (firstWaiting < targetJob.tracks.size()
                        && (targetJob.tracks.get(firstWaiting).status
                        == DownloadJob.TrackItem.Status.DONE
                        || targetJob.tracks.get(firstWaiting).status
                        == DownloadJob.TrackItem.Status.FAILED)) {
                    firstWaiting++;
                }
                targetJob.tracks.add(firstWaiting, target);
                targetJob.trackOrderJson = serializeTrackOrderLocked(targetJob);
            }
        }

        DownloadJob running = activeJob;
        if (!alreadyDownloading && running != null) {
            running.yieldInterrupted = true;
            killActiveProcess();
        }
        persistPaused(targetJob);
        persistTrackOrder(targetJob);
        persistQueueOrder();
        publish(true);
        scheduleDispatcher();
    }

    private int indexOfTrackLocked(DownloadJob job, String videoId) {
        for (int i = 0; i < job.tracks.size(); i++) {
            if (videoId.equals(job.tracks.get(i).videoId)) {
                return i;
            }
        }
        return -1;
    }

    private String serializeTrackOrderLocked(DownloadJob job) {
        JSONArray order = new JSONArray();
        for (DownloadJob.TrackItem item : job.tracks) {
            order.put(item.videoId);
        }
        return order.toString();
    }

    private void persistTrackOrder(DownloadJob job) {
        String order = job.trackOrderJson;
        VibyDatabase.dbExecutor.execute(() -> {
            if (job.pendingId != 0) {
                VibyDatabase.get(this).pendingDownloadDao()
                        .updateTrackOrder(job.pendingId, order);
            }
        });
    }

    @Nullable
    private DownloadJob findJobLocked(long jobId) {
        int index = indexOfJobLocked(jobId);
        return index >= 0 ? jobs.get(index) : null;
    }

    private int indexOfJobLocked(long jobId) {
        for (int i = 0; i < jobs.size(); i++) {
            if (jobs.get(i).id == jobId) {
                return i;
            }
        }
        return -1;
    }

    private void killActiveProcess() {
        String processId = activeProcessId;
        if (processId != null) {
            try {
                YoutubeDL.getInstance().destroyProcessById(processId);
            } catch (Exception e) {
                Log.w(TAG, "destroyProcessById failed", e);
            }
        }
    }

    // ------------------------------------------------------------ helpers

    private void waitForEngine() throws InterruptedException {
        VibyApp app = (VibyApp) getApplication();
        for (int i = 0; i < ENGINE_WAIT_SECONDS * 2; i++) {
            if (app.isEngineReady()) {
                return;
            }
            if (app.getEngineState().getValue() == VibyApp.EngineState.FAILED) {
                throw new IllegalStateException(getString(R.string.error_engine_init));
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException(getString(R.string.error_engine_init));
    }

    private void publish(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastPublishMs < 300) {
            return;
        }
        lastPublishMs = now;
        synchronized (jobs) {
            jobsLive.postValue(new ArrayList<>(jobs));
        }
    }

    private Notification buildProgressNotification(DownloadJob job) {
        int requestCode = (int) (job.id % 100_000L) * 2;
        PendingIntent cancelIntent = PendingIntent.getService(
                this, requestCode,
                new Intent(this, DownloadService.class)
                        .setAction(ACTION_CANCEL_JOB)
                        .putExtra(EXTRA_JOB_ID, job.id),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean paused = job.status == DownloadJob.Status.PAUSED || job.pauseRequested;
        PendingIntent pauseIntent = PendingIntent.getService(
                this, requestCode + 1,
                new Intent(this, DownloadService.class)
                        .setAction(paused ? ACTION_RESUME_JOB : ACTION_PAUSE_JOB)
                        .putExtra(EXTRA_JOB_ID, job.id),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String trackName = job.currentTrackTitle != null
                ? job.currentTrackTitle : (job.title != null ? job.title : job.url);
        StringBuilder text = new StringBuilder();
        if (job.isPlaylist && job.totalCount > 0) {
            text.append(getString(R.string.notif_download_playlist_progress,
                    job.currentIndex, job.totalCount));
        }
        if (job.totalBytes > 0) {
            if (text.length() > 0) {
                text.append(" · ");
            }
            text.append(Formats.size(this, job.downloadedBytes))
                    .append(" / ")
                    .append(Formats.size(this, job.totalBytes));
        }
        if (paused) {
            if (text.length() > 0) {
                text.append(" · ");
            }
            text.append(getString(R.string.status_paused));
        }

        return new NotificationCompat.Builder(this, VibyApp.CHANNEL_DOWNLOADS)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(getString(R.string.notif_downloading, trackName))
                .setContentText(text.length() > 0 ? text.toString() : null)
                .setProgress(100, job.progress,
                        job.status == DownloadJob.Status.PREPARING)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(0, getString(paused
                        ? R.string.btn_resume_download
                        : R.string.btn_pause_download), pauseIntent)
                .addAction(0, getString(R.string.btn_cancel), cancelIntent)
                .build();
    }

    private void updateProgressNotification(DownloadJob job) {
        notificationManager.notify(NOTIF_PROGRESS_ID, buildProgressNotification(job));
    }

    private void cleanupPendingPlaceholders(DownloadJob job) {
        if (!job.isPlaylist || job.playlistName == null) {
            return;
        }
        List<String> pendingVideoIds = new ArrayList<>();
        synchronized (job.tracks) {
            for (DownloadJob.TrackItem item : job.tracks) {
                if (item.status != DownloadJob.TrackItem.Status.DONE) {
                    pendingVideoIds.add(item.videoId);
                }
            }
        }
        String playlist = job.playlistName;
        VibyDatabase.dbExecutor.execute(() -> {
            for (String videoId : pendingVideoIds) {
                dao.deletePending(playlist, videoId);
            }
        });
    }

    private void refreshForegroundState() {
        if (pendingCount.get() <= 0) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        DownloadJob notificationJob = activeJob;
        if (notificationJob == null || !notificationJob.isActive()) {
            synchronized (jobs) {
                for (DownloadJob candidate : jobs) {
                    if (candidate.isActive()) {
                        notificationJob = candidate;
                        break;
                    }
                }
            }
        }
        if (notificationJob != null) {
            updateProgressNotification(notificationJob);
        }
    }

    private void postResultNotification(DownloadJob job) {
        String text;
        switch (job.status) {
            case DONE:
                text = job.isPlaylist
                        ? getString(R.string.notif_download_summary,
                                job.totalCount - job.failedCount, job.failedCount)
                        : getString(R.string.notif_download_done, job.title);
                break;
            case FAILED:
                text = getString(R.string.notif_download_failed,
                        job.error != null ? job.error : job.title);
                break;
            default:
                return; // отменённые не показываем
        }
        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(this, VibyApp.CHANNEL_DOWNLOADS)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle(job.title != null ? job.title : job.url)
                        .setContentText(text)
                        .setAutoCancel(true);
        if (!job.failedTitles.isEmpty()) {
            // разворачиваемое уведомление со списком нескачавшихся треков
            StringBuilder big = new StringBuilder(text).append('\n')
                    .append(getString(R.string.failed_tracks_header));
            synchronized (job.failedTitles) {
                for (String title : job.failedTitles) {
                    big.append("\n• ").append(title);
                }
            }
            builder.setStyle(new NotificationCompat.BigTextStyle().bigText(big.toString()));
        }
        notificationManager.notify(notifIdSeq.getAndIncrement(), builder.build());
    }

    /**
     * Видео, которое сменой player-client не оживить: удалено, приватно,
     * заблокировано правообладателем/в регионе. Такие не ретраим — только время терять.
     */
    private static boolean isPermanentlyUnavailable(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(java.util.Locale.US);
        return lower.contains("removed")
                || lower.contains("deleted")
                || lower.contains("private")
                || lower.contains("terminated")
                || lower.contains("copyright")
                || lower.contains("blocked")
                || lower.contains("who has blocked it")
                || lower.contains("not made this video available");
    }

    private static String shortError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isEmpty()) {
            return e.getClass().getSimpleName();
        }
        // yt-dlp пишет многострочные простыни — берём первую содержательную строку
        for (String line : message.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("ERROR:")) {
                return trimmed;
            }
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
