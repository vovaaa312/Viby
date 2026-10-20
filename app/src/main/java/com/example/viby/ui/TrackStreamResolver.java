package com.example.viby.ui;

import android.content.Context;

import androidx.annotation.Nullable;

import com.example.viby.data.Track;
import com.example.viby.util.YtCookies;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.mapper.VideoInfo;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Resolves short-lived online audio URLs and suppresses stale async results. */
final class TrackStreamResolver {

    interface Backend {
        String resolve(String sourceUrl) throws Exception;
    }

    interface Runner {
        void execute(Runnable task);

        void shutdown();
    }

    interface Callback {
        void onResolved(String streamUrl);

        void onFailure(Exception error);
    }

    private final Runner runner;
    private final Consumer<Runnable> callbackDispatcher;
    private final Backend backend;
    private final AtomicLong requestSequence = new AtomicLong();

    TrackStreamResolver(MainActivity activity) {
        Context context = activity.getApplicationContext();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        runner = new Runner() {
            @Override
            public void execute(Runnable task) {
                executor.execute(task);
            }

            @Override
            public void shutdown() {
                executor.shutdownNow();
            }
        };
        callbackDispatcher = activity::runOnUiThread;
        backend = sourceUrl -> {
            YoutubeDLRequest request = new YoutubeDLRequest(sourceUrl);
            request.addOption("--no-playlist");
            request.addOption("--format", "bestaudio[ext=m4a]/bestaudio/best");
            if (YtCookies.isLoggedIn(context)) {
                request.addOption("--cookies",
                        YtCookies.file(context).getAbsolutePath());
            }
            VideoInfo info = YoutubeDL.getInstance().getInfo(request);
            String streamUrl = info.getUrl();
            if (streamUrl == null || streamUrl.isEmpty()) {
                throw new IllegalStateException("yt-dlp returned no stream URL");
            }
            return streamUrl;
        };
    }

    TrackStreamResolver(Runner runner, Consumer<Runnable> callbackDispatcher,
                        Backend backend) {
        this.runner = runner;
        this.callbackDispatcher = callbackDispatcher;
        this.backend = backend;
    }

    static boolean canResolve(@Nullable Track track) {
        return track != null
                && track.videoId != null && !track.videoId.isEmpty()
                && track.sourceUrl != null && !track.sourceUrl.isEmpty();
    }

    boolean resolve(Track track, Callback callback) {
        if (!canResolve(track)) {
            return false;
        }
        String sourceUrl = track.sourceUrl;
        long requestId = requestSequence.incrementAndGet();
        runner.execute(() -> {
            try {
                String streamUrl = backend.resolve(sourceUrl);
                dispatchIfCurrent(requestId, () -> callback.onResolved(streamUrl));
            } catch (Exception error) {
                dispatchIfCurrent(requestId, () -> callback.onFailure(error));
            }
        });
        return true;
    }

    void destroy() {
        requestSequence.incrementAndGet();
        runner.shutdown();
    }

    private void dispatchIfCurrent(long requestId, Runnable callback) {
        callbackDispatcher.accept(() -> {
            if (requestId == requestSequence.get()) {
                callback.run();
            }
        });
    }
}
