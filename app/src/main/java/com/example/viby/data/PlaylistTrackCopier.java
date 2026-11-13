package com.example.viby.data;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Copies playlist membership while keeping the original row and shared media file intact. */
public final class PlaylistTrackCopier {

    interface Store {
        @Nullable
        Track findByVideoId(String playlist, String videoId);

        @Nullable
        Track findByFilePath(String playlist, String filePath);

        /** Moves every row of the playlist {@code count} positions down. */
        void shiftPositions(String playlist, int count);

        void insert(Track track);
    }

    private final Store store;

    public static PlaylistTrackCopier create(TrackDao dao) {
        return new PlaylistTrackCopier(new Store() {
            @Override
            public Track findByVideoId(String playlist, String videoId) {
                return dao.getByVideoIdSync(playlist, videoId);
            }

            @Override
            public Track findByFilePath(String playlist, String filePath) {
                return dao.getByFilePathSync(playlist, filePath);
            }

            @Override
            public void shiftPositions(String playlist, int count) {
                dao.shiftPositions(playlist, count);
            }

            @Override
            public void insert(Track track) {
                dao.insert(track);
            }
        });
    }

    PlaylistTrackCopier(Store store) {
        this.store = store;
    }

    /**
     * Adds the tracks to the top of the target playlist, keeping their selected order.
     * Callers should run this inside a database transaction.
     */
    public int copy(List<Track> sources, String targetPlaylist) {
        List<Track> accepted = new ArrayList<>();
        Set<String> batch = new HashSet<>();
        for (Track source : sources) {
            if (targetPlaylist.equals(source.playlistName) || alreadyExists(source,
                    targetPlaylist)) {
                continue;
            }
            String identity = identity(source);
            if (identity != null && !batch.add(identity)) {
                continue; // the same song selected twice
            }
            accepted.add(source);
        }
        if (accepted.isEmpty()) {
            return 0;
        }
        store.shiftPositions(targetPlaylist, accepted.size());
        for (int i = 0; i < accepted.size(); i++) {
            store.insert(copyOf(accepted.get(i), targetPlaylist, i));
        }
        return accepted.size();
    }

    private boolean alreadyExists(Track source, String targetPlaylist) {
        if (source.videoId != null && !source.videoId.isEmpty()) {
            return store.findByVideoId(targetPlaylist, source.videoId) != null;
        }
        return source.filePath != null && !source.filePath.isEmpty()
                && store.findByFilePath(targetPlaylist, source.filePath) != null;
    }

    @Nullable
    private static String identity(Track source) {
        if (source.videoId != null && !source.videoId.isEmpty()) {
            return "v:" + source.videoId;
        }
        if (source.filePath != null && !source.filePath.isEmpty()) {
            return "f:" + source.filePath;
        }
        return null;
    }

    private static Track copyOf(Track source, String targetPlaylist, int position) {
        Track copy = new Track();
        copy.videoId = source.videoId;
        copy.title = source.title;
        copy.uploader = source.uploader;
        copy.durationMs = source.durationMs;
        copy.filePath = source.filePath;
        copy.playlistName = targetPlaylist;
        copy.thumbnailUrl = source.thumbnailUrl;
        copy.position = position;
        copy.youtubePosition = null;
        copy.youtubePlaylistItemId = null;
        copy.createdAt = System.currentTimeMillis();
        copy.downloaded = source.downloaded;
        copy.sourceUrl = source.sourceUrl;
        copy.downloadState = source.downloadState;
        copy.downloadErrorCode = source.downloadErrorCode;
        copy.downloadErrorMessage = source.downloadErrorMessage;
        copy.downloadAttemptCount = source.downloadAttemptCount;
        copy.downloadLastAttemptAt = source.downloadLastAttemptAt;
        copy.downloadFormat = source.downloadFormat;
        copy.downloadRequiresAuthorization = source.downloadRequiresAuthorization;
        copy.downloadProgress = source.downloadProgress;
        // the song keeps its library identity: album, favorite and listening history
        copy.album = source.album;
        copy.favorite = source.favorite;
        copy.playCount = source.playCount;
        copy.lastPlayedAt = source.lastPlayedAt;
        copy.metadataScanned = source.metadataScanned;
        copy.loudnessLufs = source.loudnessLufs;
        copy.loudnessPeak = source.loudnessPeak;
        copy.loudnessScanned = source.loudnessScanned;
        copy.addedInViby = true; // keeps its top slot when the YouTube order is re-applied
        return copy;
    }
}
