package com.example.viby.download;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.viby.R;
import com.example.viby.VibyApp;
import com.example.viby.data.Track;
import com.example.viby.data.TrackDao;
import com.example.viby.data.VibyDatabase;
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
    public static final String ACTION_RETRY_TRACK = "com.example.viby.action.RETRY_TRACK";
    public static final String ACTION_RETRY_FAILED = "com.example.viby.action.RETRY_FAILED";
    public static final String ACTION_REMOVE_FAILED = "com.example.viby.action.REMOVE_FAILED";
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

    public static void retryTrack(Context context, long jobId, String videoId) {
        context.startService(new Intent(context, DownloadService.class)
                .setAction(ACTION_RETRY_TRACK)
                .putExtra(EXTRA_JOB_ID, jobId)
                .putExtra(EXTRA_TRACK_ID, videoId));
    }

    public static void retryAllFailed(Context context, long jobId) {
        context.startService(new Intent(context, DownloadService.class)
                .setAction(ACTION_RETRY_FAILED)
                .putExtra(EXTRA_JOB_ID, jobId));
    }

    public static void removeFailedTrack(Context context, long jobId, String videoId) {
        context.startService(new Intent(context, DownloadService.class)
                .setAction(ACTION_REMOVE_FAILED)
                .putExtra(EXTRA_JOB_ID, jobId)
                .putExtra(EXTRA_TRACK_ID, videoId));
    }

    /** Recreates a durable single-track job from a failed placeholder after process restart. */
    public static void retryPersistedTrack(Context context, Track track) {
        if (track.sourceUrl != null && !track.sourceUrl.isEmpty()) {
            enqueue(context, track.sourceUrl, track.playlistName, false);
        }
    }

    /**
     * Восстановить незавершённые загрузки после краша/перезагрузки/обновления.
     * Уже скачанные треки плейлистов пропускаются, так что докачка продолжается
     * ровно с того места, где остановилась.
     */
    public static void restorePending(Context context) {
        Context app = context.getApplicationContext();
        VibyDatabase.dbExecutor.execute(() -> {
            DownloadQueueRepository repository = new DownloadQueueRepository(
                    VibyDatabase.get(app).pendingDownloadDao());
            for (com.example.viby.data.PendingDownload pending : repository.loadAll()) {
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
    private boolean dispatcherRunning;
    private volatile String activeProcessId;
    @Nullable
    private volatile DownloadJob activeJob;

    private DownloadNotificationController notificationController;
    private ConnectivityManager connectivityManager;
    @Nullable
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile boolean networkAvailable = true;
    private TrackDao dao;
    private DownloadQueueRepository queueRepository;
    private YoutubeExtractor youtubeExtractor;
    private AudioDownloader audioDownloader;
    private LibraryImporter libraryImporter;
    private long lastPublishMs;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationController = new DownloadNotificationController(this);
        connectivityManager = getSystemService(ConnectivityManager.class);
        VibyDatabase database = VibyDatabase.get(this);
        dao = database.trackDao();
        queueRepository = new DownloadQueueRepository(database.pendingDownloadDao());
        youtubeExtractor = YoutubeExtractor.create(this);
        audioDownloader = AudioDownloader.create(youtubeExtractor);
        libraryImporter = LibraryImporter.create(dao);
        networkAvailable = hasUsableNetwork();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                refreshNetworkState();
            }

            @Override
            public void onLost(Network network) {
                refreshNetworkState();
            }

            @Override
            public void onCapabilitiesChanged(Network network,
                                              NetworkCapabilities capabilities) {
                refreshNetworkState();
            }
        };
        connectivityManager.registerDefaultNetworkCallback(networkCallback);
    }

    @Override
    public void onDestroy() {
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (IllegalArgumentException ignored) {
            }
        }
        super.onDestroy();
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
            case ACTION_RETRY_TRACK:
                retryFailedInternal(intent.getLongExtra(EXTRA_JOB_ID, -1L),
                        intent.getStringExtra(EXTRA_TRACK_ID), false);
                break;
            case ACTION_RETRY_FAILED:
                retryFailedInternal(intent.getLongExtra(EXTRA_JOB_ID, -1L), null, true);
                break;
            case ACTION_REMOVE_FAILED:
                removeFailedInternal(intent.getLongExtra(EXTRA_JOB_ID, -1L),
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
                    DownloadJob job;
                    synchronized (jobs) {
                        job = DownloadJobFactory.create(
                                url, playlist, isPlaylist, pendingId,
                                restoredPosition, restoredPaused, restoredTrackOrder,
                                DownloadScheduler.nextQueuePosition(jobs));
                        jobs.add(job);
                        DownloadScheduler.sort(jobs);
                    }
                    enqueuePreparedJob(job, pendingId == 0);
                }
                break;
            default:
                break;
        }
        return START_NOT_STICKY;
    }

    private void enqueuePreparedJob(DownloadJob job, boolean persist) {
        if (persist) {
            persistPending(job);
        }
        pendingCount.incrementAndGet();
        publish(true);
        notificationController.startForeground(job);
        scheduleDispatcher();
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
                next = DownloadScheduler.nextRunnable(jobs);
                if (next == null) {
                    dispatcherRunning = false;
                    return;
                }
            }
            runJob(next);
        }
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
                    || job.status == DownloadJob.Status.COMPLETED_WITH_ERRORS
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
                job.error = e instanceof ClassifiedDownloadException
                        ? failureMessage(((ClassifiedDownloadException) e).failure.code)
                        : shortError(e);
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
                notificationController.postResult(job);
            }
            notificationController.refreshForegroundState(
                    pendingCount.get(), activeJob, jobs);
        }
    }

    private static final class JobPausedException extends Exception {
    }

    private static final class JobYieldException extends Exception {
    }

    private static final class ClassifiedDownloadException extends Exception {
        final DownloadFailure failure;

        ClassifiedDownloadException(DownloadFailure failure, Throwable cause) {
            super(cause);
            this.failure = failure;
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

    private void persistPending(DownloadJob job) {
        long createdAt = System.currentTimeMillis();
        VibyDatabase.dbExecutor.execute(() -> queueRepository.insert(job, createdAt));
    }

    private void removePending(DownloadJob job) {
        synchronized (job) {
            if (job.completionHandled || job.isActive()) {
                return;
            }
            job.completionHandled = true;
        }
        // dbExecutor последовательный: insert из persistPending выполнится раньше
        VibyDatabase.dbExecutor.execute(() -> queueRepository.delete(job.pendingId));
        pendingCount.decrementAndGet();
    }

    private void persistPaused(DownloadJob job) {
        VibyDatabase.dbExecutor.execute(() -> queueRepository.updatePaused(job));
    }

    private void persistQueueOrder() {
        List<DownloadJob> snapshot;
        synchronized (jobs) {
            DownloadScheduler.normalizeActivePositions(jobs);
            snapshot = new ArrayList<>(jobs);
        }
        VibyDatabase.dbExecutor.execute(() -> {
            for (DownloadJob job : snapshot) {
                queueRepository.updateQueuePosition(job);
            }
        });
    }

    private void runSingleJob(DownloadJob job) throws Exception {
        job.status = DownloadJob.Status.PREPARING;
        publish(true);
        notificationController.updateProgress(job);

        YoutubeDLRequest infoRequest = youtubeExtractor.videoInfoRequest(job.url);
        VideoInfo info = fetchVideoInfo(job, infoRequest);
        String videoId = info.getId();
        String title = info.getTitle() != null ? info.getTitle() : job.url;
        job.title = title;
        job.currentTrackTitle = title;
        String playlist = job.playlistName != null && !job.playlistName.isEmpty()
                ? job.playlistName : getString(R.string.default_playlist);
        job.playlistName = playlist;
        publish(true);

        if (libraryImporter.reuseDownloadedTrack(videoId, title, info.getUploader(),
                info.getDuration() * 1000L, playlist, null, job.url)) {
            job.status = DownloadJob.Status.DONE;
            return;
        }

        long durationMs = info.getDuration() * 1000L;
        DownloadJob.TrackItem item = findTrack(job, videoId);
        if (item == null) {
            item = new DownloadJob.TrackItem(videoId, title,
                    info.getUploader(), durationMs, 0);
            job.tracks.add(item);
        }
        ensurePlaceholder(item, playlist, job.url);
        job.activeTrack = item;
        try {
            downloadTrackWithRetries(job, item, job.url, videoId, title,
                    info.getUploader(), durationMs, playlist, null);
        } finally {
            job.activeTrack = null;
        }
        job.status = job.cancelRequested
                ? DownloadJob.Status.CANCELED : DownloadJob.Status.DONE;
    }

    private void runPlaylistJob(DownloadJob job) throws Exception {
        job.status = DownloadJob.Status.PREPARING;
        publish(true);
        notificationController.updateProgress(job);

        YoutubeDLRequest infoRequest = youtubeExtractor.playlistInfoRequest(
                job.url, com.example.viby.util.YoutubeUrlParser.isRadioMixUrl(job.url));
        String processId = "job-" + job.id + "-info";
        activeProcessId = processId;
        YoutubeDLResponse response = fetchPlaylistInfo(job, infoRequest, processId);

        JSONObject root = new JSONObject(response.getOut());
        String playlistTitle = root.optString("title", "Playlist");
        String playlist = job.playlistName != null && !job.playlistName.isEmpty()
                ? job.playlistName : StorageHelper.sanitize(playlistTitle);
        job.playlistName = playlist;
        job.title = playlistTitle;

        // запоминаем источник — для кнопки «обновить плейлист»
        com.example.viby.data.PlaylistSource source =
                VibyDatabase.get(this).playlistSourceDao().getSync(playlist);
        if (source == null) {
            source = new com.example.viby.data.PlaylistSource();
        }
        source.playlistName = playlist;
        source.sourceUrl = job.url;
        if (source.youtubePlaylistId == null) {
            source.youtubePlaylistId =
                    com.example.viby.util.YoutubeUrlParser.playlistId(job.url);
        }
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
            notificationController.updateProgress(job);
            try {
                if (item.videoId.isEmpty() || libraryImporter.reuseDownloadedTrack(item.videoId,
                        item.title, item.uploader, item.durationMs, playlist,
                        item.youtubePosition, "https://www.youtube.com/watch?v="
                                + item.videoId)) {
                    item.progress = 100;
                    item.status = DownloadJob.TrackItem.Status.DOWNLOADED;
                    continue;
                }
                String videoUrl = "https://www.youtube.com/watch?v=" + item.videoId;
                downloadTrackWithRetries(job, item, videoUrl, item.videoId,
                        item.title, item.uploader, item.durationMs, playlist,
                        item.youtubePosition);
                item.progress = 100;
                item.status = DownloadJob.TrackItem.Status.DOWNLOADED;
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
                if (item.status != DownloadJob.TrackItem.Status.FAILED_TEMPORARY
                        && item.status != DownloadJob.TrackItem.Status.FAILED_PERMANENT) {
                    DownloadFailure failure = e instanceof ClassifiedDownloadException
                            ? ((ClassifiedDownloadException) e).failure
                            : DownloadFailure.classify(e);
                    setFailedState(item, failure);
                    persistTrackState(playlist, item);
                }
                Log.w(TAG, "playlist entry failed [" + item.failureCode + "]: "
                        + item.title, e);
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
                ? DownloadJob.Status.CANCELED
                : job.failedCount > 0
                ? DownloadJob.Status.COMPLETED_WITH_ERRORS
                : DownloadJob.Status.DONE;
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
                if (item.status == DownloadJob.TrackItem.Status.DOWNLOADED
                        || item.status == DownloadJob.TrackItem.Status.FAILED_TEMPORARY
                        || item.status == DownloadJob.TrackItem.Status.FAILED_PERMANENT) {
                    count++;
                }
            }
        }
        return count;
    }

    private void ensurePlaceholderTracks(DownloadJob job, String playlist) {
        synchronized (job.tracks) {
            for (DownloadJob.TrackItem item : job.tracks) {
                String sourceUrl = "https://www.youtube.com/watch?v=" + item.videoId;
                if (libraryImporter.reuseDownloadedTrack(item.videoId, item.title, item.uploader,
                        item.durationMs, playlist, item.youtubePosition, sourceUrl)) {
                    item.status = DownloadJob.TrackItem.Status.DOWNLOADED;
                    continue;
                }
                Track existing = dao.getByVideoIdSync(playlist, item.videoId);
                if (existing != null) {
                    existing.title = item.title;
                    existing.uploader = item.uploader;
                    existing.durationMs = item.durationMs;
                    existing.youtubePosition = item.youtubePosition;
                    existing.thumbnailUrl = "https://i.ytimg.com/vi/"
                            + item.videoId + "/hqdefault.jpg";
                    existing.sourceUrl = sourceUrl;
                    existing.downloaded = false;
                    existing.filePath = "";
                    boolean stillFailed = item.status
                            == DownloadJob.TrackItem.Status.FAILED_TEMPORARY
                            || item.status == DownloadJob.TrackItem.Status.FAILED_PERMANENT;
                    if (!stillFailed) {
                        existing.downloadState = Track.DOWNLOAD_WAITING;
                        existing.downloadErrorCode = null;
                        existing.downloadErrorMessage = null;
                        existing.downloadProgress = 0;
                    }
                    dao.update(existing);
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
                placeholder.sourceUrl = sourceUrl;
                placeholder.downloadState = Track.DOWNLOAD_WAITING;
                dao.insert(placeholder);
            }
        }
    }

    private void ensurePlaceholder(DownloadJob.TrackItem item, String playlist,
                                   String sourceUrl) {
        Track existing = dao.getByVideoIdSync(playlist, item.videoId);
        if (existing == null) {
            existing = new Track();
            existing.videoId = item.videoId;
            existing.playlistName = playlist;
            existing.position = dao.nextPosition(playlist);
            existing.createdAt = System.currentTimeMillis();
        }
        existing.title = item.title;
        existing.uploader = item.uploader;
        existing.durationMs = item.durationMs;
        existing.thumbnailUrl = item.videoId != null && !item.videoId.isEmpty()
                ? "https://i.ytimg.com/vi/" + item.videoId + "/hqdefault.jpg" : null;
        existing.sourceUrl = sourceUrl;
        existing.filePath = "";
        existing.downloaded = false;
        existing.downloadState = Track.DOWNLOAD_WAITING;
        existing.downloadErrorCode = null;
        existing.downloadErrorMessage = null;
        existing.downloadProgress = 0;
        if (existing.id == 0) {
            dao.insert(existing);
        } else {
            dao.update(existing);
        }
    }

    private void persistTrackState(String playlist, DownloadJob.TrackItem item) {
        Track track = dao.getByVideoIdSync(playlist, item.videoId);
        if (track == null || track.downloaded) {
            return;
        }
        switch (item.status) {
            case DOWNLOADING:
                track.downloadState = Track.DOWNLOAD_DOWNLOADING;
                break;
            case RETRY_SCHEDULED:
                track.downloadState = Track.DOWNLOAD_RETRY_SCHEDULED;
                break;
            case FAILED_TEMPORARY:
                track.downloadState = Track.DOWNLOAD_FAILED_TEMPORARY;
                break;
            case FAILED_PERMANENT:
                track.downloadState = Track.DOWNLOAD_FAILED_PERMANENT;
                break;
            case DOWNLOADED:
                track.downloadState = Track.DOWNLOAD_DOWNLOADED;
                break;
            default:
                track.downloadState = Track.DOWNLOAD_WAITING;
                break;
        }
        track.downloadErrorCode = item.failureCode;
        track.downloadErrorMessage = item.failureMessage;
        track.downloadAttemptCount = item.attemptCount;
        track.downloadLastAttemptAt = item.lastAttemptAt;
        track.downloadFormat = item.selectedFormat;
        track.downloadRequiresAuthorization = item.requiresAuthorization;
        track.downloadProgress = item.progress;
        dao.update(track);
    }

    private void setFailedState(DownloadJob.TrackItem item, DownloadFailure failure) {
        item.status = failure.retryable
                ? DownloadJob.TrackItem.Status.FAILED_TEMPORARY
                : DownloadJob.TrackItem.Status.FAILED_PERMANENT;
        item.failureCode = failure.code.name();
        item.failureMessage = failureMessage(failure.code);
        item.requiresAuthorization = failure.requiresAuthorization;
        item.nextRetryAt = 0L;
    }

    private void waitForRetry(DownloadJob job, long delayMs) throws Exception {
        long deadline = System.currentTimeMillis() + delayMs;
        while (System.currentTimeMillis() < deadline) {
            throwIfPaused(job);
            if (job.cancelRequested) {
                throw new InterruptedException("canceled");
            }
            long remaining = deadline - System.currentTimeMillis();
            try {
                Thread.sleep(Math.min(250L, Math.max(1L, remaining)));
            } catch (InterruptedException interrupted) {
                if (job.cancelRequested) {
                    throw interrupted;
                }
            }
        }
    }

    private String failureMessage(DownloadFailure.Code code) {
        switch (code) {
            case VIDEO_REMOVED:
                return getString(R.string.download_error_video_removed);
            case VIDEO_UNAVAILABLE:
                return getString(R.string.download_error_video_unavailable);
            case VIDEO_PRIVATE:
                return getString(R.string.download_error_video_private);
            case REGION_BLOCKED:
                return getString(R.string.download_error_region_blocked);
            case AGE_RESTRICTED:
                return getString(R.string.download_error_age_restricted);
            case EXTRACTOR_OUTDATED:
                return getString(R.string.download_error_extractor_outdated);
            case AUTH_REQUIRED:
                return getString(R.string.download_error_auth_required);
            case RATE_LIMITED:
                return getString(R.string.download_error_rate_limited);
            case NETWORK:
                return getString(R.string.download_error_network);
            case FFMPEG:
                return getString(R.string.download_error_ffmpeg);
            case STORAGE_FULL:
                return getString(R.string.download_error_storage_full);
            case FILE_SYSTEM:
                return getString(R.string.download_error_file_system);
            case FORMAT_UNAVAILABLE:
                return getString(R.string.download_error_format_unavailable);
            default:
                return getString(R.string.download_error_unknown);
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

    private void downloadTrackWithRetries(DownloadJob job, DownloadJob.TrackItem item,
                                          String videoUrl, String videoId,
                                          String title, @Nullable String uploader,
                                          long durationMs, String playlist,
                                          @Nullable Integer youtubePosition) throws Exception {
        int transientAttempts = 0;
        int networkAttempts = 0;
        while (true) {
            throwIfPaused(job);
            item.status = DownloadJob.TrackItem.Status.DOWNLOADING;
            item.attemptCount++;
            item.lastAttemptAt = System.currentTimeMillis();
            item.nextRetryAt = 0L;
            item.failureCode = null;
            item.failureMessage = null;
            item.requiresAuthorization = false;
            persistTrackState(playlist, item);
            publish(true);
            try {
                downloadTrack(job, videoUrl, videoId, title, uploader, durationMs,
                        playlist, youtubePosition, item);
                clearNetworkRetry(job);
                item.status = DownloadJob.TrackItem.Status.DOWNLOADED;
                item.progress = 100;
                persistTrackState(playlist, item);
                return;
            } catch (Exception error) {
                if (job.cancelRequested || job.pauseRequested || job.pauseInterrupted
                        || job.yieldInterrupted || error instanceof JobPausedException
                        || error instanceof JobYieldException) {
                    throw error;
                }
                DownloadFailure failure = error instanceof ClassifiedDownloadException
                        ? ((ClassifiedDownloadException) error).failure
                        : DownloadFailure.classify(error);
                item.failureCode = failure.code.name();
                item.failureMessage = failureMessage(failure.code);
                item.requiresAuthorization = failure.requiresAuthorization;
                if (failure.code == DownloadFailure.Code.NETWORK) {
                    item.status = DownloadJob.TrackItem.Status.RETRY_SCHEDULED;
                    item.nextRetryAt = System.currentTimeMillis()
                            + DownloadRetryPolicy.delayAfterAttempt(++networkAttempts);
                    persistTrackState(playlist, item);
                    waitForNetworkRetry(job, networkAttempts);
                    continue;
                }
                transientAttempts++;
                if (!DownloadRetryPolicy.shouldRetry(failure, transientAttempts)) {
                    setFailedState(item, failure);
                    persistTrackState(playlist, item);
                    throw new ClassifiedDownloadException(failure, error);
                }
                long delayMs = DownloadRetryPolicy.delayAfterAttempt(transientAttempts);
                item.status = DownloadJob.TrackItem.Status.RETRY_SCHEDULED;
                item.nextRetryAt = System.currentTimeMillis() + delayMs;
                persistTrackState(playlist, item);
                publish(true);
                waitForRetry(job, delayMs);
            }
        }
    }

    private void downloadTrack(DownloadJob job, String videoUrl, String videoId,
                               String title, @Nullable String uploader,
                               long durationMs, String playlist,
                               @Nullable Integer youtubePosition,
                               DownloadJob.TrackItem item) throws Exception {
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
            item.selectedFormat = "mp3";
            resultFile = downloadWithPauseRetry(job, videoUrl, dir, baseName, "mp3", null);
        } catch (Exception e) {
            if (job.cancelRequested || job.pauseRequested || job.pauseInterrupted
                    || job.yieldInterrupted || e instanceof JobPausedException
                    || e instanceof JobYieldException) {
                throw e;
            }
            DownloadFailure firstFailure = DownloadFailure.classify(e);
            if (!shouldTryAlternatePlayerClients(firstFailure)) {
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
                item.selectedFormat = "mp3";
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
                item.selectedFormat = "m4a";
                resultFile = downloadWithPauseRetry(job, videoUrl, dir, baseName,
                        "m4a", ALT_PLAYER_CLIENTS);
            }
        }

        libraryImporter.importDownloaded(videoId, title, uploader, durationMs,
                playlist, youtubePosition, videoUrl, resultFile, item.attemptCount,
                item.lastAttemptAt, item.selectedFormat);
    }

    private static boolean shouldTryAlternatePlayerClients(DownloadFailure failure) {
        return failure.code == DownloadFailure.Code.UNKNOWN
                || failure.code == DownloadFailure.Code.VIDEO_UNAVAILABLE
                || failure.code == DownloadFailure.Code.FORMAT_UNAVAILABLE;
    }

    /** Пауза = убить процесс yt-dlp; при возобновлении он докачивает .part-файл. */
    private File downloadWithPauseRetry(DownloadJob job, String videoUrl, File dir,
                                        String baseName, String format,
                                        @Nullable String playerClients) throws Exception {
        while (true) {
            waitWhilePaused(job);
            waitForNetwork(job);
            if (job.cancelRequested) {
                throw new InterruptedException("canceled");
            }
            job.networkInterrupted = false;
            try {
                File result = executeDownload(
                        job, videoUrl, dir, baseName, format, playerClients);
                job.networkInterrupted = false;
                return result;
            } catch (Exception e) {
                if (job.pauseRequested && !job.cancelRequested) {
                    continue; // процесс убит паузой — подождём и продолжим
                }
                if (!job.cancelRequested
                        && (job.networkInterrupted || !networkAvailable)) {
                    waitForNetwork(job);
                    continue;
                }
                throw e;
            }
        }
    }

    private VideoInfo fetchVideoInfo(DownloadJob job, YoutubeDLRequest request)
            throws Exception {
        int attempt = 0;
        while (true) {
            waitForNetwork(job);
            throwIfPaused(job);
            job.networkInterrupted = false;
            try {
                VideoInfo info = youtubeExtractor.getVideoInfo(request);
                job.networkInterrupted = false;
                clearNetworkRetry(job);
                return info;
            } catch (Exception error) {
                if (job.cancelRequested || job.pauseRequested || job.pauseInterrupted
                        || job.yieldInterrupted || error instanceof JobPausedException
                        || error instanceof JobYieldException) {
                    throw error;
                }
                DownloadFailure failure = DownloadFailure.classify(error);
                boolean networkFailure = job.networkInterrupted || !networkAvailable
                        || failure.code == DownloadFailure.Code.NETWORK;
                if (!networkFailure) {
                    throw new ClassifiedDownloadException(failure, error);
                }
                waitForNetworkRetry(job, ++attempt);
            }
        }
    }

    private YoutubeDLResponse fetchPlaylistInfo(DownloadJob job,
                                                 YoutubeDLRequest request,
                                                 String processId) throws Exception {
        int attempt = 0;
        while (true) {
            waitForNetwork(job);
            throwIfPaused(job);
            job.networkInterrupted = false;
            activeProcessId = processId;
            try {
                YoutubeDLResponse response = youtubeExtractor.getPlaylistInfo(
                        request, processId);
                job.networkInterrupted = false;
                clearNetworkRetry(job);
                return response;
            } catch (Exception error) {
                if (job.cancelRequested || job.pauseRequested || job.pauseInterrupted
                        || job.yieldInterrupted || error instanceof JobPausedException
                        || error instanceof JobYieldException) {
                    throw error;
                }
                DownloadFailure failure = DownloadFailure.classify(error);
                boolean networkFailure = job.networkInterrupted || !networkAvailable
                        || failure.code == DownloadFailure.Code.NETWORK;
                if (!networkFailure) {
                    throw new ClassifiedDownloadException(failure, error);
                }
                waitForNetworkRetry(job, ++attempt);
            } finally {
                activeProcessId = null;
            }
        }
    }

    private void waitForNetworkRetry(DownloadJob job, int attempt)
            throws Exception {
        job.networkRetrying = true;
        publish(true);
        notificationController.updateProgress(job);
        long delayMs = DownloadRetryPolicy.delayAfterAttempt(attempt);
        long deadline = System.currentTimeMillis() + delayMs;
        while (System.currentTimeMillis() < deadline) {
            throwIfPaused(job);
            if (job.cancelRequested) {
                throw new InterruptedException("canceled");
            }
            if (!networkAvailable) {
                waitForNetwork(job);
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining > 0L) {
                Thread.sleep(Math.min(250L, remaining));
            }
        }
    }

    private void clearNetworkRetry(DownloadJob job) {
        if (!job.networkRetrying) {
            return;
        }
        job.networkRetrying = false;
        publish(true);
        notificationController.updateProgress(job);
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
        notificationController.updateProgress(job);
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
        String processId = "job-" + job.id;
        activeProcessId = processId;
        try {
            return audioDownloader.download(
                    videoUrl, dir, baseName, format, playerClients, processId,
                    (progress, etaSeconds, line) -> {
                        job.progress = DownloadProgressParser.resolve(
                                job.progress, progress, line);
                        DownloadJob.TrackItem activeTrack = job.activeTrack;
                        if (activeTrack != null) {
                            activeTrack.progress = job.progress;
                            if (job.playlistName != null
                                    && (activeTrack.progress == 100
                                    || activeTrack.progress
                                    >= activeTrack.persistedProgress + 5)) {
                                activeTrack.persistedProgress = activeTrack.progress;
                                persistTrackState(job.playlistName, activeTrack);
                            }
                        }
                        parseSizes(job, line);
                        publish(false);
                        notificationController.updateProgress(job);
                    });
        } finally {
            activeProcessId = null;
        }
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

    private void waitForNetwork(DownloadJob job)
            throws InterruptedException, JobPausedException, JobYieldException {
        while (!networkAvailable) {
            throwIfPaused(job);
            if (job.cancelRequested) {
                throw new InterruptedException("canceled");
            }
            if (!job.waitingForNetwork) {
                job.waitingForNetwork = true;
                publish(true);
                notificationController.updateProgress(job);
            }
            Thread.sleep(250L);
        }
        if (job.waitingForNetwork) {
            job.waitingForNetwork = false;
            publish(true);
            notificationController.updateProgress(job);
        }
    }

    private boolean hasUsableNetwork() {
        if (connectivityManager == null) {
            return true;
        }
        Network active = connectivityManager.getActiveNetwork();
        NetworkCapabilities capabilities = active != null
                ? connectivityManager.getNetworkCapabilities(active) : null;
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private void refreshNetworkState() {
        boolean available = hasUsableNetwork();
        boolean changed = networkAvailable != available;
        networkAvailable = available;
        DownloadJob job = activeJob;
        if (job != null && job.isActive()) {
            if (!available) {
                job.networkInterrupted = true;
            }
            boolean waitingChanged = job.waitingForNetwork == available;
            job.waitingForNetwork = !available;
            if (changed || waitingChanged) {
                publish(true);
                notificationController.updateProgress(job);
            }
        }
    }

    // ------------------------------------------------------- pause / cancel

    private void setJobPaused(long jobId, boolean paused) {
        DownloadQueueActions.PauseResult result;
        synchronized (jobs) {
            result = DownloadQueueActions.setPaused(jobs, activeJob, jobId, paused);
        }
        if (!result.changed()) {
            return;
        }
        DownloadJob job = result.job;
        persistPaused(job);
        if (result.killActiveProcess) {
            killActiveProcess();
        }
        publish(true);
        notificationController.refreshForegroundState(
                pendingCount.get(), activeJob, jobs);
        scheduleDispatcher();
    }

    private void cancelJob(long jobId) {
        DownloadQueueActions.CancelResult result;
        synchronized (jobs) {
            result = DownloadQueueActions.cancel(jobs, activeJob, jobId);
        }
        if (!result.changed()) {
            return;
        }
        DownloadJob job = result.job;
        if (result.killActiveProcess) {
            killActiveProcess();
        }
        if (result.finishImmediately) {
            cleanupPendingPlaceholders(job);
            removePending(job);
        }
        publish(true);
        notificationController.refreshForegroundState(
                pendingCount.get(), activeJob, jobs);
        scheduleDispatcher();
    }

    private void moveJobInternal(long jobId, long targetJobId) {
        boolean changed;
        synchronized (jobs) {
            changed = DownloadQueueActions.moveJob(jobs, jobId, targetJobId);
        }
        if (!changed) {
            return;
        }
        persistQueueOrder();
        publish(true);
    }

    private void retryFailedInternal(long jobId, @Nullable String videoId,
                                     boolean retryAll) {
        DownloadJob job = findJob(jobId);
        if (job == null || job.isActive()) {
            return;
        }
        boolean changed = false;
        synchronized (job.tracks) {
            for (DownloadJob.TrackItem item : job.tracks) {
                boolean failed = item.status == DownloadJob.TrackItem.Status.FAILED_TEMPORARY
                        || item.status == DownloadJob.TrackItem.Status.FAILED_PERMANENT;
                if (failed && (retryAll || item.videoId.equals(videoId))) {
                    item.status = DownloadJob.TrackItem.Status.WAITING;
                    item.progress = 0;
                    item.nextRetryAt = 0L;
                    changed = true;
                }
            }
            rebuildFailureSummary(job);
        }
        if (!changed) {
            return;
        }
        synchronized (jobs) {
            job.queuePosition = DownloadScheduler.nextQueuePosition(jobs);
            DownloadScheduler.sort(jobs);
        }
        job.error = null;
        job.cancelRequested = false;
        job.pauseRequested = false;
        job.completionHandled = false;
        job.pendingId = 0L;
        job.status = DownloadJob.Status.QUEUED;
        persistPending(job);
        pendingCount.incrementAndGet();
        publish(true);
        notificationController.startForeground(job);
        scheduleDispatcher();
    }

    private void removeFailedInternal(long jobId, @Nullable String videoId) {
        if (videoId == null) {
            return;
        }
        DownloadJob job = findJob(jobId);
        if (job == null || job.isActive()) {
            return;
        }
        boolean removed;
        synchronized (job.tracks) {
            removed = job.tracks.removeIf(item -> videoId.equals(item.videoId)
                    && (item.status == DownloadJob.TrackItem.Status.FAILED_TEMPORARY
                    || item.status == DownloadJob.TrackItem.Status.FAILED_PERMANENT));
            rebuildFailureSummary(job);
        }
        if (!removed) {
            return;
        }
        if (job.failedCount == 0 && job.status == DownloadJob.Status.COMPLETED_WITH_ERRORS) {
            job.status = DownloadJob.Status.DONE;
        }
        if (job.playlistName != null) {
            String playlist = job.playlistName;
            VibyDatabase.dbExecutor.execute(() -> dao.deletePending(playlist, videoId));
        }
        publish(true);
    }

    @Nullable
    private DownloadJob findJob(long jobId) {
        synchronized (jobs) {
            for (DownloadJob job : jobs) {
                if (job.id == jobId) {
                    return job;
                }
            }
        }
        return null;
    }

    @Nullable
    private DownloadJob.TrackItem findTrack(DownloadJob job, String videoId) {
        synchronized (job.tracks) {
            for (DownloadJob.TrackItem item : job.tracks) {
                if (videoId.equals(item.videoId)) {
                    return item;
                }
            }
        }
        return null;
    }

    private void rebuildFailureSummary(DownloadJob job) {
        job.failedCount = 0;
        job.failedTitles.clear();
        for (DownloadJob.TrackItem item : job.tracks) {
            if (item.status == DownloadJob.TrackItem.Status.FAILED_TEMPORARY
                    || item.status == DownloadJob.TrackItem.Status.FAILED_PERMANENT) {
                job.failedCount++;
                job.failedTitles.add(item.title);
            }
        }
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
            if (!DownloadQueueActions.moveTrack(job, videoId, targetVideoId)) {
                return;
            }
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
                        == DownloadJob.TrackItem.Status.DOWNLOADED
                        || targetJob.tracks.get(firstWaiting).status
                        == DownloadJob.TrackItem.Status.FAILED_TEMPORARY
                        || targetJob.tracks.get(firstWaiting).status
                        == DownloadJob.TrackItem.Status.FAILED_PERMANENT)) {
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
        VibyDatabase.dbExecutor.execute(
                () -> queueRepository.updateTrackOrder(job, order));
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

    private void cleanupPendingPlaceholders(DownloadJob job) {
        if (!job.isPlaylist || job.playlistName == null) {
            return;
        }
        List<String> pendingVideoIds = new ArrayList<>();
        synchronized (job.tracks) {
            for (DownloadJob.TrackItem item : job.tracks) {
                if (item.status != DownloadJob.TrackItem.Status.DOWNLOADED) {
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
