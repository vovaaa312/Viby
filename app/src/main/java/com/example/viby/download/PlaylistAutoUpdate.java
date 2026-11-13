package com.example.viby.download;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import com.example.viby.data.PlaylistSource;
import com.example.viby.data.VibyDatabase;
import com.example.viby.util.YoutubeUrlParser;

import java.util.ArrayList;
import java.util.List;

/**
 * At app launch, re-reads every playlist that was downloaded from YouTube and quietly
 * downloads the tracks added there since. Uses the regular "refresh playlist" job, so
 * already downloaded tracks are skipped and the order follows YouTube.
 */
public final class PlaylistAutoUpdate {

    private static final String TAG = "PlaylistAutoUpdate";
    private static final String PREFS = "viby_download";
    private static final String KEY_ENABLED = "auto_update_playlists";
    private static final String KEY_LAST_RUN = "auto_update_last_run";
    /** Reopening the app right away does not list every playlist again. */
    static final long MIN_INTERVAL_MS = 10 * 60 * 1000L;

    private PlaylistAutoUpdate() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /** Call when the app is opened; starts the check unless it ran a moment ago. */
    public static void runIfDue(Context context) {
        Context app = context.getApplicationContext();
        long now = System.currentTimeMillis();
        long last = prefs(app).getLong(KEY_LAST_RUN, 0L);
        if (!isEnabled(app) || !isDue(last, now) || !hasInternet(app)) {
            return;
        }
        prefs(app).edit().putLong(KEY_LAST_RUN, now).apply();
        VibyDatabase.dbExecutor.execute(() -> {
            List<PlaylistSource> sources = updatable(
                    VibyDatabase.get(app).playlistSourceDao().getAllSync());
            if (sources.isEmpty()) {
                return;
            }
            new Handler(Looper.getMainLooper()).post(() -> {
                Log.d(TAG, "Checking " + sources.size() + " playlists for new tracks");
                for (PlaylistSource source : sources) {
                    try {
                        DownloadService.enqueueAutomaticRefresh(app, source.sourceUrl,
                                source.playlistName);
                    } catch (RuntimeException notAllowed) {
                        // the app went to the background before the check could start
                        Log.w(TAG, "Cannot start the playlist check", notAllowed);
                        return;
                    }
                }
            });
        });
    }

    static boolean isDue(long lastRun, long now) {
        return lastRun <= 0 || now < lastRun || now - lastRun >= MIN_INTERVAL_MS;
    }

    /** Real YouTube playlists only: a radio mix is a new random list every time. */
    static List<PlaylistSource> updatable(@Nullable List<PlaylistSource> sources) {
        List<PlaylistSource> result = new ArrayList<>();
        if (sources == null) {
            return result;
        }
        for (PlaylistSource source : sources) {
            String url = source.sourceUrl;
            if (url.isEmpty() || YoutubeUrlParser.isRadioMixUrl(url)
                    || YoutubeUrlParser.playlistId(url) == null) {
                continue;
            }
            result.add(source);
        }
        return result;
    }

    private static boolean hasInternet(Context context) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        if (manager == null) {
            return false;
        }
        Network network = manager.getActiveNetwork();
        NetworkCapabilities capabilities = network != null
                ? manager.getNetworkCapabilities(network) : null;
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
}
