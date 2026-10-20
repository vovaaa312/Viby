package com.example.viby.ui;

import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.example.viby.R;
import com.example.viby.data.PlaylistSource;
import com.example.viby.data.Track;
import com.example.viby.data.VibyDatabase;
import com.example.viby.download.DownloadService;
import com.example.viby.sync.YoutubePlaylistSync;
import com.example.viby.util.YoutubeUrlParser;
import com.google.android.gms.common.api.ApiException;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Owns YouTube playlist authorization, synchronization and its UI prompts. */
final class YoutubeSyncController {

    private static final String TAG = "YoutubeSyncController";

    interface LocalCopy {
        void run(@Nullable Runnable afterCopy);
    }

    static final class ActionState {
        final boolean showSaveRadio;
        final boolean showSyncMissing;

        ActionState(boolean showSaveRadio, boolean showSyncMissing) {
            this.showSaveRadio = showSaveRadio;
            this.showSyncMissing = showSyncMissing;
        }
    }

    private final MainActivity activity;
    private final YoutubeAuthorizationController authorization;
    private final YoutubePlaylistSync synchronization;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    YoutubeSyncController(MainActivity activity) {
        this.activity = activity;
        authorization = new YoutubeAuthorizationController(activity);
        synchronization = new YoutubePlaylistSync(activity);
    }

    void updateActions(Menu menu, @Nullable PlaylistSource source,
                       @Nullable List<Track> tracks) {
        ActionState state = actionState(source, tracks);
        MenuItem saveRadio = menu.findItem(R.id.action_save_radio);
        if (saveRadio != null) {
            saveRadio.setVisible(state.showSaveRadio);
        }
        MenuItem syncMissing = menu.findItem(R.id.action_sync_missing_youtube);
        if (syncMissing != null) {
            syncMissing.setVisible(state.showSyncMissing);
        }
    }

    static ActionState actionState(@Nullable PlaylistSource source,
                                   @Nullable List<Track> tracks) {
        boolean unsavedRadio = source != null && isUnsavedRadio(source);
        boolean missing = source != null && (source.youtubeSyncEnabled
                ? YoutubePlaylistSync.hasMissingYoutubeTracks(tracks)
                : YoutubePlaylistSync.hasLocallyAddedYoutubeTracks(tracks));
        return new ActionState(unsavedRadio,
                !unsavedRadio
                        && YoutubePlaylistSync.hasOriginalYoutubePlaylist(source)
                        && missing);
    }

    void showPlaylistDialog(@Nullable String playlist) {
        if (playlist == null) {
            return;
        }
        VibyDatabase.dbExecutor.execute(() -> {
            PlaylistSource source = VibyDatabase.get(activity)
                    .playlistSourceDao().getSync(playlist);
            activity.runOnUiThread(() -> {
                if (!isAlive()) {
                    return;
                }
                if (source == null) {
                    toast(R.string.refresh_no_source, Toast.LENGTH_LONG);
                } else if (source.youtubeSyncEnabled) {
                    new MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.youtube_sync_title)
                            .setMessage(activity.getString(
                                    R.string.youtube_sync_enabled_message, playlist))
                            .setPositiveButton(R.string.youtube_sync_disable,
                                    (dialog, which) -> disable(playlist))
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                } else if (isUnsavedRadio(source)) {
                    new MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.track_radio_save_title)
                            .setMessage(R.string.track_radio_save_message)
                            .setPositiveButton(R.string.track_radio_save,
                                    (dialog, which) -> saveRadio(playlist))
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                } else {
                    new MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.youtube_sync_title)
                            .setMessage(activity.getString(
                                    R.string.youtube_sync_enable_message, playlist))
                            .setPositiveButton(R.string.youtube_sync_enable,
                                    (dialog, which) -> enable(playlist))
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                }
            });
        });
    }

    void syncMissing(@Nullable String playlist) {
        if (playlist == null) {
            return;
        }
        authorize(accessToken -> executor.execute(() -> {
            try {
                PlaylistSource source = VibyDatabase.get(activity)
                        .playlistSourceDao().getSync(playlist);
                if (source != null && !source.youtubeSyncEnabled) {
                    synchronization.enable(playlist, accessToken);
                }
                showMissingResult(synchronization.syncMissingTracks(
                        playlist, accessToken));
            } catch (Exception error) {
                showError(error);
            }
        }), R.string.youtube_sync_missing);
    }

    void addTrackByUrl(String url, String playlist) {
        VibyDatabase.dbExecutor.execute(() -> {
            PlaylistSource source = VibyDatabase.get(activity)
                    .playlistSourceDao().getSync(playlist);
            activity.runOnUiThread(() -> {
                if (!isAlive()) {
                    return;
                }
                if (source == null || !source.youtubeSyncEnabled) {
                    DuplicateTrackPrompt.enqueue(activity, url, playlist,
                            false, null);
                    return;
                }
                String[] choices = {
                        activity.getString(R.string.youtube_sync_add_both),
                        activity.getString(R.string.youtube_sync_local_only),
                };
                new MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.youtube_sync_add_title)
                        .setItems(choices, (dialog, which) -> {
                            if (which == 0) {
                                checkDuplicateThenAdd(url, playlist);
                            } else {
                                DuplicateTrackPrompt.enqueue(activity, url,
                                        playlist, false, null);
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        });
    }

    void offerTrackCopy(List<Track> tracks, String target, LocalCopy localCopy) {
        VibyDatabase.dbExecutor.execute(() -> {
            PlaylistSource source = VibyDatabase.get(activity)
                    .playlistSourceDao().getSync(target);
            ArrayList<String> videoIds = new ArrayList<>();
            for (Track track : tracks) {
                if (!target.equals(track.playlistName)
                        && track.videoId != null && !track.videoId.isEmpty()) {
                    videoIds.add(track.videoId);
                }
            }
            activity.runOnUiThread(() -> {
                if (!isAlive()) {
                    return;
                }
                if (!YoutubePlaylistSync.hasOriginalYoutubePlaylist(source)
                        || videoIds.isEmpty()) {
                    localCopy.run(null);
                    return;
                }
                String[] choices = {
                        activity.getString(R.string.youtube_sync_add_both),
                        activity.getString(R.string.youtube_sync_local_only),
                };
                new MaterialAlertDialogBuilder(activity)
                        .setTitle(activity.getString(
                                R.string.youtube_sync_copy_title, target))
                        .setItems(choices, (dialog, which) -> {
                            if (which == 0) {
                                localCopy.run(() -> syncCopiedTracks(target,
                                        videoIds, !source.youtubeSyncEnabled));
                            } else {
                                localCopy.run(null);
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        });
    }

    void deleteTracksWithChoice(List<Track> tracks, boolean deleteFiles,
                                Runnable localDelete,
                                Consumer<List<Track>> onRemoteRemoved) {
        if (tracks.isEmpty()) {
            return;
        }
        String playlist = tracks.get(0).playlistName;
        VibyDatabase.dbExecutor.execute(() -> {
            PlaylistSource source = VibyDatabase.get(activity)
                    .playlistSourceDao().getSync(playlist);
            activity.runOnUiThread(() -> {
                if (!isAlive()) {
                    return;
                }
                if (source == null || !source.youtubeSyncEnabled) {
                    localDelete.run();
                    return;
                }
                String[] choices = {
                        activity.getString(R.string.youtube_sync_delete_both),
                        activity.getString(R.string.youtube_sync_local_only),
                };
                new MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.youtube_sync_delete_title)
                        .setItems(choices, (dialog, which) -> {
                            if (which == 0) {
                                deleteFromYoutube(playlist, tracks, deleteFiles,
                                        onRemoteRemoved);
                            } else {
                                localDelete.run();
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        });
    }

    void destroy() {
        authorization.destroy();
        executor.shutdownNow();
    }

    private static boolean isUnsavedRadio(PlaylistSource source) {
        return YoutubeUrlParser.isRadioMixUrl(source.sourceUrl)
                && (source.youtubePlaylistId == null
                || YoutubeUrlParser.isRadioPlaylistId(source.youtubePlaylistId));
    }

    private void saveRadio(String playlist) {
        authorize(accessToken -> executor.execute(() -> {
            try {
                YoutubePlaylistSync.SaveRadioResult result = synchronization
                        .saveRadioToYoutube(playlist, accessToken);
                activity.runOnUiThread(() -> {
                    if (result.failedCount == 0) {
                        toast(activity.getString(R.string.track_radio_saved,
                                result.remoteTitle, result.addedCount), Toast.LENGTH_LONG);
                    } else {
                        toast(activity.getString(R.string.track_radio_saved_partial,
                                result.remoteTitle, result.addedCount,
                                result.failedCount, errorText(result.firstError)),
                                Toast.LENGTH_LONG);
                    }
                });
            } catch (Exception error) {
                showError(error);
            }
        }), R.string.track_radio_saving);
    }

    private void enable(String playlist) {
        authorize(accessToken -> executor.execute(() -> {
            try {
                YoutubePlaylistSync.EnableResult result =
                        synchronization.enable(playlist, accessToken);
                activity.runOnUiThread(() -> toast(activity.getString(
                        R.string.youtube_sync_enabled, result.remoteTitle,
                        result.linkedTracks), Toast.LENGTH_LONG));
            } catch (Exception error) {
                showError(error);
            }
        }), R.string.youtube_sync_enabling);
    }

    private void disable(String playlist) {
        VibyDatabase.dbExecutor.execute(() -> {
            synchronization.disable(playlist);
            activity.runOnUiThread(() -> toast(
                    R.string.youtube_sync_disabled, Toast.LENGTH_SHORT));
        });
    }

    private void checkDuplicateThenAdd(String url, String playlist) {
        String videoId = YoutubeUrlParser.videoId(url);
        if (videoId == null) {
            toast(R.string.error_invalid_url, Toast.LENGTH_SHORT);
            return;
        }
        VibyDatabase.dbExecutor.execute(() -> {
            Track duplicate = VibyDatabase.get(activity).trackDao()
                    .getDownloadedByVideoIdSync(playlist, videoId);
            activity.runOnUiThread(() -> {
                if (!isAlive()) {
                    return;
                }
                if (duplicate == null) {
                    addTrack(url, playlist);
                } else {
                    new MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.duplicate_track_title)
                            .setMessage(activity.getString(
                                    R.string.duplicate_track_message,
                                    duplicate.title, playlist))
                            .setPositiveButton(R.string.duplicate_track_add,
                                    (dialog, which) -> addTrack(url, playlist))
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                }
            });
        });
    }

    private void addTrack(String url, String playlist) {
        authorize(accessToken -> executor.execute(() -> {
            try {
                YoutubePlaylistSync.AddResult result = synchronization
                        .addTrackAtStart(playlist, url, accessToken);
                activity.runOnUiThread(() -> {
                    if (result.downloadRequired) {
                        DownloadService.enqueue(activity, url, playlist, false);
                    }
                    toast(R.string.youtube_sync_added, Toast.LENGTH_LONG);
                });
            } catch (Exception error) {
                showError(error);
            }
        }), R.string.youtube_sync_adding);
    }

    private void syncCopiedTracks(String playlist, List<String> videoIds,
                                  boolean enableFirst) {
        authorize(accessToken -> executor.execute(() -> {
            try {
                if (enableFirst) {
                    synchronization.enable(playlist, accessToken);
                }
                showMissingResult(synchronization.syncMissingTracks(
                        playlist, videoIds, accessToken));
            } catch (Exception error) {
                showError(error);
            }
        }), R.string.youtube_sync_missing);
    }

    private void deleteFromYoutube(String playlist, List<Track> tracks,
                                   boolean deleteFiles,
                                   Consumer<List<Track>> onRemoteRemoved) {
        authorize(accessToken -> executor.execute(() -> {
            try {
                YoutubePlaylistSync.DeleteResult result = synchronization
                        .deleteTracks(playlist, tracks, deleteFiles, accessToken);
                activity.runOnUiThread(() -> {
                    onRemoteRemoved.accept(result.removed);
                    if (result.failedCount == 0) {
                        toast(activity.getString(R.string.tracks_deleted,
                                result.removed.size()), Toast.LENGTH_SHORT);
                    } else {
                        toast(activity.getString(R.string.youtube_sync_delete_partial,
                                result.removed.size(), result.failedCount,
                                errorText(result.firstError)), Toast.LENGTH_LONG);
                    }
                });
            } catch (Exception error) {
                showError(error);
            }
        }), R.string.youtube_sync_deleting);
    }

    private void authorize(Consumer<String> operation, int progressMessage) {
        toast(R.string.youtube_sync_authorizing, Toast.LENGTH_SHORT);
        authorization.request(accessToken -> {
            toast(progressMessage, Toast.LENGTH_LONG);
            operation.accept(accessToken);
        }, this::showError);
    }

    private void showMissingResult(YoutubePlaylistSync.MissingTracksResult result) {
        activity.runOnUiThread(() -> {
            if (result.failedCount == 0) {
                toast(activity.getString(R.string.youtube_sync_missing_added,
                        result.addedCount), Toast.LENGTH_LONG);
            } else {
                toast(activity.getString(R.string.youtube_sync_missing_partial,
                        result.addedCount, result.failedCount,
                        errorText(result.firstError)), Toast.LENGTH_LONG);
            }
        });
    }

    private void showError(Exception error) {
        Log.w(TAG, "YouTube synchronization failed", error);
        activity.runOnUiThread(() -> {
            if (!isAlive()) {
                return;
            }
            String message;
            if (error instanceof ApiException
                    && ((ApiException) error).getStatusCode() == 10) {
                message = activity.getString(
                        R.string.youtube_sync_oauth_not_configured);
            } else if (error instanceof ApiException) {
                ApiException apiError = (ApiException) error;
                message = "Google OAuth " + apiError.getStatusCode();
                if (apiError.getStatusMessage() != null
                        && !apiError.getStatusMessage().isEmpty()) {
                    message += ": " + apiError.getStatusMessage();
                }
            } else {
                message = error.getMessage();
            }
            if (message == null || message.trim().isEmpty()) {
                message = error.getClass().getSimpleName();
            }
            toast(activity.getString(R.string.youtube_sync_failed, message),
                    Toast.LENGTH_LONG);
        });
    }

    private static String errorText(@Nullable String error) {
        return error != null ? error : "YouTube API";
    }

    private void toast(int message, int duration) {
        Toast.makeText(activity, message, duration).show();
    }

    private void toast(String message, int duration) {
        Toast.makeText(activity, message, duration).show();
    }

    private boolean isAlive() {
        return !activity.isFinishing() && !activity.isDestroyed();
    }
}
