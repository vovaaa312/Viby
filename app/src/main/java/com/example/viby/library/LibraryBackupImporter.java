package com.example.viby.library;

import androidx.annotation.Nullable;

import com.example.viby.data.PlaylistSource;
import com.example.viby.data.Track;
import com.example.viby.data.TrackDao;
import com.example.viby.data.PlaylistSourceDao;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Merges a backup into the current library without duplicating or deleting anything. */
public final class LibraryBackupImporter {

    public static final String ERROR_FILE_MISSING = "FILE_MISSING";

    public interface Store {
        @Nullable
        Track findByVideoId(String playlist, String videoId);

        @Nullable
        Track findByFile(String playlist, String filePath);

        int nextPosition(String playlist);

        void insert(Track track);

        void update(Track track);

        @Nullable
        PlaylistSource source(String playlist);

        void upsertSource(PlaylistSource source);
    }

    public static final class Result {
        public int added;
        public int merged;
        public int playlistsRestored;
        /** Rows whose audio file is not on this device yet. */
        public int missingFiles;
    }

    private final Store store;

    public static LibraryBackupImporter create(TrackDao trackDao, PlaylistSourceDao sourceDao) {
        return new LibraryBackupImporter(new Store() {
            @Override
            public Track findByVideoId(String playlist, String videoId) {
                return trackDao.getByVideoIdSync(playlist, videoId);
            }

            @Override
            public Track findByFile(String playlist, String filePath) {
                return trackDao.getByFilePathSync(playlist, filePath);
            }

            @Override
            public int nextPosition(String playlist) {
                return trackDao.nextPosition(playlist);
            }

            @Override
            public void insert(Track track) {
                trackDao.insert(track);
            }

            @Override
            public void update(Track track) {
                trackDao.update(track);
            }

            @Override
            public PlaylistSource source(String playlist) {
                return sourceDao.getSync(playlist);
            }

            @Override
            public void upsertSource(PlaylistSource source) {
                sourceDao.upsert(source);
            }
        });
    }

    public LibraryBackupImporter(Store store) {
        this.store = store;
    }

    /**
     * @param missingMessage localized text shown for local-only tracks whose file is gone
     */
    public Result importSnapshot(LibraryBackup.Snapshot snapshot, File musicRoot,
                                 String missingMessage, long now) {
        Result result = new Result();
        for (LibraryBackup.PlaylistEntry entry : snapshot.playlists) {
            if (entry.sourceUrl == null || entry.sourceUrl.isEmpty()
                    || store.source(entry.name) != null) {
                continue;
            }
            PlaylistSource source = new PlaylistSource();
            source.playlistName = entry.name;
            source.sourceUrl = entry.sourceUrl;
            source.updatedAt = entry.updatedAt > 0 ? entry.updatedAt : now;
            source.youtubePlaylistId = entry.youtubePlaylistId;
            // Remote writes need a fresh, explicit opt-in on this device and account.
            source.youtubeSyncEnabled = false;
            source.youtubeOwnerChannelId = null;
            store.upsertSource(source);
            result.playlistsRestored++;
        }

        List<LibraryBackup.Entry> ordered = new ArrayList<>(snapshot.tracks);
        Collections.sort(ordered, (a, b) -> {
            int byPlaylist = a.playlist.compareTo(b.playlist);
            return byPlaylist != 0 ? byPlaylist : Integer.compare(a.position, b.position);
        });
        for (LibraryBackup.Entry entry : ordered) {
            String filePath = entry.relativePath != null
                    ? LibraryPaths.resolve(musicRoot, entry.relativePath).getAbsolutePath()
                    : "";
            boolean fileExists = !filePath.isEmpty() && new File(filePath).isFile();
            Track existing = findExisting(entry, filePath);
            if (existing != null) {
                merge(existing, entry, filePath, fileExists);
                store.update(existing);
                result.merged++;
                continue;
            }
            Track track = toTrack(entry, filePath, fileExists, missingMessage, now);
            track.position = store.nextPosition(entry.playlist);
            store.insert(track);
            result.added++;
            if (!track.downloaded) {
                result.missingFiles++;
            }
        }
        return result;
    }

    @Nullable
    private Track findExisting(LibraryBackup.Entry entry, String filePath) {
        if (entry.videoId != null && !entry.videoId.isEmpty()) {
            Track byVideo = store.findByVideoId(entry.playlist, entry.videoId);
            if (byVideo != null) {
                return byVideo;
            }
        }
        return filePath.isEmpty() ? null : store.findByFile(entry.playlist, filePath);
    }

    private static void merge(Track existing, LibraryBackup.Entry entry, String filePath,
                              boolean fileExists) {
        existing.favorite = existing.favorite || entry.favorite;
        existing.playCount = Math.max(existing.playCount, entry.playCount);
        existing.lastPlayedAt = Math.max(existing.lastPlayedAt, entry.lastPlayedAt);
        if (existing.album == null && entry.album != null) {
            existing.album = entry.album;
        }
        if (existing.sourceUrl == null && entry.sourceUrl != null) {
            existing.sourceUrl = entry.sourceUrl;
        }
        boolean existingPlayable = existing.downloaded && existing.filePath != null
                && !existing.filePath.isEmpty() && new File(existing.filePath).isFile();
        if (!existingPlayable && fileExists) {
            existing.filePath = filePath;
            existing.downloaded = true;
            existing.downloadState = Track.DOWNLOAD_DOWNLOADED;
            existing.downloadErrorCode = null;
            existing.downloadErrorMessage = null;
            existing.downloadProgress = 100;
        }
    }

    static Track toTrack(LibraryBackup.Entry entry, String filePath, boolean fileExists,
                         String missingMessage, long now) {
        Track track = new Track();
        track.playlistName = entry.playlist;
        track.title = entry.title;
        track.uploader = entry.artist;
        track.album = entry.album;
        track.durationMs = Math.max(0L, entry.durationMs);
        track.videoId = entry.videoId;
        track.sourceUrl = entry.sourceUrl != null ? entry.sourceUrl
                : watchUrl(entry.videoId);
        track.thumbnailUrl = entry.thumbnailUrl;
        track.youtubePosition = entry.youtubePosition;
        track.youtubePlaylistItemId = entry.youtubePlaylistItemId;
        track.createdAt = entry.createdAt > 0 ? entry.createdAt : now;
        track.favorite = entry.favorite;
        track.playCount = entry.playCount;
        track.lastPlayedAt = entry.lastPlayedAt;
        // Keep the expected path even when missing: a mirror restore can fill it later.
        track.filePath = filePath;
        if (fileExists) {
            track.downloaded = true;
            track.downloadState = Track.DOWNLOAD_DOWNLOADED;
            track.downloadProgress = 100;
        } else if (track.sourceUrl != null) {
            track.downloaded = false;
            track.downloadState = Track.DOWNLOAD_WAITING;
        } else {
            track.downloaded = false;
            track.downloadState = Track.DOWNLOAD_FAILED_PERMANENT;
            track.downloadErrorCode = ERROR_FILE_MISSING;
            track.downloadErrorMessage = missingMessage;
        }
        return track;
    }

    @Nullable
    public static String watchUrl(@Nullable String videoId) {
        return videoId != null && !videoId.isEmpty()
                ? "https://www.youtube.com/watch?v=" + videoId : null;
    }
}
