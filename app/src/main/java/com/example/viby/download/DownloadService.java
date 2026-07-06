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
    public static final String ACTION_CANCEL_ALL = "com.example.viby.action.CANCEL_ALL";
    public static final String ACTION_PAUSE_ALL = "com.example.viby.action.PAUSE_ALL";
    public static final String ACTION_RESUME_ALL = "com.example.viby.action.RESUME_ALL";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_PLAYLIST = "playlist";
    public static final String EXTRA_IS_PLAYLIST = "is_playlist";
    public static final String EXTRA_PENDING_ID = "pending_id";

    private static final int NOTIF_PROGRESS_ID = 1;
    private static final int ENGINE_WAIT_SECONDS = 60;

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

    public static void sendAction(Context context, String action) {
        context.startService(new Intent(context, DownloadService.class).setAction(action));
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
                        .putExtra(EXTRA_PENDING_ID, pending.id);
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
            case ACTION_CANCEL_ALL:
                cancelAll();
                break;
            case ACTION_PAUSE_ALL:
                setPausedAll(true);
                break;
            case ACTION_RESUME_ALL:
                setPausedAll(false);
                break;
            case ACTION_ENQUEUE:
                String url = intent.getStringExtra(EXTRA_URL);
                String playlist = intent.getStringExtra(EXTRA_PLAYLIST);
                boolean isPlaylist = intent.getBooleanExtra(EXTRA_IS_PLAYLIST, false);
                long pendingId = intent.getLongExtra(EXTRA_PENDING_ID, 0L);
                if (url != null && !url.trim().isEmpty()
                        && !hasActiveJob(url.trim(), playlist)) {
                    DownloadJob job = new DownloadJob(url.trim(), playlist, isPlaylist);
                    job.pendingId = pendingId;
                    if (pendingId == 0) {
                        persistPending(job);
                    }
                    synchronized (jobs) {
                        jobs.add(job);
                    }
                    pendingCount.incrementAndGet();
                    publish(true);
                    startForeground(NOTIF_PROGRESS_ID, buildProgressNotification(job));
                    worker.execute(() -> runJob(job));
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

    private void runJob(DownloadJob job) {
        activeJob = job;
        try {
            if (job.cancelRequested) {
                job.status = DownloadJob.Status.CANCELED;
                return;
            }
            waitForEngine();
            if (job.isPlaylist) {
                runPlaylistJob(job);
            } else {
                runSingleJob(job);
            }
        } catch (Exception e) {
            Log.e(TAG, "job failed: " + job.url, e);
            job.status = job.cancelRequested
                    ? DownloadJob.Status.CANCELED : DownloadJob.Status.FAILED;
            job.error = shortError(e);
        } finally {
            activeJob = null;
            removePending(job); // задание завершилось — восстанавливать больше нечего
            publish(true);
            postResultNotification(job);
            if (pendingCount.decrementAndGet() == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
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
        VibyDatabase.dbExecutor.execute(() -> {
            com.example.viby.data.PendingDownload pending =
                    new com.example.viby.data.PendingDownload();
            pending.url = job.url;
            pending.playlistName = job.playlistName;
            pending.isPlaylist = job.isPlaylist;
            pending.createdAt = System.currentTimeMillis();
            job.pendingId = VibyDatabase.get(this).pendingDownloadDao().insert(pending);
        });
    }

    private void removePending(DownloadJob job) {
        // dbExecutor последовательный: insert из persistPending выполнится раньше
        VibyDatabase.dbExecutor.execute(() -> {
            if (job.pendingId != 0) {
                VibyDatabase.get(this).pendingDownloadDao().delete(job.pendingId);
            }
        });
    }

    private void runSingleJob(DownloadJob job) throws Exception {
        job.status = DownloadJob.Status.PREPARING;
        publish(true);
        updateProgressNotification(job);

        VideoInfo info = YoutubeDL.getInstance().getInfo(job.url);
        String videoId = info.getId();
        String title = info.getTitle() != null ? info.getTitle() : job.url;
        job.title = title;
        job.currentTrackTitle = title;
        String playlist = job.playlistName != null && !job.playlistName.isEmpty()
                ? job.playlistName : getString(R.string.default_playlist);
        job.playlistName = playlist;
        publish(true);

        if (videoId != null && dao.exists(playlist, videoId)) {
            job.status = DownloadJob.Status.DONE;
            return;
        }

        long durationMs = info.getDuration() * 1000L;
        downloadTrack(job, job.url, videoId, title, info.getUploader(), durationMs, playlist);
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
        String processId = "job-" + job.id + "-info";
        activeProcessId = processId;
        YoutubeDLResponse response =
                YoutubeDL.getInstance().execute(infoRequest, processId, null);
        activeProcessId = null;

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

        for (int i = 0; i < entries.length(); i++) {
            if (job.cancelRequested) {
                break;
            }
            waitWhilePaused(job);
            if (job.cancelRequested) {
                break;
            }
            job.currentIndex = i + 1;
            job.progress = 0;
            job.downloadedBytes = 0;
            job.totalBytes = 0;
            JSONObject entry = entries.getJSONObject(i);
            String videoId = entry.optString("id", "");
            String title = entry.optString("title", videoId);
            String uploader = entry.optString("uploader",
                    entry.optString("channel", null));
            long durationMs = (long) (entry.optDouble("duration", 0) * 1000);
            job.currentTrackTitle = title;
            publish(true);
            updateProgressNotification(job);
            try {
                if (videoId.isEmpty() || dao.exists(playlist, videoId)) {
                    continue;
                }
                String videoUrl = "https://www.youtube.com/watch?v=" + videoId;
                downloadTrack(job, videoUrl, videoId, title, uploader, durationMs, playlist);
            } catch (Exception e) {
                if (job.cancelRequested) {
                    break;
                }
                Log.w(TAG, "playlist entry failed: " + title, e);
                job.failedCount++;
                job.failedTitles.add(title);
            }
        }
        job.status = job.cancelRequested
                ? DownloadJob.Status.CANCELED : DownloadJob.Status.DONE;
    }

    // ------------------------------------------------------------- download

    private void downloadTrack(DownloadJob job, String videoUrl, String videoId,
                               String title, @Nullable String uploader,
                               long durationMs, String playlist) throws Exception {
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
            resultFile = downloadWithPauseRetry(job, videoUrl, dir, baseName, "mp3");
        } catch (Exception e) {
            if (job.cancelRequested) {
                throw e;
            }
            // fallback: если конвертация в mp3 не удалась — пробуем m4a
            Log.w(TAG, "mp3 failed, retrying as m4a: " + title, e);
            resultFile = downloadWithPauseRetry(job, videoUrl, dir, baseName, "m4a");
        }

        Track track = new Track();
        track.videoId = videoId;
        track.title = title;
        track.uploader = uploader;
        track.durationMs = durationMs;
        track.filePath = resultFile.getAbsolutePath();
        track.playlistName = playlist;
        track.thumbnailUrl = videoId != null && !videoId.isEmpty()
                ? "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg" : null;
        track.position = dao.nextPosition(playlist);
        track.createdAt = System.currentTimeMillis();
        dao.insert(track);
    }

    /** Пауза = убить процесс yt-dlp; при возобновлении он докачивает .part-файл. */
    private File downloadWithPauseRetry(DownloadJob job, String videoUrl, File dir,
                                        String baseName, String format) throws Exception {
        while (true) {
            waitWhilePaused(job);
            if (job.cancelRequested) {
                throw new InterruptedException("canceled");
            }
            try {
                return executeDownload(job, videoUrl, dir, baseName, format);
            } catch (Exception e) {
                if (job.pauseRequested && !job.cancelRequested) {
                    continue; // процесс убит паузой — подождём и продолжим
                }
                throw e;
            }
        }
    }

    private void waitWhilePaused(DownloadJob job) throws InterruptedException {
        if (!job.pauseRequested || job.cancelRequested) {
            return;
        }
        job.status = DownloadJob.Status.PAUSED;
        publish(true);
        updateProgressNotification(job);
        while (job.pauseRequested && !job.cancelRequested) {
            Thread.sleep(300);
        }
        if (!job.cancelRequested) {
            job.status = DownloadJob.Status.DOWNLOADING;
            publish(true);
            updateProgressNotification(job);
        }
    }

    private File executeDownload(DownloadJob job, String videoUrl, File dir,
                                 String baseName, String format) throws Exception {
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

        String processId = "job-" + job.id;
        activeProcessId = processId;
        try {
            YoutubeDL.getInstance().execute(request, processId,
                    (progress, etaSeconds, line) -> {
                        job.progress = Math.max(0, Math.min(100, Math.round(progress)));
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

    private void setPausedAll(boolean paused) {
        synchronized (jobs) {
            for (DownloadJob job : jobs) {
                if (job.isActive()) {
                    job.pauseRequested = paused;
                }
            }
        }
        if (paused) {
            killActiveProcess();
        }
        publish(true);
        DownloadJob job = activeJob;
        if (job != null) {
            updateProgressNotification(job);
        }
    }

    private void cancelAll() {
        synchronized (jobs) {
            for (DownloadJob job : jobs) {
                if (job.isActive()) {
                    job.cancelRequested = true;
                    job.pauseRequested = false;
                }
            }
        }
        killActiveProcess();
        publish(true);
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
        PendingIntent cancelIntent = PendingIntent.getService(
                this, 0,
                new Intent(this, DownloadService.class).setAction(ACTION_CANCEL_ALL),
                PendingIntent.FLAG_IMMUTABLE);
        boolean paused = job.status == DownloadJob.Status.PAUSED || job.pauseRequested;
        PendingIntent pauseIntent = PendingIntent.getService(
                this, 1,
                new Intent(this, DownloadService.class)
                        .setAction(paused ? ACTION_RESUME_ALL : ACTION_PAUSE_ALL),
                PendingIntent.FLAG_IMMUTABLE);

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
