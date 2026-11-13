package com.example.viby.download;

import com.yausername.youtubedl_android.YoutubeDLRequest;

import java.io.File;

/** Defines yt-dlp/FFmpeg audio post-processing and validates its output. */
final class MediaConverter {

    void configure(YoutubeDLRequest request, String format) {
        configure(request, format, "0");
    }

    /**
     * @param audioQuality yt-dlp --audio-quality: "0" (best VBR) or a bitrate like "320K"
     */
    void configure(YoutubeDLRequest request, String format, String audioQuality) {
        if (DownloadSettings.Format.M4A.extension.equals(format)) {
            // take YouTube's AAC stream as is, so nothing is re-encoded
            request.addOption("-f", "bestaudio[ext=m4a]/bestaudio");
        }
        request.addOption("-x");
        request.addOption("--audio-format", format);
        request.addOption("--audio-quality", audioQuality);
        request.addOption("--embed-thumbnail");
        request.addOption("--embed-metadata");
    }

    File findOutput(File directory, String baseName, String format) {
        File expected = new File(directory, baseName + "." + format);
        if (expected.isFile()) {
            return expected;
        }
        File[] candidates = directory.listFiles((dir, name) -> {
            File candidate = new File(dir, name);
            int dot = name.lastIndexOf('.');
            return candidate.isFile()
                    && dot > 0
                    && name.substring(0, dot).equals(baseName);
        });
        if (candidates != null && candidates.length > 0) {
            return candidates[0];
        }
        throw new IllegalStateException(
                "Файл не найден после конвертации: " + baseName);
    }
}
