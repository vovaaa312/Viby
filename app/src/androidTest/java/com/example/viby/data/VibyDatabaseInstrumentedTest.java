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
