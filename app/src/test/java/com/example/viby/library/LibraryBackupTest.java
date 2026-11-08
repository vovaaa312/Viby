package com.example.viby.library;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.viby.data.PlaylistSource;
import com.example.viby.data.Track;

import org.json.JSONException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Backup format, merge-on-import and file relinking. */
public class LibraryBackupTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void roundTripKeepsLibraryFieldsAndRelativePaths() throws Exception {
        File root = temporaryFolder.newFolder("Music");
        Track track = track("Mix", "vid1", 3, new File(root, "Mix/one.mp3").getPath());
        track.album = "Album";
        track.favorite = true;
        track.playCount = 7;
        track.lastPlayedAt = 99L;
        track.youtubePosition = 2;
        PlaylistSource source = new PlaylistSource();
        source.playlistName = "Mix";
        source.sourceUrl = "https://www.youtube.com/playlist?list=PL1";
        source.youtubePlaylistId = "PL1";
        source.youtubeSyncEnabled = true;

        String json = LibraryBackup.toJson(LibraryBackup.fromLibrary(
                Collections.singletonList(track), Collections.singletonList(source),
                root, 1234L, "1.2.9", LibraryBackup.SCOPE_LIBRARY));
        LibraryBackup.Snapshot parsed = LibraryBackup.parse(json);

        assertEquals(1234L, parsed.exportedAt);
        assertEquals(1, parsed.playlists.size());
        assertEquals("PL1", parsed.playlists.get(0).youtubePlaylistId);
        LibraryBackup.Entry entry = parsed.tracks.get(0);
        assertEquals("Mix", entry.playlist);
        assertEquals(3, entry.position);
        assertEquals("Mix/one.mp3", entry.relativePath);
        assertEquals("Album", entry.album);
        assertTrue(entry.favorite);
        assertEquals(7, entry.playCount);
        assertEquals(Integer.valueOf(2), entry.youtubePosition);
    }

    @Test
    public void backupNeverContainsAbsolutePathsOrDiagnostics() throws Exception {
        File root = temporaryFolder.newFolder("Music");
        Track track = track("Mix", "vid1", 0, new File(root, "Mix/one.mp3").getPath());
        track.downloadErrorMessage = "Sign in: cookie=SECRET";
        track.downloadErrorCode = "AUTH_REQUIRED";

        String json = LibraryBackup.toJson(LibraryBackup.fromLibrary(
                Collections.singletonList(track), Collections.emptyList(), root, 0L,
                null, LibraryBackup.SCOPE_LIBRARY));

        assertFalse(json.contains("SECRET"));
        assertFalse(json.contains("AUTH_REQUIRED"));
        assertFalse(json.contains(root.getAbsolutePath().replace('\\', '/')));
        assertFalse(json.contains(root.getAbsolutePath()));
    }

    @Test
    public void parseRejectsForeignFilesAndUnsafePaths() throws Exception {
        try {
            LibraryBackup.parse("{\"format\":\"other\"}");
            fail("foreign JSON must be rejected");
        } catch (JSONException expected) {
            // ok
        }
        assertNull(LibraryBackup.safeRelativePath("../../data/data/app/db"));
        assertNull(LibraryBackup.safeRelativePath("C:/Windows/x.mp3"));
        assertNull(LibraryBackup.safeRelativePath("Mix/./x.mp3"));
        assertEquals("Mix/x.mp3", LibraryBackup.safeRelativePath("/Mix\\x.mp3"));
    }

    @Test
    public void parseSkipsEntriesWithoutPlaylistOrTitle() throws Exception {
        String json = "{\"format\":\"viby-library\",\"version\":1,\"tracks\":["
                + "{\"playlist\":\"\",\"title\":\"x\"},"
                + "{\"playlist\":\"Mix\"},"
                + "{\"playlist\":\"Mix\",\"title\":\"ok\",\"relativePath\":\"../evil\"}]}";
        LibraryBackup.Snapshot snapshot = LibraryBackup.parse(json);
        assertEquals(1, snapshot.tracks.size());
        assertNull(snapshot.tracks.get(0).relativePath);
    }

    @Test
    public void importAddsTracksWithCorrectStateForEachFileSituation() throws Exception {
        File root = temporaryFolder.newFolder("Music");
        assertTrue(new File(root, "Mix").mkdirs());
        assertTrue(new File(root, "Mix/present.mp3").createNewFile());
        LibraryBackup.Snapshot snapshot = new LibraryBackup.Snapshot();
        snapshot.tracks.add(entry("Mix", "Present", "v1", "Mix/present.mp3", 0));
        snapshot.tracks.add(entry("Mix", "Gone but on YouTube", "v2", "Mix/gone.mp3", 1));
        snapshot.tracks.add(entry("Mix", "Gone local file", null, "Mix/local.mp3", 2));
        FakeStore store = new FakeStore();

        LibraryBackupImporter.Result result = new LibraryBackupImporter(store)
                .importSnapshot(snapshot, root, "missing", 10L);

        assertEquals(3, result.added);
        assertEquals(2, result.missingFiles);
        Track present = store.tracks.get(0);
        assertTrue(present.downloaded);
        assertEquals(Track.DOWNLOAD_DOWNLOADED, present.downloadState);
        Track youtube = store.tracks.get(1);
        assertFalse(youtube.downloaded);
        assertEquals(Track.DOWNLOAD_WAITING, youtube.downloadState);
        assertEquals("https://www.youtube.com/watch?v=v2", youtube.sourceUrl);
        Track local = store.tracks.get(2);
        assertEquals(Track.DOWNLOAD_FAILED_PERMANENT, local.downloadState);
        assertEquals(LibraryBackupImporter.ERROR_FILE_MISSING, local.downloadErrorCode);
        assertEquals("missing", local.downloadErrorMessage);
        // order inside the playlist follows the backup
        assertEquals(0, present.position);
        assertEquals(2, local.position);
    }

    @Test
    public void importMergesExistingRowsInsteadOfDuplicating() throws Exception {
        File root = temporaryFolder.newFolder("Music");
        FakeStore store = new FakeStore();
        Track existing = track("Mix", "v1", 0, "");
        existing.downloaded = false;
        existing.playCount = 2;
        store.insert(existing);
        LibraryBackup.Snapshot snapshot = new LibraryBackup.Snapshot();
        LibraryBackup.Entry entry = entry("Mix", "Song", "v1", "Mix/song.mp3", 0);
        entry.favorite = true;
        entry.playCount = 5;
        entry.album = "Album";
        snapshot.tracks.add(entry);
        LibraryBackup.PlaylistEntry playlist = new LibraryBackup.PlaylistEntry();
        playlist.name = "Mix";
        playlist.sourceUrl = "https://www.youtube.com/playlist?list=PL1";
        playlist.youtubeSyncEnabled = true;
        playlist.youtubeOwnerChannelId = "channel";
        snapshot.playlists.add(playlist);

        LibraryBackupImporter.Result result = new LibraryBackupImporter(store)
                .importSnapshot(snapshot, root, "missing", 10L);

        assertEquals(0, result.added);
        assertEquals(1, result.merged);
        assertEquals(1, store.tracks.size());
        assertTrue(existing.favorite);
        assertEquals(5, existing.playCount);
        assertEquals("Album", existing.album);
        PlaylistSource source = store.sources.get("Mix");
        assertEquals(playlist.sourceUrl, source.sourceUrl);
        assertFalse("remote sync must be re-enabled explicitly", source.youtubeSyncEnabled);
        assertNull(source.youtubeOwnerChannelId);
    }

    @Test
    public void importNeverOverwritesExistingPlaylistSource() throws Exception {
        FakeStore store = new FakeStore();
        PlaylistSource mine = new PlaylistSource();
        mine.playlistName = "Mix";
        mine.sourceUrl = "mine";
        store.sources.put("Mix", mine);
        LibraryBackup.Snapshot snapshot = new LibraryBackup.Snapshot();
        LibraryBackup.PlaylistEntry playlist = new LibraryBackup.PlaylistEntry();
        playlist.name = "Mix";
        playlist.sourceUrl = "theirs";
        snapshot.playlists.add(playlist);

        LibraryBackupImporter.Result result = new LibraryBackupImporter(store)
                .importSnapshot(snapshot, temporaryFolder.getRoot(), "missing", 1L);

        assertEquals(0, result.playlistsRestored);
        assertEquals("mine", store.sources.get("Mix").sourceUrl);
    }

    @Test
    public void relinkerFindsMovedFilesRestoresFromMirrorAndMarksTheRest() throws Exception {
        File root = temporaryFolder.newFolder("Music");
        assertTrue(new File(root, "Mix").mkdirs());
        File ok = new File(root, "Mix/ok.mp3");
        assertTrue(ok.createNewFile());
        assertTrue(new File(root, "Mix/moved.mp3").createNewFile());

        Track inPlace = track("Mix", "a", 0, ok.getAbsolutePath());
        Track moved = track("Mix", "b", 1, "/old/device/files/Music/Other/moved.mp3");
        Track mirrored = track("Mix", "c", 2, new File(root, "Mix/mirror.mp3").getPath());
        Track lost = track("Mix", "d", 3, new File(root, "Mix/lost.mp3").getPath());
        Track waiting = track("Mix", "e", 4, new File(root, "Mix/waiting.mp3").getPath());
        waiting.downloaded = false;
        Track placeholder = track("Mix", "f", 5, "");
        placeholder.downloaded = false;

        Map<Long, String> relinked = new HashMap<>();
        List<Long> missing = new ArrayList<>();
        LibraryRelinker relinker = new LibraryRelinker(new LibraryRelinker.Store() {
            @Override
            public void relink(Track track, String filePath) {
                relinked.put(track.id, filePath);
            }

            @Override
            public void markMissing(Track track) {
                missing.add(track.id);
            }
        });
        LibraryRelinker.Mirror mirror = (relativePath, destination) -> {
            if (!relativePath.equals("Mix/mirror.mp3")) {
                return false;
            }
            try {
                return destination.createNewFile();
            } catch (Exception error) {
                return false;
            }
        };

        LibraryRelinker.Result result = relinker.relink(
                java.util.Arrays.asList(inPlace, moved, mirrored, lost, waiting, placeholder),
                root, mirror);

        assertEquals(5, result.checked);
        assertEquals(1, result.alreadyOk);
        assertEquals(1, result.relinked);
        assertEquals(1, result.restoredFromMirror);
        assertEquals(1, result.markedMissing);
        assertEquals(1, result.stillMissing);
        assertEquals(new File(root, "Mix/moved.mp3").getAbsolutePath(), relinked.get(moved.id));
        assertTrue(new File(relinked.get(mirrored.id)).isFile());
        assertEquals(Collections.singletonList(lost.id), missing);
    }

    // ----------------------------------------------------------------- helpers

    private static long nextId = 1;

    private static Track track(String playlist, String videoId, int position, String path) {
        Track track = new Track();
        track.id = nextId++;
        track.playlistName = playlist;
        track.videoId = videoId;
        track.title = "Title " + videoId;
        track.position = position;
        track.filePath = path;
        track.downloaded = true;
        return track;
    }

    private static LibraryBackup.Entry entry(String playlist, String title, String videoId,
                                             String relativePath, int position) {
        LibraryBackup.Entry entry = new LibraryBackup.Entry();
        entry.playlist = playlist;
        entry.title = title;
        entry.videoId = videoId;
        entry.relativePath = relativePath;
        entry.position = position;
        entry.downloaded = true;
        return entry;
    }

    private static final class FakeStore implements LibraryBackupImporter.Store {
        final List<Track> tracks = new ArrayList<>();
        final Map<String, PlaylistSource> sources = new HashMap<>();

        @Override
        public Track findByVideoId(String playlist, String videoId) {
            for (Track track : tracks) {
                if (track.playlistName.equals(playlist) && videoId.equals(track.videoId)) {
                    return track;
                }
            }
            return null;
        }

        @Override
        public Track findByFile(String playlist, String filePath) {
            for (Track track : tracks) {
                if (track.playlistName.equals(playlist) && filePath.equals(track.filePath)) {
                    return track;
                }
            }
            return null;
        }

        @Override
        public int nextPosition(String playlist) {
            int next = 0;
            for (Track track : tracks) {
                if (track.playlistName.equals(playlist)) {
                    next = Math.max(next, track.position + 1);
                }
            }
            return next;
        }

        @Override
        public void insert(Track track) {
            tracks.add(track);
        }

        @Override
        public void update(Track track) {
            // rows are mutated in place
        }

        @Override
        public PlaylistSource source(String playlist) {
            return sources.get(playlist);
        }

        @Override
        public void upsertSource(PlaylistSource source) {
            sources.put(source.playlistName, source);
        }
    }
}
