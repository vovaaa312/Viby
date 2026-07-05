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

import kotlin.Unit;

/**
 * Foreground-сервис с последовательной очередью загрузок yt-dlp.
 * Треки и плейлисты добавляются интентом ACTION_ENQUEUE.
 */
public class DownloadService extends Service {

    private static final String TAG = "DownloadService";

    public static final String ACTION_ENQUEUE = "com.example.viby.action.ENQUEUE";
    public static final String ACTION_CANCEL_ALL = "com.example.viby.action.CANCEL_ALL";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_PLAYLIST = "playlist";
    public static final String EXTRA_IS_PLAYLIST = "is_playlist";

    private static final int NOTIF_PROGRESS_ID = 1;
    private static final int ENGINE_WAIT_SECONDS = 60;

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

    private final List<DownloadJob> jobs = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger pendingCount = new AtomicInteger(0);
    private final AtomicInteger notifIdSeq = new AtomicInteger(100);
    private volatile String activeProcessId;

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
        if (intent == null) {
            return START_NOT_STICKY;
        }
        if (ACTION_CANCEL_ALL.equals(intent.getAction())) {
            cancelAll();
            return START_NOT_STICKY;
        }
        if (ACTION_ENQUEUE.equals(intent.getAction())) {
            String url = intent.getStringExtra(EXTRA_URL);
            String playlist = intent.getStringExtra(EXTRA_PLAYLIST);
            boolean isPlaylist = intent.getBooleanExtra(EXTRA_IS_PLAYLIST, false);
            if (url != null && !url.trim().isEmpty()) {
                DownloadJob job = new DownloadJob(url.trim(), playlist, isPlaylist);
                synchronized (jobs) {
                    jobs.add(job);
                }
                pendingCount.incrementAndGet();
                publish(true);
                startForeground(NOTIF_PROGRESS_ID, buildProgressNotification(job));
                worker.execute(() -> runJob(job));
            }
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
            publish(true);
            postResultNotification(job);
            if (pendingCount.decrementAndGet() == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }
    }

    private void runSingleJob(DownloadJob job) throws Exception {
        job.status = DownloadJob.Status.PREPARING;
        publish(true);
        updateProgressNotification(job);

        VideoInfo info = YoutubeDL.getInstance().getInfo(job.url);
        String videoId = info.getId();
        String title = info.getTitle() != null ? info.getTitle() : job.url;
        job.title = title;
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

        JSONArray entries = root.optJSONArray("entries");
        if (entries == null || entries.length() == 0) {
            throw new IllegalStateException("В плейлисте не нашлось видео");
        }
        job.totalCount = entries.length();
        publish(true);

        for (int i = 0; i < entries.length(); i++) {
            if (job.cancelRequested) {
                break;
            }
            job.currentIndex = i + 1;
            job.progress = 0;
            JSONObject entry = entries.getJSONObject(i);
            String videoId = entry.optString("id", "");
            String title = entry.optString("title", videoId);
            String uploader = entry.optString("uploader",
                    entry.optString("channel", null));
            long durationMs = (long) (entry.optDouble("duration", 0) * 1000);
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
        File dir = StorageHelper.playlistDir(this, playlist);
        String baseName = StorageHelper.sanitize(title);
        // защита от одинаковых названий разных видео
        if (new File(dir, baseName + ".mp3").exists()
                || new File(dir, baseName + ".m4a").exists()) {
            baseName = baseName + " [" + videoId + "]";
        }

        File resultFile;
        try {
            resultFile = executeDownload(job, videoUrl, dir, baseName, "mp3");
        } catch (Exception e) {
            if (job.cancelRequested) {
                throw e;
            }
            // fallback: если конвертация в mp3 не удалась — пробуем m4a
            Log.w(TAG, "mp3 failed, retrying as m4a: " + title, e);
            resultFile = executeDownload(job, videoUrl, dir, baseName, "m4a");
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
        File[] candidates = dir.listFiles((d, name) -> {
            int dot = name.lastIndexOf('.');
            return dot > 0 && name.substring(0, dot).equals(baseName);
        });
        if (candidates != null && candidates.length > 0) {
            return candidates[0];
        }
        throw new IllegalStateException("Файл не найден после загрузки: " + baseName);
    }

    // ------------------------------------------------------------ cancel

    private void cancelAll() {
        synchronized (jobs) {
            for (DownloadJob job : jobs) {
                if (job.status == DownloadJob.Status.QUEUED
                        || job.status == DownloadJob.Status.PREPARING
                        || job.status == DownloadJob.Status.DOWNLOADING) {
                    job.cancelRequested = true;
                }
            }
        }
        String processId = activeProcessId;
        if (processId != null) {
            try {
                YoutubeDL.getInstance().destroyProcessById(processId);
            } catch (Exception e) {
                Log.w(TAG, "destroyProcessById failed", e);
            }
        }
        publish(true);
    }

    // ------------------------------------------------------------ helpers

    private void waitForEngine() throws InterruptedException {
        VibyApp app = (VibyApp) getApplication();
        for (int i = 0; i < ENGINE_WAIT_SECONDS * 2; i++) {
            if (app.isEngineReady()) {
                return;
            }
            if (app.getEngineState().getValue() == VibyApp.EngineState.FAILED) {
                throw new IllegalStateException("yt-dlp не смог инициализироваться");
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("yt-dlp так и не инициализировался");
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

        String text = job.isPlaylist && job.totalCount > 0
                ? getString(R.string.notif_download_playlist_progress,
                        job.currentIndex, job.totalCount)
                : null;
        return new NotificationCompat.Builder(this, VibyApp.CHANNEL_DOWNLOADS)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(getString(R.string.notif_downloading,
                        job.title != null ? job.title : job.url))
                .setContentText(text)
                .setProgress(100, job.progress, job.status == DownloadJob.Status.PREPARING)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
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
        Notification notification =
                new NotificationCompat.Builder(this, VibyApp.CHANNEL_DOWNLOADS)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle(job.title != null ? job.title : job.url)
                        .setContentText(text)
                        .setAutoCancel(true)
                        .build();
        notificationManager.notify(notifIdSeq.getAndIncrement(), notification);
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
