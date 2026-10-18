package com.example.viby.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.example.viby.data.Track;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class YoutubePlaylistSyncTest {

    @Test
    public void linksDuplicateVideosToDistinctPlaylistItems() {
        Track first = track(1, "same", 0);
        Track second = track(2, "same", 1);
        List<Track> tracks = new ArrayList<>(Arrays.asList(first, second));
        List<YoutubePlaylistApi.PlaylistItem> remote = Arrays.asList(
                item("item-new", "same", 0),
                item("item-old", "same", 1));

        int linked = YoutubePlaylistSync.linkTracks(tracks, remote, true);

        assertEquals(2, linked);
        assertEquals("item-new", tracks.get(0).youtubePlaylistItemId);
        assertEquals("item-old", tracks.get(1).youtubePlaylistItemId);
    }

    @Test
    public void preservesExactOccurrenceAcrossRemoteReordering() {
        Track older = track(1, "same", 0);
        older.youtubePlaylistItemId = "item-old";
        Track newer = track(2, "same", 1);
        newer.youtubePlaylistItemId = "item-new";
        List<Track> tracks = new ArrayList<>(Arrays.asList(older, newer));
        List<YoutubePlaylistApi.PlaylistItem> remote = Arrays.asList(
                item("item-new", "same", 0),
                item("item-old", "same", 1));

        YoutubePlaylistSync.linkTracks(tracks, remote, true);

        assertEquals(2, tracks.get(0).id);
        assertEquals("item-new", tracks.get(0).youtubePlaylistItemId);
        assertEquals(1, tracks.get(1).id);
        assertEquals("item-old", tracks.get(1).youtubePlaylistItemId);
    }

    @Test
    public void leavesLocalOnlyTrackUnlinked() {
        Track remoteTrack = track(1, "remote", 0);
        Track localTrack = track(2, "local", 1);
        List<Track> tracks = new ArrayList<>(Arrays.asList(remoteTrack, localTrack));

        YoutubePlaylistSync.linkTracks(tracks,
                Arrays.asList(item("item", "remote", 0)), true);

        assertEquals("item", tracks.get(0).youtubePlaylistItemId);
        assertNull(tracks.get(1).youtubePlaylistItemId);
        assertNull(tracks.get(1).youtubePosition);
    }

    @Test
    public void staleOccurrenceIdIsReplacedByMatchingVideoOccurrence() {
        Track track = track(1, "video", 0);
        track.youtubePlaylistItemId = "stale-item";
        List<Track> tracks = new ArrayList<>(Arrays.asList(track));

        YoutubePlaylistSync.linkTracks(tracks,
                Arrays.asList(item("new-item", "video", 4)), true);

        assertEquals("new-item", track.youtubePlaylistItemId);
        assertEquals(Integer.valueOf(4), track.youtubePosition);
    }

    @Test
    public void missingRemoteOccurrenceClearsOldLink() {
        Track track = track(1, "video", 0);
        track.youtubePlaylistItemId = "old-item";
        track.youtubePosition = 9;

        int linked = YoutubePlaylistSync.linkTracks(
                new ArrayList<>(Arrays.asList(track)), new ArrayList<>(), true);

        assertEquals(0, linked);
        assertNull(track.youtubePlaylistItemId);
        assertNull(track.youtubePosition);
    }

    @Test
    public void linkingWithoutReorderPreservesLocalListAndPositions() {
        Track first = track(1, "first", 10);
        Track second = track(2, "second", 20);
        List<Track> tracks = new ArrayList<>(Arrays.asList(first, second));

        YoutubePlaylistSync.linkTracks(tracks, Arrays.asList(
                item("second-item", "second", 0),
                item("first-item", "first", 1)), false);

        assertEquals(1, tracks.get(0).id);
        assertEquals(2, tracks.get(1).id);
        assertEquals(10, tracks.get(0).position);
        assertEquals(20, tracks.get(1).position);
    }

    private static Track track(long id, String videoId, int position) {
        Track track = new Track();
        track.id = id;
        track.videoId = videoId;
        track.position = position;
        return track;
    }

    private static YoutubePlaylistApi.PlaylistItem item(
            String id, String videoId, int position) {
        return new YoutubePlaylistApi.PlaylistItem(id, videoId, position);
    }
}
