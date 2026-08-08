package com.example.viby.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class YoutubePlaylistOrderTest {

    @Test
    public void applyPlacesYouTubeTracksFirstAndKeepsLocalTracks() {
        Track firstLocal = track(1, "first", 0);
        Track removed = track(2, "removed", 1);
        Track newest = track(3, "newest", 2);
        Track local = track(4, null, 3);
        List<Track> tracks = new ArrayList<>(
                java.util.Arrays.asList(firstLocal, removed, newest, local));

        Map<String, Integer> sourcePositions = new HashMap<>();
        sourcePositions.put("newest", 0);
        sourcePositions.put("first", 1);

        YoutubePlaylistOrder.apply(tracks, sourcePositions);

        assertEquals(3, tracks.get(0).id);
        assertEquals(1, tracks.get(1).id);
        assertEquals(2, tracks.get(2).id);
        assertEquals(4, tracks.get(3).id);
        assertEquals(Integer.valueOf(0), tracks.get(0).youtubePosition);
        assertEquals(Integer.valueOf(1), tracks.get(1).youtubePosition);
        assertNull(tracks.get(2).youtubePosition);
        for (int i = 0; i < tracks.size(); i++) {
            assertEquals(i, tracks.get(i).position);
        }
    }

    @Test
    public void sortRestoresStoredYouTubeOrderAfterAnotherSort() {
        Track older = track(1, "older", 0);
        older.youtubePosition = 1;
        Track newer = track(2, "newer", 1);
        newer.youtubePosition = 0;
        List<Track> tracks = new ArrayList<>(java.util.Arrays.asList(older, newer));

        YoutubePlaylistOrder.sort(tracks);

        assertEquals(2, tracks.get(0).id);
        assertEquals(1, tracks.get(1).id);
    }

    private static Track track(long id, String videoId, int position) {
        Track track = new Track();
        track.id = id;
        track.videoId = videoId;
        track.position = position;
        return track;
    }
}
