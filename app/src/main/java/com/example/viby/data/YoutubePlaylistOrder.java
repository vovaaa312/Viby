package com.example.viby.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Keeps a local playlist aligned with the order returned by YouTube. */
public final class YoutubePlaylistOrder {

    private YoutubePlaylistOrder() {
    }

    /**
     * Refreshes source positions and re-aligns the playlist with YouTube (see
     * {@link #arrange}). Tracks must be passed in their current local order.
     */
    public static void apply(List<Track> tracks, Map<String, Integer> sourcePositions) {
        for (Track track : tracks) {
            track.youtubePosition = track.videoId != null
                    ? sourcePositions.get(track.videoId) : null;
        }
        arrange(tracks);
    }

    /**
     * Re-aligns a playlist with YouTube without moving tracks the user sent here from Viby:
     * those keep their current slot (usually the top), and every other track fills the
     * remaining slots in YouTube order, with local-only or removed tracks at the end.
     * Tracks must be passed in their current local order; positions are rewritten.
     */
    public static void arrange(List<Track> tracks) {
        List<Track> movable = new ArrayList<>();
        for (Track track : tracks) {
            if (!track.addedInViby) {
                movable.add(track);
            }
        }
        sort(movable);
        Iterator<Track> next = movable.iterator();
        List<Track> arranged = new ArrayList<>(tracks.size());
        for (Track track : tracks) {
            arranged.add(track.addedInViby ? track : next.next());
        }
        tracks.clear();
        tracks.addAll(arranged);
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
