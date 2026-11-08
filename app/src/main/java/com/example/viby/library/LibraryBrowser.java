package com.example.viby.library;

import androidx.annotation.Nullable;

import com.example.viby.data.Track;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pure filtering, sorting and grouping behind the library screen. */
public final class LibraryBrowser {

    public enum Tab { TRACKS, ARTISTS, ALBUMS, FOLDERS }

    public enum Filter {
        ALL, FAVORITES, RECENTLY_ADDED, RECENTLY_PLAYED, NOT_DOWNLOADED, FAILED
    }

    public enum Sort {
        /** Natural order of the filter: newest for "recent", otherwise by title. */
        DEFAULT, TITLE, ARTIST, ALBUM, DATE_ADDED, LAST_PLAYED, PLAY_COUNT, DURATION
    }

    public static final long RECENTLY_ADDED_WINDOW_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final String TOPIC_SUFFIX = " - topic";

    public static final class Group {
        public final String key;
        public final String title;
        public final List<Track> tracks = new ArrayList<>();
        public long durationMs;

        Group(String key, String title) {
            this.key = key;
            this.title = title;
        }

        @Nullable
        public String firstThumbnail() {
            for (Track track : tracks) {
                if (track.thumbnailUrl != null) {
                    return track.thumbnailUrl;
                }
            }
            return null;
        }
    }

    private LibraryBrowser() {
    }

    /**
     * A file copied into several playlists is one song: keep the first row per media file
     * (or per YouTube video for placeholders). Favorite and play statistics are already
     * written to every row sharing the file, so any row is representative.
     */
    public static List<Track> distinct(List<Track> tracks) {
        Map<String, Track> unique = new LinkedHashMap<>();
        for (Track track : tracks) {
            String key = identity(track);
            Track kept = unique.get(key);
            if (kept == null) {
                unique.put(key, track);
            } else if (!kept.downloaded && track.downloaded) {
                unique.put(key, track);
            }
        }
        return new ArrayList<>(unique.values());
    }

    public static String identity(Track track) {
        if (track.downloaded && track.filePath != null && !track.filePath.isEmpty()) {
            return "f:" + track.filePath;
        }
        if (track.videoId != null && !track.videoId.isEmpty()) {
            return "v:" + track.videoId;
        }
        return "id:" + track.id;
    }

    public static List<Track> filter(List<Track> tracks, Filter filter,
                                     @Nullable String query, long now) {
        String needle = query != null ? query.trim().toLowerCase(Locale.getDefault()) : "";
        List<Track> result = new ArrayList<>();
        for (Track track : tracks) {
            if (matchesFilter(track, filter, now) && matchesQuery(track, needle)) {
                result.add(track);
            }
        }
        return result;
    }

    static boolean matchesFilter(Track track, Filter filter, long now) {
        switch (filter) {
            case FAVORITES:
                return track.favorite;
            case RECENTLY_ADDED:
                return track.createdAt > 0 && now - track.createdAt <= RECENTLY_ADDED_WINDOW_MS;
            case RECENTLY_PLAYED:
                return track.lastPlayedAt > 0;
            case NOT_DOWNLOADED:
                return !track.downloaded && !isFailed(track);
            case FAILED:
                return isFailed(track);
            case ALL:
            default:
                return true;
        }
    }

    public static boolean isFailed(Track track) {
        return !track.downloaded
                && (Track.DOWNLOAD_FAILED_TEMPORARY.equals(track.downloadState)
                || Track.DOWNLOAD_FAILED_PERMANENT.equals(track.downloadState));
    }

    static boolean matchesQuery(Track track, String needle) {
        if (needle.isEmpty()) {
            return true;
        }
        return contains(track.title, needle) || contains(track.uploader, needle)
                || contains(track.album, needle) || contains(track.playlistName, needle);
    }

    private static boolean contains(@Nullable String text, String needle) {
        return text != null && text.toLowerCase(Locale.getDefault()).contains(needle);
    }

    public static List<Track> sort(List<Track> tracks, Sort sort, Filter filter,
                                   boolean reverse) {
        List<Track> sorted = new ArrayList<>(tracks);
        Sort effective = sort;
        if (effective == Sort.DEFAULT) {
            if (filter == Filter.RECENTLY_ADDED) {
                effective = Sort.DATE_ADDED;
                reverse = !reverse; // newest first
            } else if (filter == Filter.RECENTLY_PLAYED) {
                effective = Sort.LAST_PLAYED;
                reverse = !reverse;
            } else {
                effective = Sort.TITLE;
            }
        }
        Comparator<Track> byTitle = (a, b) -> compareText(a.title, b.title);
        Comparator<Track> comparator;
        switch (effective) {
            case ARTIST:
                comparator = ((Comparator<Track>) (a, b) ->
                        compareText(artistName(a), artistName(b))).thenComparing(byTitle);
                break;
            case ALBUM:
                comparator = ((Comparator<Track>) (a, b) ->
                        compareText(a.album, b.album)).thenComparing(byTitle);
                break;
            case DATE_ADDED:
                comparator = Comparator.comparingLong((Track t) -> t.createdAt)
                        .thenComparing(byTitle);
                break;
            case LAST_PLAYED:
                comparator = Comparator.comparingLong((Track t) -> t.lastPlayedAt)
                        .thenComparing(byTitle);
                break;
            case PLAY_COUNT:
                comparator = Comparator.comparingInt((Track t) -> t.playCount)
                        .thenComparing(byTitle);
                break;
            case DURATION:
                comparator = Comparator.comparingLong((Track t) -> t.durationMs)
                        .thenComparing(byTitle);
                break;
            case TITLE:
            default:
                comparator = byTitle;
                break;
        }
        Collections.sort(sorted, comparator);
        if (reverse) {
            Collections.reverse(sorted);
        }
        return sorted;
    }

    /** Text compare with empty values last, so "Unknown" never dominates the list top. */
    private static int compareText(@Nullable String a, @Nullable String b) {
        boolean aEmpty = a == null || a.trim().isEmpty();
        boolean bEmpty = b == null || b.trim().isEmpty();
        if (aEmpty || bEmpty) {
            return aEmpty == bEmpty ? 0 : (aEmpty ? 1 : -1);
        }
        return a.trim().compareToIgnoreCase(b.trim());
    }

    public static List<Group> group(List<Track> tracks, Tab tab, String unknownLabel) {
        Map<String, Group> groups = new LinkedHashMap<>();
        for (Track track : tracks) {
            String title = groupTitle(track, tab);
            String display = title != null ? title : unknownLabel;
            String key = display.toLowerCase(Locale.ROOT);
            Group group = groups.get(key);
            if (group == null) {
                group = new Group(key, display);
                groups.put(key, group);
            }
            group.tracks.add(track);
            group.durationMs += Math.max(0L, track.durationMs);
        }
        List<Group> result = new ArrayList<>(groups.values());
        Collections.sort(result, (a, b) -> {
            boolean aUnknown = a.title.equals(unknownLabel);
            boolean bUnknown = b.title.equals(unknownLabel);
            if (aUnknown != bUnknown) {
                return aUnknown ? 1 : -1;
            }
            return a.title.compareToIgnoreCase(b.title);
        });
        return result;
    }

    @Nullable
    static String groupTitle(Track track, Tab tab) {
        switch (tab) {
            case ARTISTS:
                return artistName(track);
            case ALBUMS:
                return track.album != null && !track.album.trim().isEmpty()
                        ? track.album.trim() : null;
            case FOLDERS:
                return folderName(track);
            case TRACKS:
            default:
                return null;
        }
    }

    /** YouTube auto-generated channels are named "Artist - Topic". */
    @Nullable
    public static String artistName(Track track) {
        if (track.uploader == null || track.uploader.trim().isEmpty()) {
            return null;
        }
        String artist = track.uploader.trim();
        if (artist.toLowerCase(Locale.ROOT).endsWith(TOPIC_SUFFIX)) {
            artist = artist.substring(0, artist.length() - TOPIC_SUFFIX.length()).trim();
        }
        return artist.isEmpty() ? null : artist;
    }

    /** Folder on disk that holds the file; placeholders belong to their playlist folder. */
    public static String folderName(Track track) {
        if (track.downloaded && track.filePath != null && !track.filePath.isEmpty()) {
            String parent = LibraryPaths.parentName(track.filePath);
            if (parent != null) {
                return parent;
            }
        }
        return track.playlistName;
    }

    /** Reads a persisted enum name, falling back when it is missing or from an older build. */
    public static <T extends Enum<T>> T parseEnum(Class<T> type, @Nullable String value,
                                                  T fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException unknown) {
            return fallback;
        }
    }
}
