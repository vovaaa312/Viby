package com.example.viby.ui;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.example.viby.R;
import com.example.viby.data.Track;
import com.example.viby.data.TrackDao;
import com.example.viby.data.VibyDatabase;
import com.example.viby.download.DownloadService;
import com.example.viby.util.YoutubeUrlParser;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Handles deliberate duplicate additions without downloading the same media twice. */
final class DuplicateTrackPrompt {

    private DuplicateTrackPrompt() {
    }

    static void enqueue(Activity activity, String url, @Nullable String playlist,
                        boolean isPlaylist, @Nullable Runnable onAccepted) {
        String targetPlaylist = playlist == null || playlist.trim().isEmpty()
                ? activity.getString(R.string.default_playlist) : playlist.trim();
        String videoId = isPlaylist ? null : YoutubeUrlParser.videoId(url);
        if (isPlaylist || videoId == null) {
            enqueueNormally(activity, url, playlist, isPlaylist, onAccepted);
            return;
        }

        Context app = activity.getApplicationContext();
        VibyDatabase.dbExecutor.execute(() -> {
            Track existing = VibyDatabase.get(app).trackDao()
                    .getDownloadedByVideoIdSync(targetPlaylist, videoId);
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!isUsable(activity)) {
                    return;
                }
                if (existing == null) {
                    enqueueNormally(activity, url, playlist, false, onAccepted);
                } else {
                    showConfirmation(activity, existing, targetPlaylist, videoId,
                            url, onAccepted);
                }
            });
        });
    }

    private static void showConfirmation(Activity activity, Track existing,
                                         String playlist, String videoId, String url,
                                         @Nullable Runnable onAccepted) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.duplicate_track_title)
                .setMessage(activity.getString(R.string.duplicate_track_message,
                        existing.title, playlist))
                .setPositiveButton(R.string.duplicate_track_add, (dialog, which) ->
                        addDuplicate(activity, playlist, videoId, url, onAccepted))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void addDuplicate(Activity activity, String playlist, String videoId,
                                     String url, @Nullable Runnable onAccepted) {
        Context app = activity.getApplicationContext();
        VibyDatabase.dbExecutor.execute(() -> {
            TrackDao dao = VibyDatabase.get(app).trackDao();
            Track source = dao.getDownloadedByVideoIdSync(playlist, videoId);
            if (source == null || source.filePath == null || source.filePath.isEmpty()) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (isUsable(activity)) {
                        enqueueNormally(activity, url, playlist, false, onAccepted);
                    }
                });
                return;
            }

            Track duplicate = new Track();
            duplicate.videoId = source.videoId;
            duplicate.title = source.title;
            duplicate.uploader = source.uploader;
            duplicate.durationMs = source.durationMs;
            duplicate.filePath = source.filePath;
            duplicate.playlistName = playlist;
            duplicate.thumbnailUrl = source.thumbnailUrl;
            duplicate.position = dao.nextPosition(playlist);
            duplicate.youtubePosition = null;
            duplicate.createdAt = System.currentTimeMillis();
            duplicate.downloaded = true;
            duplicate.sourceUrl = source.sourceUrl != null ? source.sourceUrl : url;
            dao.insert(duplicate);

            new Handler(Looper.getMainLooper()).post(() -> {
                if (!isUsable(activity)) {
                    return;
                }
                Toast.makeText(activity,
                        activity.getString(R.string.duplicate_track_added, playlist),
                        Toast.LENGTH_SHORT).show();
                if (onAccepted != null) {
                    onAccepted.run();
                }
            });
        });
    }

    private static void enqueueNormally(Activity activity, String url,
                                        @Nullable String playlist, boolean isPlaylist,
                                        @Nullable Runnable onAccepted) {
        DownloadService.enqueue(activity, url, playlist, isPlaylist);
        Toast.makeText(activity, R.string.download_queued, Toast.LENGTH_SHORT).show();
        if (onAccepted != null) {
            onAccepted.run();
        }
    }

    private static boolean isUsable(Activity activity) {
        return !activity.isFinishing() && !activity.isDestroyed();
    }
}
