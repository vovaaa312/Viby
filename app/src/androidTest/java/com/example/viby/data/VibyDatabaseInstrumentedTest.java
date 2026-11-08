package com.example.viby.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@RunWith(AndroidJUnit4.class)
public class VibyDatabaseInstrumentedTest {

    private VibyDatabase database;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        database = Room.inMemoryDatabaseBuilder(context, VibyDatabase.class)
                .allowMainThreadQueries()
                .build();
    }

    @After
    public void tearDown() {
        database.close();
    }

    @Test
    public void trackQueriesKeepPlaylistOrderAndDistinguishPendingFiles() {
        Track later = track("playlist", "same-video", 4, true, "/music/shared.m4a");
        Track first = track("playlist", "first-video", 0, true, "/music/first.m4a");
        Track pending = track("playlist", "same-video", 2, false, "");
        pending.downloadState = Track.DOWNLOAD_FAILED_TEMPORARY;
        pending.downloadErrorCode = "NETWORK";
        pending.downloadErrorMessage = "Network interrupted";
        pending.downloadAttemptCount = 4;
        pending.downloadLastAttemptAt = 123L;
        pending.downloadFormat = "m4a";
        pending.downloadProgress = 42;
        Track copy = track("other", "same-video", 0, true, "/music/shared.m4a");

        long laterId = database.trackDao().insert(later);
        long firstId = database.trackDao().insert(first);
        long pendingId = database.trackDao().insert(pending);
        database.trackDao().insert(copy);

        List<Track> playlist = database.trackDao().getPlaylistSync("playlist");
        assertEquals(firstId, playlist.get(0).id);
        assertEquals(pendingId, playlist.get(1).id);
        assertEquals(laterId, playlist.get(2).id);
        assertEquals(5, database.trackDao().nextPosition("playlist"));
        assertTrue(database.trackDao().isDownloaded("playlist", "same-video"));
        assertNotNull(database.trackDao().getDownloadedByVideoIdSync(
                "playlist", "same-video"));
        assertEquals(2, database.trackDao()
                .getDownloadedByVideoIdGloballySync("same-video").size());
        assertEquals(2, database.trackDao().countFileReferences("/music/shared.m4a"));
        assertEquals(firstId, database.trackDao().getByIdSync(firstId).id);
        Track storedPending = database.trackDao().getByIdSync(pendingId);
        assertEquals(Track.DOWNLOAD_FAILED_TEMPORARY, storedPending.downloadState);
        assertEquals("NETWORK", storedPending.downloadErrorCode);
        assertEquals(4, storedPending.downloadAttemptCount);
        assertEquals(123L, storedPending.downloadLastAttemptAt);
        assertEquals("m4a", storedPending.downloadFormat);
        assertEquals(42, storedPending.downloadProgress);

        database.trackDao().deletePending("playlist", "same-video");

        assertEquals(2, database.trackDao().getPlaylistSync("playlist").size());
        assertNull(database.trackDao().getByIdSync(pendingId));
        assertNotNull(database.trackDao().getByIdSync(laterId));
    }

    @Test
    public void pendingDownloadUpdatesOnlySelectedQueueEntry() {
        PendingDownload first = pending("first", 20, false, "[\"a\"]");
        PendingDownload second = pending("second", 10, false, "[\"b\"]");
        long firstId = database.pendingDownloadDao().insert(first);
        long secondId = database.pendingDownloadDao().insert(second);

        List<PendingDownload> ordered = database.pendingDownloadDao().getAllSync();
        assertEquals(secondId, ordered.get(0).id);
        assertEquals(firstId, ordered.get(1).id);

        database.pendingDownloadDao().updatePaused(firstId, true);
        database.pendingDownloadDao().updateQueuePosition(firstId, 1);
        database.pendingDownloadDao().updateTrackOrder(firstId, "[\"c\",\"a\"]");

        ordered = database.pendingDownloadDao().getAllSync();
        assertEquals(firstId, ordered.get(0).id);
        assertTrue(ordered.get(0).paused);
        assertEquals("[\"c\",\"a\"]", ordered.get(0).trackOrderJson);
        assertFalse(ordered.get(1).paused);
        assertEquals("[\"b\"]", ordered.get(1).trackOrderJson);

        database.pendingDownloadDao().delete(firstId);
        assertEquals(1, database.pendingDownloadDao().getAllSync().size());
        assertEquals(secondId, database.pendingDownloadDao().getAllSync().get(0).id);
    }

    @Test
    public void playlistSourceUpsertPersistsSyncOwnershipAndCanBeRemoved() {
        PlaylistSource source = new PlaylistSource();
        source.playlistName = "favs";
        source.sourceUrl = "https://youtube.com/playlist?list=PL1";
        source.youtubePlaylistId = "PL1";
        source.youtubeSyncEnabled = true;
        source.youtubeOwnerChannelId = "channel";
        database.playlistSourceDao().upsert(source);

        PlaylistSource stored = database.playlistSourceDao().getSync("favs");
        assertEquals("PL1", stored.youtubePlaylistId);
        assertTrue(stored.youtubeSyncEnabled);
        assertEquals("channel", stored.youtubeOwnerChannelId);

        source.youtubeSyncEnabled = false;
        database.playlistSourceDao().upsert(source);
        assertFalse(database.playlistSourceDao().getSync("favs").youtubeSyncEnabled);

        database.playlistSourceDao().delete("favs");
        assertNull(database.playlistSourceDao().getSync("favs"));
    }

    @Test
    public void favoritesAndPlaysFollowEveryCopyOfTheSameFile() {
        long original = database.trackDao().insert(
                track("a", "song", 0, true, "/music/a/song.mp3"));
        long copy = database.trackDao().insert(
                track("b", "song", 0, true, "/music/a/song.mp3"));
        long other = database.trackDao().insert(
                track("a", "other", 1, true, "/music/a/other.mp3"));
        Track pendingCopy = track("c", "song", 0, false, "");
        long pending = database.trackDao().insert(pendingCopy);

        Track source = database.trackDao().getByIdSync(original);
        database.trackDao().setFavorite(source.id, source.filePath, source.videoId, true);
        database.trackDao().markPlayed(copy, 500L);
        database.trackDao().markPlayed(copy, 900L);

        assertTrue(database.trackDao().getByIdSync(copy).favorite);
        assertTrue(database.trackDao().getByIdSync(pending).favorite);
        assertFalse(database.trackDao().getByIdSync(other).favorite);
        assertEquals(2, database.trackDao().getByIdSync(original).playCount);
        assertEquals(900L, database.trackDao().getByIdSync(original).lastPlayedAt);
        assertEquals(0, database.trackDao().getByIdSync(other).playCount);
        // pending rows have no file, so they never collect plays through filePath = ''
        assertEquals(0, database.trackDao().getByIdSync(pending).playCount);
    }

    @Test
    public void relinkMissingAndMetadataQueriesUpdateLibraryState() {
        long id = database.trackDao().insert(track("a", "song", 0, true, "/old/song.mp3"));

        assertEquals(1, database.trackDao().getUnscannedSync(10).size());
        database.trackDao().updateScannedMetadata("/old/song.mp3", "Album");
        assertTrue(database.trackDao().getUnscannedSync(10).isEmpty());
        assertEquals("Album", database.trackDao().getByIdSync(id).album);

        database.trackDao().markFileMissing(id, Track.DOWNLOAD_WAITING, null, null,
                "https://www.youtube.com/watch?v=song");
        Track missing = database.trackDao().getByIdSync(id);
        assertFalse(missing.downloaded);
        assertEquals(Track.DOWNLOAD_WAITING, missing.downloadState);
        assertEquals("https://www.youtube.com/watch?v=song", missing.sourceUrl);

        database.trackDao().relinkFile(id, "/new/song.mp3");
        Track relinked = database.trackDao().getByIdSync(id);
        assertTrue(relinked.downloaded);
        assertEquals("/new/song.mp3", relinked.filePath);
        assertEquals(Track.DOWNLOAD_DOWNLOADED, relinked.downloadState);
        assertEquals(1, database.trackDao().getReferencedFilePathsSync().size());
        assertEquals(1, database.trackDao().getByIdsSync(
                java.util.Collections.singletonList(id)).size());
    }

    private static Track track(String playlist, String videoId, int position,
                               boolean downloaded, String filePath) {
        Track track = new Track();
        track.playlistName = playlist;
        track.videoId = videoId;
        track.title = videoId;
        track.position = position;
        track.downloaded = downloaded;
        track.filePath = filePath;
        return track;
    }

    private static PendingDownload pending(String name, long position,
                                           boolean paused, String order) {
        PendingDownload pending = new PendingDownload();
        pending.url = "https://example/" + name;
        pending.playlistName = name;
        pending.isPlaylist = true;
        pending.queuePosition = position;
        pending.paused = paused;
        pending.trackOrderJson = order;
        return pending;
    }
}
