package com.example.viby.library;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.viby.data.Track;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Library filtering, sorting, grouping, saved views and M3U export. */
public class LibraryBrowserTest {

    private static final long NOW = 100L * 24L * 60L * 60L * 1000L;
    private static final long DAY = 24L * 60L * 60L * 1000L;

    @Test
    public void distinctCollapsesPlaylistCopiesOfOneFile() {
        Track original = track(1, "Song", "A", "/m/Mix/song.mp3");
        Track copy = track(2, "Song", "A", "/m/Mix/song.mp3");
        copy.playlistName = "Other";
        Track pendingSameVideo = pending(3, "Song", "vid");
        Track pendingCopy = pending(4, "Song", "vid");

        List<Track> result = LibraryBrowser.distinct(
                Arrays.asList(original, copy, pendingSameVideo, pendingCopy));

        assertEquals(Arrays.asList(original, pendingSameVideo), result);
    }

    @Test
    public void filtersSelectFavoritesRecentStatesAndErrors() {
        Track favorite = track(1, "Fav", "A", "/m/a.mp3");
        favorite.favorite = true;
        Track recent = track(2, "New", "A", "/m/b.mp3");
        recent.createdAt = NOW - DAY;
        Track old = track(3, "Old", "A", "/m/c.mp3");
        old.createdAt = NOW - 60 * DAY;
        old.lastPlayedAt = NOW - DAY;
        Track waiting = pending(4, "Waiting", "w");
        Track failed = pending(5, "Failed", "f");
        failed.downloadState = Track.DOWNLOAD_FAILED_PERMANENT;
        List<Track> all = Arrays.asList(favorite, recent, old, waiting, failed);

        assertEquals(Arrays.asList(favorite), filter(all, LibraryBrowser.Filter.FAVORITES));
        assertEquals(Arrays.asList(recent),
                filter(all, LibraryBrowser.Filter.RECENTLY_ADDED));
        assertEquals(Arrays.asList(old), filter(all, LibraryBrowser.Filter.RECENTLY_PLAYED));
        assertEquals(Arrays.asList(waiting),
                filter(all, LibraryBrowser.Filter.NOT_DOWNLOADED));
        assertEquals(Arrays.asList(failed), filter(all, LibraryBrowser.Filter.FAILED));
        assertEquals(5, filter(all, LibraryBrowser.Filter.ALL).size());
    }

    @Test
    public void searchMatchesTitleArtistAlbumAndPlaylist() {
        Track byTitle = track(1, "Midnight City", "M83", "/m/a.mp3");
        Track byAlbum = track(2, "Other", "X", "/m/b.mp3");
        byAlbum.album = "Midnight Memories";
        Track byPlaylist = track(3, "Third", "Y", "/m/c.mp3");
        byPlaylist.playlistName = "midnight drive";
        Track none = track(4, "Day", "Z", "/m/d.mp3");

        List<Track> result = LibraryBrowser.filter(
                Arrays.asList(byTitle, byAlbum, byPlaylist, none),
                LibraryBrowser.Filter.ALL, "  MIDNIGHT ", NOW);

        assertEquals(Arrays.asList(byTitle, byAlbum, byPlaylist), result);
    }

    @Test
    public void defaultSortFollowsFilterAndReverseFlipsIt() {
        Track a = track(1, "b-title", "A", "/m/a.mp3");
        a.createdAt = 10;
        a.lastPlayedAt = 300;
        Track b = track(2, "a-title", "B", "/m/b.mp3");
        b.createdAt = 30;
        b.lastPlayedAt = 100;
        List<Track> tracks = Arrays.asList(a, b);

        assertEquals(Arrays.asList(b, a), LibraryBrowser.sort(tracks,
                LibraryBrowser.Sort.DEFAULT, LibraryBrowser.Filter.ALL, false));
        assertEquals(Arrays.asList(b, a), LibraryBrowser.sort(tracks,
                LibraryBrowser.Sort.DEFAULT, LibraryBrowser.Filter.RECENTLY_ADDED, false));
        assertEquals(Arrays.asList(a, b), LibraryBrowser.sort(tracks,
                LibraryBrowser.Sort.DEFAULT, LibraryBrowser.Filter.RECENTLY_PLAYED, false));
        assertEquals(Arrays.asList(a, b), LibraryBrowser.sort(tracks,
                LibraryBrowser.Sort.TITLE, LibraryBrowser.Filter.ALL, true));
    }

    @Test
    public void sortPlacesMissingValuesLastAndUsesTitleAsTieBreaker() {
        Track noArtist = track(1, "A", null, "/m/a.mp3");
        Track zed = track(2, "B", "Zed", "/m/b.mp3");
        Track abba2 = track(3, "Z", "abba", "/m/c.mp3");
        Track abba1 = track(4, "C", "ABBA", "/m/d.mp3");

        assertEquals(Arrays.asList(abba1, abba2, zed, noArtist), LibraryBrowser.sort(
                Arrays.asList(noArtist, zed, abba2, abba1), LibraryBrowser.Sort.ARTIST,
                LibraryBrowser.Filter.ALL, false));
    }

    @Test
    public void playCountSortOrdersAscendingUnlessReversed() {
        Track rare = track(1, "Rare", "A", "/m/a.mp3");
        rare.playCount = 1;
        Track often = track(2, "Often", "A", "/m/b.mp3");
        often.playCount = 9;
        assertEquals(Arrays.asList(often, rare), LibraryBrowser.sort(
                Arrays.asList(rare, often), LibraryBrowser.Sort.PLAY_COUNT,
                LibraryBrowser.Filter.ALL, true));
    }

    @Test
    public void groupingByArtistMergesTopicChannelsAndPutsUnknownLast() {
        Track topic = track(1, "One", "Daft Punk - Topic", "/m/a.mp3");
        topic.durationMs = 1000;
        Track plain = track(2, "Two", "daft punk", "/m/b.mp3");
        plain.durationMs = 2000;
        Track unknown = track(3, "Three", null, "/m/c.mp3");
        Track abba = track(4, "Four", "ABBA", "/m/d.mp3");

        List<LibraryBrowser.Group> groups = LibraryBrowser.group(
                Arrays.asList(topic, plain, unknown, abba), LibraryBrowser.Tab.ARTISTS,
                "Unknown");

        assertEquals(3, groups.size());
        assertEquals("ABBA", groups.get(0).title);
        assertEquals("Daft Punk", groups.get(1).title);
        assertEquals(2, groups.get(1).tracks.size());
        assertEquals(3000L, groups.get(1).durationMs);
        assertEquals("Unknown", groups.get(2).title);
    }

    @Test
    public void foldersComeFromFileLocationAndAlbumsFromTags() {
        Track shared = track(1, "One", "A", "/music/Mix/one.mp3");
        shared.playlistName = "Favorites copy";
        shared.album = "Discovery";
        Track pending = pending(2, "Two", "v");
        pending.playlistName = "Queue";

        List<LibraryBrowser.Group> folders = LibraryBrowser.group(
                Arrays.asList(shared, pending), LibraryBrowser.Tab.FOLDERS, "?");
        List<LibraryBrowser.Group> albums = LibraryBrowser.group(
                Arrays.asList(shared, pending), LibraryBrowser.Tab.ALBUMS, "No album");

        assertEquals("Mix", folders.get(0).title);
        assertEquals("Queue", folders.get(1).title);
        assertEquals("Discovery", albums.get(0).title);
        assertEquals("No album", albums.get(1).title);
    }

    @Test
    public void persistedEnumNamesFallBackWhenUnknown() {
        assertEquals(LibraryBrowser.Tab.ARTISTS, LibraryBrowser.parseEnum(
                LibraryBrowser.Tab.class, "ARTISTS", LibraryBrowser.Tab.TRACKS));
        assertEquals(LibraryBrowser.Tab.TRACKS, LibraryBrowser.parseEnum(
                LibraryBrowser.Tab.class, "PODCASTS", LibraryBrowser.Tab.TRACKS));
        assertEquals(LibraryBrowser.Sort.DEFAULT, LibraryBrowser.parseEnum(
                LibraryBrowser.Sort.class, null, LibraryBrowser.Sort.DEFAULT));
    }

    @Test
    public void m3uUsesRelativePathsAndYoutubeFallback() {
        File root = new File("/data/files/Music");
        Track local = track(1, "Song\nTitle", "Artist", "/data/files/Music/Mix/song.mp3");
        local.durationMs = 61_400L;
        Track online = pending(2, "Online", "vid");
        Track nowhere = pending(3, "Nowhere", null);

        String m3u = PlaylistExporter.toM3u("Mix", Arrays.asList(local, online, nowhere), root);

        assertEquals("#EXTM3U\n#PLAYLIST:Mix\n"
                + "#EXTINF:61,Artist - Song Title\nMix/song.mp3\n"
                + "#EXTINF:-1,Online\nhttps://www.youtube.com/watch?v=vid\n", m3u);
        assertEquals("a_b.m3u8", PlaylistExporter.fileName("a/b", "m3u8"));
    }

    // ----------------------------------------------------------------- helpers

    private static List<Track> filter(List<Track> tracks, LibraryBrowser.Filter filter) {
        return LibraryBrowser.filter(tracks, filter, "", NOW);
    }

    private static Track track(long id, String title, String artist, String path) {
        Track track = new Track();
        track.id = id;
        track.title = title;
        track.uploader = artist;
        track.filePath = path;
        track.playlistName = "Mix";
        track.downloaded = true;
        return track;
    }

    private static Track pending(long id, String title, String videoId) {
        Track track = track(id, title, null, "");
        track.downloaded = false;
        track.videoId = videoId;
        track.downloadState = Track.DOWNLOAD_WAITING;
        return track;
    }
}
