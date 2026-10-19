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
    private final Backend backend;

    static AudioDownloader create(YoutubeExtractor youtubeExtractor) {
        return new AudioDownloader(youtubeExtractor,
                (request, processId, listener) -> YoutubeDL.getInstance().execute(
                        request, processId, (progress, etaSeconds, line) -> {
                            listener.onProgress(progress, etaSeconds, line);
                            return Unit.INSTANCE;
                        }));
    }

    AudioDownloader(YoutubeExtractor youtubeExtractor, Backend backend) {
        this.youtubeExtractor = youtubeExtractor;
        this.backend = backend;
    }

    YoutubeDLRequest buildRequest(String videoUrl, File directory,
                                  String baseName, String format,
                                  @Nullable String playerClients) {
        String template = new File(directory,
                baseName.replace("%", "%%") + ".%(ext)s").getAbsolutePath();
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
        youtubeExtractor.applyAuthentication(request);
        return request;
    }

    File download(String videoUrl, File directory, String baseName,
                  String format, @Nullable String playerClients,
                  String processId, ProgressListener listener) throws Exception {
        YoutubeDLRequest request = buildRequest(
                videoUrl, directory, baseName, format, playerClients);
        backend.execute(request, processId, listener);
        return findOutput(directory, baseName, format);
    }

    static File findOutput(File directory, String baseName, String format) {
        File expected = new File(directory, baseName + "." + format);
        if (expected.exists()) {
            return expected;
        }
        File[] candidates = directory.listFiles((dir, name) -> {
            int dot = name.lastIndexOf('.');
            return dot > 0 && name.substring(0, dot).equals(baseName);
        });
        if (candidates != null && candidates.length > 0) {
            return candidates[0];
        }
        throw new IllegalStateException(
                "Файл не найден после загрузки: " + baseName);
    }
}
