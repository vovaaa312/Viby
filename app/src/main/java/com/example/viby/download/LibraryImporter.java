package com.example.viby.download;

import androidx.annotation.Nullable;

import com.example.viby.data.Track;
import com.example.viby.data.TrackDao;

import java.io.File;
import java.util.List;

/** Adds completed downloads to Room and reuses existing media files across playlists. */
final class LibraryImporter {

    interface Store {
        List<Track> downloadedByVideoId(String videoId);

        @Nullable
        Track find(String playlist, String videoId);

        int nextPosition(String playlist);

        void insert(Track track);

        void update(Track track);
    }

    private final Store store;

    static LibraryImporter create(TrackDao dao) {
        return new LibraryImporter(new Store() {
            @Override
            public List<Track> downloadedByVideoId(String videoId) {
                return dao.getDownloadedByVideoIdGloballySync(videoId);
            }

            @Override
            public Track find(String playlist, String videoId) {
                return dao.getByVideoIdSync(playlist, videoId);
            }

            @Override
            public int nextPosition(String playlist) {
                return dao.nextPosition(playlist);
            }

            @Override
            public void insert(Track track) {
                dao.insert(track);
            }

            @Override
            public void update(Track track) {
                dao.update(track);
            }
        });
    }

    LibraryImporter(Store store) {
        this.store = store;
    }

    boolean reuseDownloadedTrack(@Nullable String videoId, String title,
                                 @Nullable String uploader, long durationMs,
                                 String playlist, @Nullable Integer youtubePosition,
                                 String sourceUrl) {
        if (videoId == null || videoId.isEmpty()) {
            return false;
        }
        Track reusable = null;
        for (Track candidate : store.downloadedByVideoId(videoId)) {
            if (candidate.filePath != null && !candidate.filePath.isEmpty()
                    && new File(candidate.filePath).isFile()) {
                reusable = candidate;
                break;
            }
        }
        if (reusable == null) {
            return false;
        }

        Track target = store.find(playlist, videoId);
        boolean updateExisting = target != null;
        if (target == null) {
            target = newTrack(playlist);
        }
        target.videoId = videoId;
        target.title = title != null && !title.isEmpty() ? title : reusable.title;
        target.uploader = uploader != null && !uploader.isEmpty()
                ? uploader : reusable.uploader;
        target.durationMs = durationMs > 0 ? durationMs : reusable.durationMs;
        target.filePath = reusable.filePath;
        target.playlistName = playlist;
        target.thumbnailUrl = reusable.thumbnailUrl != null
                ? reusable.thumbnailUrl : thumbnailUrl(videoId);
        target.youtubePosition = youtubePosition;
        target.downloaded = true;
        target.sourceUrl = sourceUrl;
        markDownloaded(target);
        save(target, updateExisting);
        return true;
    }

    void importDownloaded(@Nullable String videoId, String title,
                          @Nullable String uploader,
                          long durationMs, String playlist,
                          @Nullable Integer youtubePosition, String sourceUrl,
                          File mediaFile, int attemptCount, long lastAttemptAt,
                          @Nullable String format) {
        Track target = videoId != null ? store.find(playlist, videoId) : null;
        boolean updateExisting = target != null;
        if (target == null) {
            target = newTrack(playlist);
        }
        target.videoId = videoId;
        target.title = title;
        target.uploader = uploader;
        target.durationMs = durationMs;
        target.filePath = mediaFile.getAbsolutePath();
        target.playlistName = playlist;
        target.thumbnailUrl = thumbnailUrl(videoId);
        target.youtubePosition = youtubePosition;
        target.downloaded = true;
        target.sourceUrl = sourceUrl;
        target.downloadAttemptCount = attemptCount;
        target.downloadLastAttemptAt = lastAttemptAt;
        target.downloadFormat = format;
        markDownloaded(target);
        save(target, updateExisting);
    }

    private Track newTrack(String playlist) {
        Track created = new Track();
        created.position = store.nextPosition(playlist);
        created.createdAt = System.currentTimeMillis();
        return created;
    }

    private void save(Track track, boolean updateExisting) {
        if (updateExisting) {
            store.update(track);
        } else {
            store.insert(track);
        }
    }

    private static void markDownloaded(Track track) {
        track.downloadState = Track.DOWNLOAD_DOWNLOADED;
        track.downloadErrorCode = null;
        track.downloadErrorMessage = null;
        track.downloadRequiresAuthorization = false;
        track.downloadProgress = 100;
    }

    @Nullable
    private static String thumbnailUrl(@Nullable String videoId) {
        return videoId != null && !videoId.isEmpty()
                ? "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg" : null;
    }
}
