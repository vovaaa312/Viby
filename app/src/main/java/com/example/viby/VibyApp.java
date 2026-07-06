package com.example.viby;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class VibyApp extends Application {

    private static final String TAG = "VibyApp";

    public static final String CHANNEL_DOWNLOADS = "downloads";

    private static final String PREFS = "viby_prefs";
    private static final String KEY_LAST_UPDATE_CHECK = "ytdlp_last_update_check";

    public enum EngineState { INITIALIZING, READY, FAILED }

    public interface UpdateCallback {
        void onResult(YoutubeDL.UpdateStatus status, Exception error);
    }

    private final MutableLiveData<EngineState> engineState =
            new MutableLiveData<>(EngineState.INITIALIZING);
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();
        background.execute(this::initEngine);
        // недокачанное с прошлого запуска продолжаем качать
        com.example.viby.download.DownloadService.restorePending(this);
    }

    public LiveData<EngineState> getEngineState() {
        return engineState;
    }

    public boolean isEngineReady() {
        return engineState.getValue() == EngineState.READY;
    }

    /** Ручное обновление yt-dlp из бокового меню. Колбэк приходит на main thread. */
    public void updateYtDlp(UpdateCallback callback) {
        background.execute(() -> {
            try {
                YoutubeDL.UpdateStatus status =
                        YoutubeDL.getInstance().updateYoutubeDL(this, YoutubeDL.UpdateChannel._STABLE);
                rememberUpdateCheck();
                mainHandler.post(() -> callback.onResult(status, null));
            } catch (Exception e) {
                Log.w(TAG, "manual yt-dlp update failed", e);
                mainHandler.post(() -> callback.onResult(null, e));
            }
        });
    }

    private void initEngine() {
        try {
            YoutubeDL.getInstance().init(this);
            FFmpeg.getInstance().init(this);
            engineState.postValue(EngineState.READY);
            Log.i(TAG, "yt-dlp initialized, version: " + YoutubeDL.getInstance().version(this));
        } catch (Exception e) {
            Log.e(TAG, "yt-dlp init failed", e);
            engineState.postValue(EngineState.FAILED);
            return;
        }
        autoUpdateIfDue();
    }

    /** Автообновление yt-dlp не чаще раза в сутки, чтобы YouTube-поломки чинились сами. */
    private void autoUpdateIfDue() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        long last = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L);
        if (System.currentTimeMillis() - last < TimeUnit.DAYS.toMillis(1)) {
            return;
        }
        try {
            YoutubeDL.UpdateStatus status =
                    YoutubeDL.getInstance().updateYoutubeDL(this, YoutubeDL.UpdateChannel._STABLE);
            rememberUpdateCheck();
            Log.i(TAG, "yt-dlp auto-update: " + status);
        } catch (Exception e) {
            // нет сети и т.п. — попробуем при следующем запуске
            Log.w(TAG, "yt-dlp auto-update failed", e);
        }
    }

    private void rememberUpdateCheck() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis())
                .apply();
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel downloads = new NotificationChannel(
                    CHANNEL_DOWNLOADS,
                    getString(R.string.channel_downloads),
                    NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(downloads);
        }
    }
}
