package com.example.viby.library;

import com.example.viby.data.Track;

import java.io.File;
import java.util.List;

/** Extended M3U8 playlists whose entries match the mirror-folder layout. */
public final class PlaylistExporter {

    private PlaylistExporter() {
    }

    /**
     * Downloaded tracks are written as "Playlist/track.mp3" relative to the Music root, so the
     * file works next to the mirrored folders; missing tracks fall back to their YouTube URL.
     */
    public static String toM3u(String playlistName, List<Track> tracks, File musicRoot) {
        StringBuilder out = new StringBuilder("#EXTM3U\n");
        out.append("#PLAYLIST:").append(singleLine(playlistName)).append('\n');
        for (Track track : tracks) {
            String location = location(track, musicRoot);
            if (location == null) {
                continue;
            }
            long seconds = track.durationMs > 0 ? Math.round(track.durationMs / 1000.0) : -1;
            out.append("#EXTINF:").append(seconds).append(',');
            if (track.uploader != null && !track.uploader.isEmpty()) {
                out.append(singleLine(track.uploader)).append(" - ");
            }
            out.append(singleLine(track.title)).append('\n');
            out.append(location).append('\n');
        }
        return out.toString();
    }

    static String location(Track track, File musicRoot) {
        if (track.downloaded && track.filePath != null && !track.filePath.isEmpty()) {
            String relative = LibraryPaths.relativePath(musicRoot, track.filePath);
            if (relative != null) {
                return relative;
            }
        }
        if (track.sourceUrl != null && !track.sourceUrl.isEmpty()) {
            return track.sourceUrl;
        }
        return LibraryBackupImporter.watchUrl(track.videoId);
    }

    public static String fileName(String playlistName, String extension) {
        String safe = playlistName.trim().replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_");
        return (safe.isEmpty() ? "playlist" : safe) + "." + extension;
    }

    private static String singleLine(String text) {
        return text == null ? "" : text.replace('\r', ' ').replace('\n', ' ').trim();
    }
}
