package com.example.viby.ui;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionCommand;

import com.example.viby.R;
import com.example.viby.data.Track;
import com.example.viby.playback.PlaybackService;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Owns the Media3 queue and its relationship with local playlists. */
final class PlaybackQueueController {

    static final String METADATA_VIDEO_ID = "com.example.viby.metadata.VIDEO_ID";
    static final String METADATA_SOURCE_URL = "com.example.viby.metadata.SOURCE_URL";

    private final Context context;
    private final PlayerViewModel viewModel;
    @Nullable private String loadedPlaylist;

    PlaybackQueueController(Context context, PlayerViewModel viewModel) {
        this.context = context.getApplicationContext();
        this.viewModel = viewModel;
    }

    void connect(MediaController controller) {
        if (controller.getMediaItemCount() > 0) {
            loadedPlaylist = viewModel.getQueuePlaylist();
        }
        viewModel.controller.setValue(controller);
        syncWithActivePlaylist();
    }

    void disconnect() {
        viewModel.controller.setValue(null);
        loadedPlaylist = null;
    }

    void syncWithActivePlaylist() {
        MediaController controller = viewModel.controller.getValue();
        List<Track> tracks = viewModel.tracks.getValue();
        String active = viewModel.getActivePlaylistName();
        if (controller == null || tracks == null || active == null) {
            return;
        }
        tracks = playableTracks(tracks);

        if (active.equals(loadedPlaylist)) {
            if (viewModel.isQueueCustomized()) {
                return;
            }
            int inQueue = controller.getMediaItemCount();
            List<String> queueIds = new ArrayList<>(inQueue);
            for (int i = 0; i < inQueue; i++) {
                queueIds.add(controller.getMediaItemAt(i).mediaId);
            }
            boolean prefixMatches = queuePrefixMatches(tracks, queueIds);
            if (prefixMatches && tracks.size() > inQueue) {
                List<MediaItem> tail = new ArrayList<>();
                for (int i = inQueue; i < tracks.size(); i++) {
                    tail.add(toMediaItem(tracks.get(i)));
                }
                controller.addMediaItems(tail);
                if (inQueue == 0) {
                    controller.prepare();
                }
            } else if (!prefixMatches || tracks.size() < inQueue) {
                resyncOrder(active, tracks);
            }
            return;
        }

        if (controller.getMediaItemCount() == 0 && !tracks.isEmpty()) {
            load(controller, tracks, active, 0, false);
        }
    }

    void playLocalTrack(Track track, List<Track> tracks, String playlist) {
        MediaController controller = viewModel.controller.getValue();
        if (controller == null) {
            return;
        }
        tracks = playableTracks(tracks);
        if (playlist.equals(loadedPlaylist)) {
            for (int i = 0; i < controller.getMediaItemCount(); i++) {
                if (String.valueOf(track.id).equals(
                        controller.getMediaItemAt(i).mediaId)) {
                    controller.seekTo(i, 0);
                    controller.play();
                    return;
                }
            }
        }
        load(controller, tracks, playlist,
                playablePosition(tracks, track.id), true);
    }

    void playResolvedStream(Track track, String playlist,
                            List<Track> latestTracks, String streamUrl) {
        MediaController controller = viewModel.controller.getValue();
        if (controller == null) {
            return;
        }
        List<MediaItem> items = new ArrayList<>();
        int startIndex = 0;
        for (Track candidate : latestTracks) {
            if (candidate.id == track.id) {
                items.add(isPlayableLocal(candidate)
                        ? toMediaItem(candidate)
                        : toStreamMediaItem(candidate, streamUrl));
                startIndex = items.size() - 1;
            } else if (isPlayableLocal(candidate)) {
                items.add(toMediaItem(candidate));
            }
        }
        if (items.isEmpty()) {
            return;
        }
        loadedPlaylist = playlist;
        viewModel.setQueuePlaylist(playlist);
        viewModel.setQueueCustomized(true);
        controller.setMediaItems(items, startIndex, 0L);
        controller.prepare();
        controller.play();
    }

    void addToQueue(List<Track> tracks) {
        MediaController controller = viewModel.controller.getValue();
        if (controller == null || tracks.isEmpty()) {
            return;
        }
        List<MediaItem> items = new ArrayList<>();
        for (Track track : tracks) {
            items.add(toMediaItem(track));
        }
        controller.addMediaItems(items);
        markCustomized();
        toast(R.string.added_to_queue);
    }

    @UnstableApi
    void insertAfterCurrent(List<Track> tracks) {
        MediaController controller = viewModel.controller.getValue();
        if (controller == null || tracks.isEmpty()) {
            return;
        }
        List<MediaItem> items = new ArrayList<>();
        for (Track track : tracks) {
            items.add(toMediaItem(track));
        }
        if (controller.getShuffleModeEnabled()) {
            ArrayList<Bundle> itemBundles = new ArrayList<>(items.size());
            for (MediaItem item : items) {
                itemBundles.add(item.toBundleIncludeLocalConfiguration());
            }
            Bundle args = new Bundle();
            args.putParcelableArrayList(PlaybackService.EXTRA_MEDIA_ITEMS, itemBundles);
            controller.sendCustomCommand(new SessionCommand(
                    PlaybackService.ACTION_INSERT_AFTER_CURRENT, Bundle.EMPTY), args);
        } else {
            int index = controller.getMediaItemCount() == 0
                    ? 0 : controller.getCurrentMediaItemIndex() + 1;
            controller.addMediaItems(index, items);
        }
        markCustomized();
        toast(R.string.added_to_queue);
    }

    void removeFromQueue(List<Track> tracks) {
        MediaController controller = viewModel.controller.getValue();
        if (controller == null) {
            return;
        }
        Set<String> ids = new HashSet<>();
        for (Track track : tracks) {
            ids.add(String.valueOf(track.id));
        }
        boolean removed = false;
        for (int i = controller.getMediaItemCount() - 1; i >= 0; i--) {
            if (ids.contains(controller.getMediaItemAt(i).mediaId)) {
                controller.removeMediaItem(i);
                removed = true;
            }
        }
        if (removed) {
            markCustomized();
        }
    }

    void markCustomized() {
        viewModel.setQueueCustomized(true);
    }

    void resetToActivePlaylist() {
        MediaController controller = viewModel.controller.getValue();
        List<Track> tracks = viewModel.tracks.getValue();
        String active = viewModel.getActivePlaylistName();
        if (controller == null || tracks == null || active == null) {
            return;
        }

        tracks = playableTracks(tracks);
        String currentId = controller.getCurrentMediaItem() != null
                ? controller.getCurrentMediaItem().mediaId : null;
        long currentPosition = Math.max(0L, controller.getCurrentPosition());
        boolean playWhenReady = controller.getPlayWhenReady();
        int startIndex = playablePosition(tracks, parseId(currentId));
        boolean currentStillExists = containsTrack(tracks, parseId(currentId));
        List<MediaItem> items = toMediaItems(tracks);

        loadedPlaylist = active;
        viewModel.setQueuePlaylist(active);
        viewModel.setQueueCustomized(false);
        if (items.isEmpty()) {
            controller.stop();
            controller.clearMediaItems();
        } else {
            controller.setMediaItems(items, startIndex,
                    currentStillExists ? currentPosition : 0L);
            controller.prepare();
            controller.setPlayWhenReady(playWhenReady);
        }
        controller.setShuffleModeEnabled(false);
        PlaybackService.rememberShuffleDisabled(context);
        toast(R.string.playback_queue_reset);
    }

    void clearIfLoaded(String playlist) {
        MediaController controller = viewModel.controller.getValue();
        if (!playlist.equals(loadedPlaylist) || controller == null) {
            return;
        }
        controller.stop();
        controller.clearMediaItems();
        loadedPlaylist = null;
        viewModel.setQueuePlaylist(null);
        viewModel.setQueueCustomized(false);
    }

    private void load(MediaController controller, List<Track> tracks,
                      String playlist, int startIndex, boolean play) {
        loadedPlaylist = playlist;
        viewModel.setQueuePlaylist(playlist);
        viewModel.setQueueCustomized(false);
        controller.setMediaItems(toMediaItems(tracks), startIndex, 0);
        controller.prepare();
        if (play) {
            controller.play();
        }
    }

    private void resyncOrder(String playlist, List<Track> sorted) {
        MediaController controller = viewModel.controller.getValue();
        if (controller == null || !playlist.equals(loadedPlaylist)
                || viewModel.isQueueCustomized()) {
            return;
        }
        String currentId = controller.getCurrentMediaItem() != null
                ? controller.getCurrentMediaItem().mediaId : null;
        long position = controller.getCurrentPosition();
        boolean playWhenReady = controller.getPlayWhenReady();
        int startIndex = playablePosition(sorted, parseId(currentId));

        controller.setMediaItems(toMediaItems(sorted), startIndex,
                currentId != null ? position : 0);
        controller.prepare();
        controller.setPlayWhenReady(playWhenReady);
    }

    static MediaItem toMediaItem(Track track) {
        return new MediaItem.Builder()
                .setUri(Uri.fromFile(new File(track.filePath)))
                .setMediaId(String.valueOf(track.id))
                .setMediaMetadata(toMediaMetadata(track))
                .build();
    }

    static boolean isPlayableLocal(Track track) {
        return track.downloaded && track.filePath != null && !track.filePath.isEmpty()
                && new File(track.filePath).isFile();
    }

    static int playablePosition(List<Track> tracks, long trackId) {
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i).id == trackId) {
                return i;
            }
        }
        return 0;
    }

    static boolean queuePrefixMatches(List<Track> tracks, List<String> queueIds) {
        if (queueIds.size() > tracks.size()) {
            return false;
        }
        for (int i = 0; i < queueIds.size(); i++) {
            if (!String.valueOf(tracks.get(i).id).equals(queueIds.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static MediaItem toStreamMediaItem(Track track, String streamUrl) {
        return new MediaItem.Builder()
                .setUri(Uri.parse(streamUrl))
                .setMediaId(String.valueOf(track.id))
                .setMediaMetadata(toMediaMetadata(track))
                .build();
    }

    private static MediaMetadata toMediaMetadata(Track track) {
        Bundle extras = new Bundle();
        if (track.videoId != null && !track.videoId.isEmpty()) {
            extras.putString(METADATA_VIDEO_ID, track.videoId);
        }
        if (track.sourceUrl != null && !track.sourceUrl.isEmpty()) {
            extras.putString(METADATA_SOURCE_URL, track.sourceUrl);
        }
        return new MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.uploader)
                .setArtworkUri(track.thumbnailUrl != null
                        ? Uri.parse(track.thumbnailUrl) : null)
                .setDurationMs(track.durationMs)
                .setExtras(extras)
                .build();
    }

    private static List<MediaItem> toMediaItems(List<Track> tracks) {
        List<MediaItem> items = new ArrayList<>(tracks.size());
        for (Track track : tracks) {
            items.add(toMediaItem(track));
        }
        return items;
    }

    private static List<Track> playableTracks(List<Track> tracks) {
        List<Track> playable = new ArrayList<>();
        for (Track track : tracks) {
            if (isPlayableLocal(track)) {
                playable.add(track);
            }
        }
        return playable;
    }

    private static boolean containsTrack(List<Track> tracks, long trackId) {
        for (Track track : tracks) {
            if (track.id == trackId) {
                return true;
            }
        }
        return false;
    }

    private static long parseId(@Nullable String mediaId) {
        if (mediaId == null) {
            return Long.MIN_VALUE;
        }
        try {
            return Long.parseLong(mediaId);
        } catch (NumberFormatException ignored) {
            return Long.MIN_VALUE;
        }
    }

    private void toast(int message) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }
}
