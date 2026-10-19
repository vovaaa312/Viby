package com.example.viby.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlaylistTrackCopierTest {

    @Test
    public void copyKeepsSourceAndSharesMediaFileWithNewPlaylistEntry() {
        FakeStore store = new FakeStore();
        Track source = track("video", "Source", "/music/source/song.mp3");
        source.id = 42L;
        source.position = 3;
        source.youtubePosition = 8;
        source.youtubePlaylistItemId = "remote-item";
        source.downloadFormat = "mp3";
        source.downloadProgress = 100;

        int copied = new PlaylistTrackCopier(store).copy(
                Collections.singletonList(source), "Target");

        assertEquals(1, copied);
        assertEquals(1, store.inserted.size());
        Track copy = store.inserted.get(0);
        assertEquals(0L, copy.id);
        assertEquals("Target", copy.playlistName);
        assertEquals(10, copy.position);
        assertEquals(source.filePath, copy.filePath);
        assertEquals(source.videoId, copy.videoId);
        assertEquals("mp3", copy.downloadFormat);
        assertNull(copy.youtubePosition);
        assertNull(copy.youtubePlaylistItemId);

        assertEquals(42L, source.id);
        assertEquals("Source", source.playlistName);
        assertEquals(3, source.position);
        assertEquals("remote-item", source.youtubePlaylistItemId);
        assertEquals("/music/source/song.mp3", source.filePath);
    }

    @Test
    public void copySkipsSamePlaylistAndExistingYoutubeTrack() {
        FakeStore store = new FakeStore();
        Track samePlaylist = track("same", "Target", "/same.mp3");
        Track duplicate = track("duplicate", "Source", "/duplicate.mp3");
        store.byVideoId.put(FakeStore.key("Target", "duplicate"), new Track());

        int copied = new PlaylistTrackCopier(store).copy(
                java.util.Arrays.asList(samePlaylist, duplicate), "Target");

        assertEquals(0, copied);
        assertTrue(store.inserted.isEmpty());
    }

    @Test
    public void localTrackUsesFilePathToPreventDuplicateMembership() {
        FakeStore store = new FakeStore();
        Track local = track(null, "Source", "/local/song.mp3");
        store.byFilePath.put(FakeStore.key("Target", local.filePath), new Track());

        int copied = new PlaylistTrackCopier(store).copy(
                Collections.singletonList(local), "Target");

        assertEquals(0, copied);
        assertFalse(store.inserted.contains(local));
    }

    private static Track track(String videoId, String playlist, String filePath) {
        Track track = new Track();
        track.videoId = videoId;
        track.title = "Title";
        track.uploader = "Artist";
        track.durationMs = 123L;
        track.filePath = filePath;
        track.playlistName = playlist;
        track.thumbnailUrl = "cover";
        track.downloaded = true;
        track.sourceUrl = "source";
        track.downloadState = Track.DOWNLOAD_DOWNLOADED;
        return track;
    }

    private static final class FakeStore implements PlaylistTrackCopier.Store {
        final Map<String, Track> byVideoId = new HashMap<>();
        final Map<String, Track> byFilePath = new HashMap<>();
        final List<Track> inserted = new ArrayList<>();

        @Override
        public Track findByVideoId(String playlist, String videoId) {
            return byVideoId.get(key(playlist, videoId));
        }

        @Override
        public Track findByFilePath(String playlist, String filePath) {
            return byFilePath.get(key(playlist, filePath));
        }

        @Override
        public int nextPosition(String playlist) {
            return 10 + inserted.size();
        }

        @Override
        public void insert(Track track) {
            inserted.add(track);
        }

        static String key(String playlist, String value) {
            return playlist + '\n' + value;
        }
    }
}
