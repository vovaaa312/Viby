package com.example.viby.playback;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import androidx.annotation.Nullable;

import com.example.viby.data.Track;
import com.example.viby.data.TrackDao;
import com.example.viby.data.VibyDatabase;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Measures the loudness of downloaded files once, in the background, on low-priority
 * threads. Decoding a whole library costs real battery, so the backlog is worked off only
 * while the phone charges (recently played tracks first); on battery just the playing and
 * the next track are measured, see {@link #measureSoon}.
 */
public final class LoudnessScanner {

    private static final String TAG = "LoudnessScanner";
    /**
     * Decoding is bound by the round trip to the codec service for every frame, not by the
     * CPU, so a few files measured side by side finish several times faster.
     */
    private static final int WORKERS = 3;
    private static final int BATCH = 24;

    private static final ExecutorService coordinator = Executors.newSingleThreadExecutor(
            r -> backgroundThread(r, "viby-loudness"));
    private static final ExecutorService workers = Executors.newFixedThreadPool(WORKERS,
            r -> backgroundThread(r, "viby-loudness-worker"));
    private static final ExecutorService onDemand = Executors.newSingleThreadExecutor(
            r -> backgroundThread(r, "viby-loudness-now"));
    private static final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean queued = new AtomicBoolean();

    private LoudnessScanner() {
    }

    private static Thread backgroundThread(Runnable r, String name) {
        Thread thread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, name);
        thread.setDaemon(true);
        return thread;
    }

    /** True while the phone is plugged in, also when the battery is already full. */
    public static boolean isCharging(Context context) {
        Intent battery = context.getApplicationContext().registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return battery != null && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    /**
     * Works off the unmeasured library while the phone charges; does nothing on battery.
     * Safe to call often.
     */
    public static void requestScan(Context context) {
        if (!isCharging(context)) {
            return;
        }
        if (!queued.compareAndSet(false, true)) {
            return;
        }
        Context app = context.getApplicationContext();
        coordinator.execute(() -> {
            queued.set(false);
            try {
                scan(app);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                Log.w(TAG, "Loudness scan stopped", error);
            }
        });
    }

    static int scan(Context context) throws InterruptedException {
        TrackDao dao = VibyDatabase.get(context).trackDao();
        int measured = 0;
        while (true) {
            if (!isCharging(context)) {
                return measured; // unplugged: the rest waits for the next charge
            }
            List<Track> batch = dao.getLoudnessPendingSync(BATCH);
            if (batch.isEmpty()) {
                return measured;
            }
            Set<String> paths = new LinkedHashSet<>();
            for (Track track : batch) {
                paths.add(track.filePath);
            }
            List<Callable<Void>> jobs = new ArrayList<>(paths.size());
            for (String path : paths) {
                jobs.add(() -> {
                    measure(dao, path);
                    return null;
                });
            }
            // every job stores its own result, so the batch query never sees them again
            long started = android.os.SystemClock.elapsedRealtime();
            workers.invokeAll(jobs);
            Log.d(TAG, "Measured " + paths.size() + " files in "
                    + (android.os.SystemClock.elapsedRealtime() - started) + " ms");
            measured += paths.size();
        }
    }

    /**
     * Measures one file right away on its own thread, also on battery: the cost of a single
     * track is small next to playing it. {@code onMeasured} runs on the main thread.
     */
    public static void measureSoon(Context context, String path,
                                   @Nullable Runnable onMeasured) {
        if (path == null || path.isEmpty() || !inFlight.add(path)) {
            return;
        }
        TrackDao dao = VibyDatabase.get(context).trackDao();
        onDemand.execute(() -> {
            Log.d(TAG, "Measuring now: " + new File(path).getName());
            try {
                measure(dao, path);
            } finally {
                inFlight.remove(path);
            }
            if (onMeasured != null) {
                main.post(onMeasured);
            }
        });
    }

    private static void measure(TrackDao dao, String path) {
        Float lufs = null;
        Float peak = null;
        if (new File(path).isFile()) {
            try {
                LoudnessAnalyzer.Result result = LoudnessAnalyzer.analyze(path);
                if (result != null) {
                    lufs = result.lufs;
                    peak = result.peak;
                }
            } catch (Exception error) {
                // marked as scanned anyway, so a broken file is not retried forever
                Log.w(TAG, "Cannot measure " + path, error);
            }
        }
        dao.updateLoudness(path, lufs, peak);
    }
}
