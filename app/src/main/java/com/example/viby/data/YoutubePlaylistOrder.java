package com.example.viby.data;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Keeps a local playlist aligned with the order returned by YouTube. */
public final class YoutubePlaylistOrder {

    private YoutubePlaylistOrder() {
    }

    /**
     * Refreshes source positions, sorts tracks found on YouTube first, and keeps
     * local or removed tracks at the end in their current relative order.
     */
    public static void apply(List<Track> tracks, Map<String, Integer> sourcePositions) {
        for (Track track : tracks) {
            track.youtubePosition = track.videoId != null
                    ? sourcePositions.get(track.videoId) : null;
        }
        sort(tracks);
        for (int i = 0; i < tracks.size(); i++) {
            tracks.get(i).position = i;
        }
    }

    /** Restores the last known original YouTube order without changing its metadata. */
    public static void sort(List<Track> tracks) {
        tracks.sort(Comparator
                .comparingInt((Track track) -> track.youtubePosition == null ? 1 : 0)
                .thenComparingInt(track -> track.youtubePosition != null
                        ? track.youtubePosition : track.position)
                .thenComparingLong(track -> track.id));
    }
}
