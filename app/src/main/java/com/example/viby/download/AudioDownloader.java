package com.example.viby.download;

import androidx.annotation.Nullable;

import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;

import java.io.File;

import kotlin.Unit;

/** Builds and runs yt-dlp audio transfers, independent from queue and UI state. */
final class AudioDownloader {

    interface ProgressListener {
        void onProgress(float progress, long etaSeconds, @Nullable String outputLine);
    }

    interface Backend {
        void execute(YoutubeDLRequest request, String processId,
                     ProgressListener listener) throws Exception;
    }

    private final YoutubeExtractor youtubeExtractor;
    private final MediaConverter mediaConverter;
    private final Backend backend;

    static AudioDownloader create(YoutubeExtractor youtubeExtractor) {
        return new AudioDownloader(youtubeExtractor, new MediaConverter(),
                (request, processId, listener) -> YoutubeDL.getInstance().execute(
                        request, processId, (progress, etaSeconds, line) -> {
                            listener.onProgress(progress, etaSeconds, line);
                            return Unit.INSTANCE;
                        }));
    }

    AudioDownloader(YoutubeExtractor youtubeExtractor,
                    MediaConverter mediaConverter, Backend backend) {
        this.youtubeExtractor = youtubeExtractor;
        this.mediaConverter = mediaConverter;
        this.backend = backend;
    }

    YoutubeDLRequest buildRequest(String videoUrl, File directory,
                                  String baseName, String format,
                                  @Nullable String playerClients) {
        String template = new File(directory,
                baseName.replace("%", "%%") + ".%(ext)s").getAbsolutePath();
        YoutubeDLRequest request = new YoutubeDLRequest(videoUrl);
        request.addOption("--no-playlist");
        request.addOption("--no-mtime");
        request.addOption("-o", template);
        mediaConverter.configure(request, format);
        if (playerClients != null) {
            request.addOption("--extractor-args",
                    "youtube:player_client=" + playerClients);
        }
        youtubeExtractor.applyAuthentication(request);
        return request;
    }

    File download(String videoUrl, File directory, String baseName,
                  String format, @Nullable String playerClients,
                  String processId, ProgressListener listener) throws Exception {
        YoutubeDLRequest request = buildRequest(
                videoUrl, directory, baseName, format, playerClients);
        backend.execute(request, processId, listener);
        return mediaConverter.findOutput(directory, baseName, format);
    }
}
