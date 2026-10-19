package com.example.viby.data;

import androidx.annotation.Nullable;

import java.util.List;

/** Copies playlist membership while keeping the original row and shared media file intact. */
public final class PlaylistTrackCopier {

    interface Store {
        @Nullable
        Track findByVideoId(String playlist, String videoId);

        @Nullable
        Track findByFilePath(String playlist, String filePath);

        int nextPosition(String playlist);

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
            public int nextPosition(String playlist) {
                return dao.nextPosition(playlist);
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

    public int copy(List<Track> sources, String targetPlaylist) {
        int copied = 0;
        for (Track source : sources) {
            if (targetPlaylist.equals(source.playlistName) || alreadyExists(source,
                    targetPlaylist)) {
                continue;
            }
            store.insert(copyOf(source, targetPlaylist,
                    store.nextPosition(targetPlaylist)));
            copied++;
        }
        return copied;
    }

    private boolean alreadyExists(Track source, String targetPlaylist) {
        if (source.videoId != null && !source.videoId.isEmpty()) {
            return store.findByVideoId(targetPlaylist, source.videoId) != null;
        }
        return source.filePath != null && !source.filePath.isEmpty()
                && store.findByFilePath(targetPlaylist, source.filePath) != null;
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
        return copy;
    }
}
