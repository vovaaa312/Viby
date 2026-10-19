package com.example.viby.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.example.viby.data.Track;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LibraryImporterTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void reuseSharesExistingFileAndCreatesIndependentPlaylistEntry()
            throws Exception {
        FakeStore store = new FakeStore();
        File media = temporaryFolder.newFile("shared.mp3");
        Track reusable = downloaded("video", "Original", "Artist", media);
        reusable.durationMs = 12_000L;
        reusable.thumbnailUrl = "cover";
        store.downloaded.add(reusable);

        boolean reused = new LibraryImporter(store).reuseDownloadedTrack(
                "video", "", null, 0L, "Second playlist", 4, "source");

        assertTrue(reused);
        assertEquals(1, store.inserted.size());
        Track target = store.inserted.get(0);
        assertEquals("Original", target.title);
        assertEquals("Artist", target.uploader);
        assertEquals(12_000L, target.durationMs);
        assertEquals(media.getAbsolutePath(), target.filePath);
        assertEquals("Second playlist", target.playlistName);
        assertEquals(Integer.valueOf(4), target.youtubePosition);
        assertEquals(7, target.position);
        assertDownloaded(target);
    }

    @Test
    public void reuseIgnoresDatabaseEntryWhoseFileIsMissing() {
        FakeStore store = new FakeStore();
        store.downloaded.add(downloaded("video", "Missing", "Artist",
                new File(temporaryFolder.getRoot(), "missing.mp3")));

        boolean reused = new LibraryImporter(store).reuseDownloadedTrack(
                "video", "Title", "Artist", 1L, "Playlist", null, "source");

        assertFalse(reused);
        assertTrue(store.inserted.isEmpty());
        assertTrue(store.updated.isEmpty());
    }

    @Test
    public void importUpdatesPlaceholderAndClearsFailureState() throws Exception {
        FakeStore store = new FakeStore();
        Track placeholder = new Track();
        placeholder.id = 42L;
        placeholder.videoId = "video";
        placeholder.playlistName = "Playlist";
        placeholder.position = 3;
        placeholder.downloaded = false;
        placeholder.downloadState = Track.DOWNLOAD_FAILED_TEMPORARY;
        placeholder.downloadErrorCode = "NETWORK";
        placeholder.downloadErrorMessage = "old error";
        placeholder.downloadRequiresAuthorization = true;
        store.tracks.put(FakeStore.key("Playlist", "video"), placeholder);
        File media = temporaryFolder.newFile("finished.m4a");

        new LibraryImporter(store).importDownloaded(
                "video", "Title", "Artist", 30_000L, "Playlist", 2,
                "source", media, 3, 1234L, "m4a");

        assertEquals(1, store.updated.size());
        assertSame(placeholder, store.updated.get(0));
        assertTrue(store.inserted.isEmpty());
        assertEquals(3, placeholder.position);
        assertEquals(media.getAbsolutePath(), placeholder.filePath);
        assertEquals(3, placeholder.downloadAttemptCount);
        assertEquals(1234L, placeholder.downloadLastAttemptAt);
        assertEquals("m4a", placeholder.downloadFormat);
        assertDownloaded(placeholder);
    }

    private static Track downloaded(String videoId, String title, String uploader,
                                    File file) {
        Track track = new Track();
        track.videoId = videoId;
        track.title = title;
        track.uploader = uploader;
        track.filePath = file.getAbsolutePath();
        track.downloaded = true;
        return track;
    }

    private static void assertDownloaded(Track track) {
        assertTrue(track.downloaded);
        assertEquals(Track.DOWNLOAD_DOWNLOADED, track.downloadState);
        assertEquals(100, track.downloadProgress);
        assertNull(track.downloadErrorCode);
        assertNull(track.downloadErrorMessage);
        assertFalse(track.downloadRequiresAuthorization);
    }

    private static final class FakeStore implements LibraryImporter.Store {
        final List<Track> downloaded = new ArrayList<>();
        final Map<String, Track> tracks = new HashMap<>();
        final List<Track> inserted = new ArrayList<>();
        final List<Track> updated = new ArrayList<>();

        @Override
        public List<Track> downloadedByVideoId(String videoId) {
            return downloaded;
        }

        @Override
        public Track find(String playlist, String videoId) {
            return tracks.get(key(playlist, videoId));
        }

        @Override
        public int nextPosition(String playlist) {
            return 7;
        }

        @Override
        public void insert(Track track) {
            inserted.add(track);
        }

        @Override
        public void update(Track track) {
            updated.add(track);
        }

        static String key(String playlist, String videoId) {
            return playlist + '\n' + videoId;
        }
    }
}
