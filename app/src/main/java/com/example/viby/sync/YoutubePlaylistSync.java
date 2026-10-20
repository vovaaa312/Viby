package com.example.viby.sync;

import android.content.Context;

import androidx.annotation.Nullable;

import com.example.viby.R;
import com.example.viby.data.PlaylistSource;
import com.example.viby.data.Track;
import com.example.viby.data.TrackDao;
import com.example.viby.data.VibyDatabase;
import com.example.viby.data.YoutubePlaylistOrder;
import com.example.viby.util.YoutubeUrlParser;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Blocking synchronization operations. Call only from a background executor. */
public final class YoutubePlaylistSync {

    private final Context context;
    private final VibyDatabase database;
    private final YoutubePlaylistApi api;

    public static final class EnableResult {
        public final String remoteTitle;
        public final int linkedTracks;

        EnableResult(String remoteTitle, int linkedTracks) {
            this.remoteTitle = remoteTitle;
            this.linkedTracks = linkedTracks;
        }
    }

    public static final class AddResult {
        public final boolean downloadRequired;

        AddResult(boolean downloadRequired) {
            this.downloadRequired = downloadRequired;
        }
    }

    public static final class DeleteResult {
        public final List<Track> removed;
        public final int failedCount;
        @Nullable public final String firstError;

        DeleteResult(List<Track> removed, int failedCount, @Nullable String firstError) {
            this.removed = removed;
            this.failedCount = failedCount;
            this.firstError = firstError;
        }
    }

    public static final class SaveRadioResult {
        public final String remoteTitle;
        public final int addedCount;
        public final int failedCount;
        @Nullable public final String firstError;

        SaveRadioResult(String remoteTitle, int addedCount, int failedCount,
                        @Nullable String firstError) {
            this.remoteTitle = remoteTitle;
            this.addedCount = addedCount;
            this.failedCount = failedCount;
            this.firstError = firstError;
        }
    }

    public static final class MissingTracksResult {
        public final int addedCount;
        public final int failedCount;
        @Nullable public final String firstError;

        MissingTracksResult(int addedCount, int failedCount,
                            @Nullable String firstError) {
            this.addedCount = addedCount;
            this.failedCount = failedCount;
            this.firstError = firstError;
        }
    }

    public YoutubePlaylistSync(Context context) {
        this(context, new YoutubePlaylistApi());
    }

    YoutubePlaylistSync(Context context, YoutubePlaylistApi api) {
        this.context = context.getApplicationContext();
        this.database = VibyDatabase.get(this.context);
        this.api = api;
    }

    public EnableResult enable(String localPlaylist, String accessToken) throws Exception {
        PlaylistSource source = requireSource(localPlaylist);
        String playlistId = playlistId(source);
        YoutubePlaylistApi.OwnedPlaylist owned =
                api.findOwnedPlaylist(accessToken, playlistId);
        if (owned == null) {
            throw new IllegalStateException(
                    context.getString(R.string.youtube_sync_not_owner));
        }
        List<YoutubePlaylistApi.PlaylistItem> remote =
                api.listItems(accessToken, playlistId);
        final int[] linked = {0};
        database.runInTransaction(() -> {
            List<Track> tracks = database.trackDao().getPlaylistSync(localPlaylist);
            linked[0] = linkTracks(tracks, remote, true);
            if (!tracks.isEmpty()) {
                database.trackDao().updateAll(tracks);
            }
            source.youtubePlaylistId = playlistId;
            source.youtubeOwnerChannelId = owned.channelId;
            source.youtubeSyncEnabled = true;
            source.updatedAt = System.currentTimeMillis();
            database.playlistSourceDao().upsert(source);
        });
        return new EnableResult(owned.title, linked[0]);
    }

    public void disable(String localPlaylist) {
        PlaylistSource source = database.playlistSourceDao().getSync(localPlaylist);
        if (source == null) {
            return;
        }
        source.youtubeSyncEnabled = false;
        database.playlistSourceDao().upsert(source);
    }

    /** Saves the current finite radio snapshot as a private owned YouTube playlist. */
    public SaveRadioResult saveRadioToYoutube(String localPlaylist, String accessToken)
            throws Exception {
        PlaylistSource source = requireSource(localPlaylist);
        if (!YoutubeUrlParser.isRadioMixUrl(source.sourceUrl)) {
            throw new IllegalStateException(
                    context.getString(R.string.youtube_sync_invalid_source));
        }
        TrackDao dao = database.trackDao();
        List<Track> tracks = dao.getPlaylistSync(localPlaylist);
        if (tracks.isEmpty()) {
            throw new IllegalStateException(context.getString(R.string.track_radio_empty));
        }

        String title = truncate(localPlaylist, 150);
        YoutubePlaylistApi.OwnedPlaylist created = api.createPrivatePlaylist(
                accessToken, title, context.getString(R.string.track_radio_description));

        int added = 0;
        int failed = 0;
        String firstError = null;
        for (Track track : tracks) {
            track.youtubePlaylistItemId = null;
            track.youtubePosition = null;
            if (track.videoId == null || track.videoId.isEmpty()) {
                failed++;
                if (firstError == null) {
                    firstError = context.getString(R.string.track_radio_unavailable);
                }
                continue;
            }
            try {
                YoutubePlaylistApi.PlaylistItem item = api.insertAtPosition(
                        accessToken, created.id, track.videoId, added);
                track.youtubePlaylistItemId = item.id;
                track.youtubePosition = item.position;
                added++;
            } catch (Exception error) {
                failed++;
                if (firstError == null) {
                    firstError = error.getMessage();
                }
            }
        }

        source.youtubePlaylistId = created.id;
        source.youtubeOwnerChannelId = created.channelId;
        source.youtubeSyncEnabled = true;
        source.updatedAt = System.currentTimeMillis();
        database.runInTransaction(() -> {
            dao.updateAll(tracks);
            database.playlistSourceDao().upsert(source);
        });
        return new SaveRadioResult(created.title, added, failed, firstError);
    }

    public AddResult addTrackAtStart(String localPlaylist, String url,
                                     String accessToken) throws Exception {
        String videoId = YoutubeUrlParser.videoId(url);
        if (videoId == null) {
            throw new IllegalArgumentException(
                    context.getString(R.string.error_invalid_url));
        }
        PlaylistSource source = requireEnabledSource(localPlaylist);
        String playlistId = playlistId(source);
        TrackDao dao = database.trackDao();
        Track downloaded = dao.getDownloadedByVideoIdSync(localPlaylist, videoId);
        Track any = dao.getByVideoIdSync(localPlaylist, videoId);
        if (downloaded == null && any != null) {
            throw new IllegalStateException(
                    context.getString(R.string.youtube_sync_track_pending));
        }

        YoutubePlaylistApi.PlaylistItem remote =
                api.insertAtStart(accessToken, playlistId, videoId);
        try {
            database.runInTransaction(() -> {
                Track added = downloaded != null
                        ? duplicate(downloaded) : placeholder(localPlaylist, videoId, url);
                added.youtubePlaylistItemId = remote.id;
                added.youtubePosition = remote.position;
                added.position = dao.nextPosition(localPlaylist);
                added.id = dao.insert(added);

                List<Track> tracks = dao.getPlaylistSync(localPlaylist);
                for (Track track : tracks) {
                    if (track.id != added.id && track.youtubePosition != null
                            && track.youtubePosition >= remote.position) {
                        track.youtubePosition++;
                    }
                }
                YoutubePlaylistOrder.sort(tracks);
                for (int i = 0; i < tracks.size(); i++) {
                    tracks.get(i).position = i;
                }
                dao.updateAll(tracks);
            });
        } catch (RuntimeException databaseFailure) {
            try {
                api.deleteItem(accessToken, remote.id);
            } catch (Exception ignored) {
            }
            throw databaseFailure;
        }
        return new AddResult(downloaded == null);
    }

    /** Adds every local YouTube-backed occurrence which is absent from the remote playlist. */
    public MissingTracksResult syncMissingTracks(String localPlaylist,
                                                  String accessToken)
            throws Exception {
        return syncMissingTracks(localPlaylist, null, accessToken);
    }

    /** Adds only requested video occurrences; null means all missing YouTube-backed tracks. */
    public MissingTracksResult syncMissingTracks(String localPlaylist,
                                                  @Nullable List<String> requestedVideoIds,
                                                  String accessToken)
            throws Exception {
        PlaylistSource source = requireEnabledSource(localPlaylist);
        String playlistId = playlistId(source);
        YoutubePlaylistApi.OwnedPlaylist owned =
                api.findOwnedPlaylist(accessToken, playlistId);
        if (owned == null) {
            throw new IllegalStateException(
                    context.getString(R.string.youtube_sync_not_owner));
        }

        List<YoutubePlaylistApi.PlaylistItem> remote =
                api.listItems(accessToken, playlistId);
        TrackDao dao = database.trackDao();
        List<Track> tracks = dao.getPlaylistSync(localPlaylist);
        linkTracks(tracks, remote, false);
        List<Track> missing = missingYoutubeTracks(tracks, requestedVideoIds);

        int added = 0;
        int failed = 0;
        String firstError = null;
        for (Track track : missing) {
            try {
                int position = Math.max(0,
                        Math.min(track.position, remote.size() + added));
                YoutubePlaylistApi.PlaylistItem item = api.insertAtPosition(
                        accessToken, playlistId, track.videoId, position);
                track.youtubePlaylistItemId = item.id;
                track.youtubePosition = item.position;
                added++;
            } catch (Exception error) {
                failed++;
                if (firstError == null) {
                    firstError = error.getMessage();
                }
            }
        }

        boolean reordered = false;
        if (added > 0) {
            try {
                linkTracks(tracks, api.listItems(accessToken, playlistId), true);
                reordered = true;
            } catch (Exception ignored) {
                // Insert responses already carry stable item ids; refresh can happen later.
            }
        } else {
            linkTracks(tracks, remote, true);
            reordered = true;
        }
        if (!reordered) {
            YoutubePlaylistOrder.sort(tracks);
            for (int i = 0; i < tracks.size(); i++) {
                tracks.get(i).position = i;
            }
        }
        if (!tracks.isEmpty()) {
            dao.updateAll(tracks);
        }
        return new MissingTracksResult(added, failed, firstError);
    }

    public DeleteResult deleteTracks(String localPlaylist, List<Track> selected,
                                     boolean deleteFiles, String accessToken)
            throws Exception {
        PlaylistSource source = requireEnabledSource(localPlaylist);
        String playlistId = playlistId(source);
        List<YoutubePlaylistApi.PlaylistItem> remote =
                api.listItems(accessToken, playlistId);
        TrackDao dao = database.trackDao();
        List<Track> all = dao.getPlaylistSync(localPlaylist);
        linkTracks(all, remote, false);
        Map<Long, Track> byId = new HashMap<>();
        for (Track track : all) {
            byId.put(track.id, track);
        }

        List<Track> removable = new ArrayList<>();
        int failed = 0;
        String firstError = null;
        Set<String> removedRemoteIds = new HashSet<>();
        for (Track requested : selected) {
            Track current = byId.get(requested.id);
            if (current == null) {
                continue;
            }
            if (current.youtubePlaylistItemId == null) {
                removable.add(current); // already absent remotely or local-only
                continue;
            }
            try {
                api.deleteItem(accessToken, current.youtubePlaylistItemId);
                removedRemoteIds.add(current.youtubePlaylistItemId);
                removable.add(current);
            } catch (Exception e) {
                failed++;
                if (firstError == null) {
                    firstError = e.getMessage();
                }
            }
        }

        List<YoutubePlaylistApi.PlaylistItem> remainingRemote = new ArrayList<>();
        for (YoutubePlaylistApi.PlaylistItem item : remote) {
            if (!removedRemoteIds.contains(item.id)) {
                remainingRemote.add(item);
            }
        }
        database.runInTransaction(() -> {
            for (Track track : removable) {
                dao.delete(track);
                if (deleteFiles) {
                    deleteFileIfUnreferenced(dao, track.filePath);
                }
            }
            List<Track> remaining = dao.getPlaylistSync(localPlaylist);
            linkTracks(remaining, remainingRemote, true);
            if (!remaining.isEmpty()) {
                dao.updateAll(remaining);
            }
        });
        return new DeleteResult(removable, failed, firstError);
    }

    /** Links exact occurrences first, then matches previously unlinked duplicates in order. */
    static int linkTracks(List<Track> tracks,
                          List<YoutubePlaylistApi.PlaylistItem> remote,
                          boolean reorderLocally) {
        Map<String, YoutubePlaylistApi.PlaylistItem> byItemId = new HashMap<>();
        for (YoutubePlaylistApi.PlaylistItem item : remote) {
            byItemId.put(item.id, item);
        }
        Map<Long, YoutubePlaylistApi.PlaylistItem> matches = new HashMap<>();
        Set<String> used = new HashSet<>();
        for (Track track : tracks) {
            YoutubePlaylistApi.PlaylistItem exact = track.youtubePlaylistItemId != null
                    ? byItemId.get(track.youtubePlaylistItemId) : null;
            if (exact != null && equals(track.videoId, exact.videoId)) {
                matches.put(track.id, exact);
                used.add(exact.id);
            }
        }

        Map<String, Deque<YoutubePlaylistApi.PlaylistItem>> byVideo =
                new LinkedHashMap<>();
        List<YoutubePlaylistApi.PlaylistItem> sortedRemote = new ArrayList<>(remote);
        sortedRemote.sort(Comparator.comparingInt(item -> item.position));
        for (YoutubePlaylistApi.PlaylistItem item : sortedRemote) {
            if (!used.contains(item.id)) {
                byVideo.computeIfAbsent(item.videoId, ignored -> new ArrayDeque<>())
                        .addLast(item);
            }
        }
        for (Track track : tracks) {
            if (matches.containsKey(track.id) || track.videoId == null) {
                continue;
            }
            Deque<YoutubePlaylistApi.PlaylistItem> candidates = byVideo.get(track.videoId);
            if (candidates != null && !candidates.isEmpty()) {
                matches.put(track.id, candidates.removeFirst());
            }
        }

        for (Track track : tracks) {
            YoutubePlaylistApi.PlaylistItem match = matches.get(track.id);
            track.youtubePlaylistItemId = match != null ? match.id : null;
            track.youtubePosition = match != null ? match.position : null;
        }
        if (reorderLocally) {
            YoutubePlaylistOrder.sort(tracks);
            for (int i = 0; i < tracks.size(); i++) {
                tracks.get(i).position = i;
            }
        }
        return matches.size();
    }

    public static boolean hasMissingYoutubeTracks(@Nullable List<Track> tracks) {
        return tracks != null && !missingYoutubeTracks(tracks, null).isEmpty();
    }

    /** A copied YouTube track has no source-playlist position until it is linked remotely. */
    public static boolean hasLocallyAddedYoutubeTracks(@Nullable List<Track> tracks) {
        if (tracks == null) {
            return false;
        }
        for (Track track : tracks) {
            if (track.videoId != null && !track.videoId.isEmpty()
                    && track.youtubePosition == null) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasOriginalYoutubePlaylist(@Nullable PlaylistSource source) {
        if (source == null) {
            return false;
        }
        String playlistId = source.youtubePlaylistId;
        if (playlistId == null || playlistId.isEmpty()) {
            playlistId = YoutubeUrlParser.playlistId(source.sourceUrl);
        }
        return playlistId != null && !YoutubeUrlParser.isRadioPlaylistId(playlistId);
    }

    static List<Track> missingYoutubeTracks(List<Track> tracks,
                                            @Nullable List<String> requestedVideoIds) {
        Map<String, Integer> requestedCounts = null;
        if (requestedVideoIds != null) {
            requestedCounts = new HashMap<>();
            for (String videoId : requestedVideoIds) {
                if (videoId != null && !videoId.isEmpty()) {
                    requestedCounts.put(videoId,
                            requestedCounts.getOrDefault(videoId, 0) + 1);
                }
            }
        }
        List<Track> localOrder = new ArrayList<>(tracks);
        localOrder.sort(Comparator.comparingInt(track -> track.position));
        List<Track> missing = new ArrayList<>();
        for (Track track : localOrder) {
            if (track.videoId == null || track.videoId.isEmpty()
                    || track.youtubePlaylistItemId != null) {
                continue;
            }
            if (requestedCounts != null) {
                int remaining = requestedCounts.getOrDefault(track.videoId, 0);
                if (remaining <= 0) {
                    continue;
                }
                requestedCounts.put(track.videoId, remaining - 1);
            }
            missing.add(track);
        }
        return missing;
    }

    private PlaylistSource requireSource(String localPlaylist) {
        PlaylistSource source = database.playlistSourceDao().getSync(localPlaylist);
        if (source == null) {
            throw new IllegalStateException(context.getString(R.string.refresh_no_source));
        }
        return source;
    }

    private PlaylistSource requireEnabledSource(String localPlaylist) {
        PlaylistSource source = requireSource(localPlaylist);
        if (!source.youtubeSyncEnabled) {
            throw new IllegalStateException(
                    context.getString(R.string.youtube_sync_not_enabled));
        }
        return source;
    }

    private String playlistId(PlaylistSource source) {
        String playlistId = source.youtubePlaylistId != null
                ? source.youtubePlaylistId : YoutubeUrlParser.playlistId(source.sourceUrl);
        if (playlistId == null) {
            throw new IllegalStateException(
                    context.getString(R.string.youtube_sync_invalid_source));
        }
        return playlistId;
    }

    private static Track duplicate(Track source) {
        Track duplicate = new Track();
        duplicate.videoId = source.videoId;
        duplicate.title = source.title;
        duplicate.uploader = source.uploader;
        duplicate.durationMs = source.durationMs;
        duplicate.filePath = source.filePath;
        duplicate.playlistName = source.playlistName;
        duplicate.thumbnailUrl = source.thumbnailUrl;
        duplicate.createdAt = System.currentTimeMillis();
        duplicate.downloaded = true;
        duplicate.sourceUrl = source.sourceUrl;
        return duplicate;
    }

    private static Track placeholder(String playlist, String videoId, String url) {
        Track track = new Track();
        track.videoId = videoId;
        track.title = "YouTube " + videoId;
        track.filePath = "";
        track.playlistName = playlist;
        track.thumbnailUrl = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
        track.createdAt = System.currentTimeMillis();
        track.downloaded = false;
        track.sourceUrl = url;
        return track;
    }

    private static void deleteFileIfUnreferenced(TrackDao dao, String filePath) {
        if (filePath == null || filePath.isEmpty()
                || dao.countFileReferences(filePath) > 0) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        new File(filePath).delete();
    }

    private static boolean equals(@Nullable String first, @Nullable String second) {
        return first == null ? second == null : first.equals(second);
    }

    private static String truncate(String value, int maxCodePoints) {
        if (value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }
}
