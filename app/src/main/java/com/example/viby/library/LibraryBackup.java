package com.example.viby.library;

import androidx.annotation.Nullable;

import com.example.viby.data.PlaylistSource;
import com.example.viby.data.Track;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Portable library snapshot. Contains only library metadata and relative file paths:
 * never cookies, OAuth tokens, download diagnostics or absolute device paths.
 */
public final class LibraryBackup {

    public static final String FORMAT = "viby-library";
    public static final int VERSION = 1;
    public static final String SCOPE_LIBRARY = "library";
    public static final String SCOPE_PLAYLIST = "playlist";
    public static final String MIRROR_FILE_NAME = "viby-library.json";

    public static final class PlaylistEntry {
        public String name;
        public String sourceUrl;
        public String youtubePlaylistId;
        public boolean youtubeSyncEnabled;
        public String youtubeOwnerChannelId;
        public long updatedAt;
    }

    public static final class Entry {
        public String playlist;
        public int position;
        public String title;
        public String artist;
        public String album;
        public long durationMs;
        public String videoId;
        public String sourceUrl;
        public String thumbnailUrl;
        public Integer youtubePosition;
        public String youtubePlaylistItemId;
        public long createdAt;
        public boolean downloaded;
        public String relativePath;
        public boolean favorite;
        public int playCount;
        public long lastPlayedAt;
    }

    public static final class Snapshot {
        public String scope = SCOPE_LIBRARY;
        public long exportedAt;
        public String appVersion;
        public final List<PlaylistEntry> playlists = new ArrayList<>();
        public final List<Entry> tracks = new ArrayList<>();
    }

    private LibraryBackup() {
    }

    public static Snapshot fromLibrary(List<Track> tracks, List<PlaylistSource> sources,
                                       File musicRoot, long now, String appVersion,
                                       String scope) {
        Snapshot snapshot = new Snapshot();
        snapshot.scope = scope;
        snapshot.exportedAt = now;
        snapshot.appVersion = appVersion;
        for (PlaylistSource source : sources) {
            PlaylistEntry entry = new PlaylistEntry();
            entry.name = source.playlistName;
            entry.sourceUrl = source.sourceUrl;
            entry.youtubePlaylistId = source.youtubePlaylistId;
            entry.youtubeSyncEnabled = source.youtubeSyncEnabled;
            entry.youtubeOwnerChannelId = source.youtubeOwnerChannelId;
            entry.updatedAt = source.updatedAt;
            snapshot.playlists.add(entry);
        }
        for (Track track : tracks) {
            Entry entry = new Entry();
            entry.playlist = track.playlistName;
            entry.position = track.position;
            entry.title = track.title;
            entry.artist = track.uploader;
            entry.album = track.album;
            entry.durationMs = track.durationMs;
            entry.videoId = track.videoId;
            entry.sourceUrl = track.sourceUrl;
            entry.thumbnailUrl = track.thumbnailUrl;
            entry.youtubePosition = track.youtubePosition;
            entry.youtubePlaylistItemId = track.youtubePlaylistItemId;
            entry.createdAt = track.createdAt;
            entry.downloaded = track.downloaded;
            entry.relativePath = LibraryPaths.relativePath(musicRoot, track.filePath);
            entry.favorite = track.favorite;
            entry.playCount = track.playCount;
            entry.lastPlayedAt = track.lastPlayedAt;
            snapshot.tracks.add(entry);
        }
        return snapshot;
    }

    public static String toJson(Snapshot snapshot) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("version", VERSION);
        root.put("scope", snapshot.scope);
        root.put("exportedAt", snapshot.exportedAt);
        putOptional(root, "appVersion", snapshot.appVersion);

        JSONArray playlists = new JSONArray();
        for (PlaylistEntry entry : snapshot.playlists) {
            JSONObject item = new JSONObject();
            item.put("name", entry.name);
            putOptional(item, "sourceUrl", entry.sourceUrl);
            putOptional(item, "youtubePlaylistId", entry.youtubePlaylistId);
            item.put("youtubeSyncEnabled", entry.youtubeSyncEnabled);
            putOptional(item, "youtubeOwnerChannelId", entry.youtubeOwnerChannelId);
            item.put("updatedAt", entry.updatedAt);
            playlists.put(item);
        }
        root.put("playlists", playlists);

        JSONArray tracks = new JSONArray();
        for (Entry entry : snapshot.tracks) {
            JSONObject item = new JSONObject();
            item.put("playlist", entry.playlist);
            item.put("position", entry.position);
            item.put("title", entry.title);
            putOptional(item, "artist", entry.artist);
            putOptional(item, "album", entry.album);
            item.put("durationMs", entry.durationMs);
            putOptional(item, "videoId", entry.videoId);
            putOptional(item, "sourceUrl", entry.sourceUrl);
            putOptional(item, "thumbnailUrl", entry.thumbnailUrl);
            if (entry.youtubePosition != null) {
                item.put("youtubePosition", entry.youtubePosition.intValue());
            }
            putOptional(item, "youtubePlaylistItemId", entry.youtubePlaylistItemId);
            item.put("createdAt", entry.createdAt);
            item.put("downloaded", entry.downloaded);
            putOptional(item, "relativePath", entry.relativePath);
            item.put("favorite", entry.favorite);
            item.put("playCount", entry.playCount);
            item.put("lastPlayedAt", entry.lastPlayedAt);
            tracks.put(item);
        }
        root.put("tracks", tracks);
        return root.toString(2);
    }

    public static Snapshot parse(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        if (!FORMAT.equals(root.optString("format"))) {
            throw new JSONException("Not a Viby library backup");
        }
        if (root.optInt("version", 0) > VERSION) {
            throw new JSONException("Backup was created by a newer Viby version");
        }
        Snapshot snapshot = new Snapshot();
        snapshot.scope = root.optString("scope", SCOPE_LIBRARY);
        snapshot.exportedAt = root.optLong("exportedAt");
        snapshot.appVersion = optString(root, "appVersion");

        JSONArray playlists = root.optJSONArray("playlists");
        if (playlists != null) {
            for (int i = 0; i < playlists.length(); i++) {
                JSONObject item = playlists.getJSONObject(i);
                String name = optString(item, "name");
                if (name == null || name.trim().isEmpty()) {
                    continue;
                }
                PlaylistEntry entry = new PlaylistEntry();
                entry.name = name;
                entry.sourceUrl = optString(item, "sourceUrl");
                entry.youtubePlaylistId = optString(item, "youtubePlaylistId");
                entry.youtubeSyncEnabled = item.optBoolean("youtubeSyncEnabled");
                entry.youtubeOwnerChannelId = optString(item, "youtubeOwnerChannelId");
                entry.updatedAt = item.optLong("updatedAt");
                snapshot.playlists.add(entry);
            }
        }

        JSONArray tracks = root.optJSONArray("tracks");
        if (tracks != null) {
            for (int i = 0; i < tracks.length(); i++) {
                JSONObject item = tracks.getJSONObject(i);
                String playlist = optString(item, "playlist");
                String title = optString(item, "title");
                if (playlist == null || playlist.trim().isEmpty() || title == null) {
                    continue;
                }
                Entry entry = new Entry();
                entry.playlist = playlist;
                entry.position = item.optInt("position");
                entry.title = title;
                entry.artist = optString(item, "artist");
                entry.album = optString(item, "album");
                entry.durationMs = item.optLong("durationMs");
                entry.videoId = optString(item, "videoId");
                entry.sourceUrl = optString(item, "sourceUrl");
                entry.thumbnailUrl = optString(item, "thumbnailUrl");
                entry.youtubePosition = item.has("youtubePosition")
                        && !item.isNull("youtubePosition")
                        ? item.optInt("youtubePosition") : null;
                entry.youtubePlaylistItemId = optString(item, "youtubePlaylistItemId");
                entry.createdAt = item.optLong("createdAt");
                entry.downloaded = item.optBoolean("downloaded");
                entry.relativePath = safeRelativePath(optString(item, "relativePath"));
                entry.favorite = item.optBoolean("favorite");
                entry.playCount = Math.max(0, item.optInt("playCount"));
                entry.lastPlayedAt = item.optLong("lastPlayedAt");
                snapshot.tracks.add(entry);
            }
        }
        return snapshot;
    }

    /** Rejects absolute paths and "..": a backup must never point outside the Music root. */
    @Nullable
    static String safeRelativePath(@Nullable String path) {
        if (path == null) {
            return null;
        }
        String normalized = LibraryPaths.normalize(path.trim());
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isEmpty() || normalized.contains(":")) {
            return null;
        }
        for (String segment : normalized.split("/")) {
            if (segment.equals("..") || segment.equals(".") || segment.isEmpty()) {
                return null;
            }
        }
        return normalized;
    }

    private static void putOptional(JSONObject object, String key, @Nullable String value)
            throws JSONException {
        if (value != null) {
            object.put(key, value);
        }
    }

    @Nullable
    private static String optString(JSONObject object, String key) {
        if (!object.has(key) || object.isNull(key)) {
            return null;
        }
        return object.optString(key, null);
    }
}
