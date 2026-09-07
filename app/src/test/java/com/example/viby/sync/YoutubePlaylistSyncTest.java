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
