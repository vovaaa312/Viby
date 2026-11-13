package com.example.viby.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Keeps a local playlist aligned with the order returned by YouTube. One rule for every
 * path (download, refresh, sync, "original order" sort), so the playlist always reads:
 * <ol>
 *     <li>tracks sent here from Viby that YouTube does not have (yet), in their local order;</li>
 *     <li>every track YouTube has, exactly in YouTube's order;</li>
 *     <li>local-only tracks and tracks removed on YouTube, in their local order.</li>
 * </ol>
 * A sent track that reaches YouTube stops being special and simply follows YouTube, so
 * Viby and YouTube never disagree about it.
 */
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
     * Puts the tracks in the order described on the class and rewrites their positions.
     * Tracks must be passed in their current local order.
     */
    public static void arrange(List<Track> tracks) {
        sort(tracks);
        for (int i = 0; i < tracks.size(); i++) {
            tracks.get(i).position = i;
        }
    }

    /**
     * Sorts like {@link #arrange} without touching positions. Tracks YouTube knows lose
     * their "sent from Viby" mark here, since from now on YouTube decides their place.
     */
    public static void sort(List<Track> tracks) {
        List<Track> pinned = new ArrayList<>();
        List<Track> onYoutube = new ArrayList<>();
        List<Track> rest = new ArrayList<>();
        for (Track track : tracks) {
            if (track.youtubePosition != null) {
                track.addedInViby = false;
                onYoutube.add(track);
            } else if (track.addedInViby) {
                pinned.add(track);
            } else {
                rest.add(track);
            }
        }
        onYoutube.sort(Comparator.comparingInt((Track track) -> track.youtubePosition)
                .thenComparingLong(track -> track.id));
        tracks.clear();
        tracks.addAll(pinned);
        tracks.addAll(onYoutube);
        tracks.addAll(rest);
    }
}
