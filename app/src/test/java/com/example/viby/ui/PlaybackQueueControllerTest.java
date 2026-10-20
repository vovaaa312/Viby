package com.example.viby.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.viby.data.Track;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class PlaybackQueueControllerTest {

    @Test
    public void prefixComparisonDetectsAppendAndReorderCases() {
        List<Track> tracks = Arrays.asList(track(1), track(2), track(3));

        assertTrue(PlaybackQueueController.queuePrefixMatches(
                tracks, Arrays.asList("1", "2")));
        assertFalse(PlaybackQueueController.queuePrefixMatches(
                tracks, Arrays.asList("2", "1")));
        assertFalse(PlaybackQueueController.queuePrefixMatches(
                tracks, Arrays.asList("1", "2", "3", "4")));
    }

    @Test
    public void playablePositionUsesTrackIdAndFallsBackToStart() {
        List<Track> tracks = Arrays.asList(track(5), track(9));

        assertEquals(1, PlaybackQueueController.playablePosition(tracks, 9));
        assertEquals(0, PlaybackQueueController.playablePosition(tracks, 99));
        assertEquals(0, PlaybackQueueController.playablePosition(
                Collections.emptyList(), 5));
    }

    private static Track track(long id) {
        Track track = new Track();
        track.id = id;
        return track;
    }
}
