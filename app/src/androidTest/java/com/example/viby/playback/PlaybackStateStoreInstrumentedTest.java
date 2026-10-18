package com.example.viby.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class PlaybackStateStoreInstrumentedTest {

    private IsolatedPreferencesContext context;
    private ExoPlayer source;
    private ExoPlayer restored;

    @Before
    public void setUp() {
        context = new IsolatedPreferencesContext(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
    }

    @After
    public void tearDown() {
        runOnMain(() -> {
            if (source != null) {
                source.release();
            }
            if (restored != null) {
                restored.release();
            }
        });
        context.preferences().edit().clear().commit();
    }

    @Test
    public void queueMetadataPositionAndModesRoundTrip() throws Exception {
        File first = temporaryAudio("first");
        File second = temporaryAudio("second");
        runOnMain(() -> {
            source = new ExoPlayer.Builder(context).build();
            source.setMediaItems(java.util.Arrays.asList(
                    item("one", first, "First", "Artist A", 1_000L),
                    item("two", second, "Second", "Artist B", 2_000L)), 1, 321L);
            source.setRepeatMode(Player.REPEAT_MODE_ALL);
            source.setShuffleModeEnabled(true);
            PlaybackStateStore.saveQueueAndSettings(context, source);

            restored = new ExoPlayer.Builder(context).build();
            assertTrue(PlaybackStateStore.restore(context, restored));
            assertEquals(2, restored.getMediaItemCount());
            assertEquals(1, restored.getCurrentMediaItemIndex());
            assertEquals(321L, restored.getCurrentPosition());
            assertEquals(Player.REPEAT_MODE_ALL, restored.getRepeatMode());
            assertTrue(restored.getShuffleModeEnabled());
            assertEquals("two", restored.getMediaItemAt(1).mediaId);
            assertEquals("Second", restored.getMediaItemAt(1)
                    .mediaMetadata.title.toString());
            assertEquals("Artist B", restored.getMediaItemAt(1)
                    .mediaMetadata.artist.toString());
            assertEquals(Long.valueOf(2_000L), restored.getMediaItemAt(1)
                    .mediaMetadata.durationMs);
        });
    }

    @Test
    public void restoreSkipsDeletedFilesAndKeepsRemainingQueueUsable() throws Exception {
        File first = temporaryAudio("kept");
        File deleted = temporaryAudio("deleted");
        File third = temporaryAudio("third");
        runOnMain(() -> {
            source = new ExoPlayer.Builder(context).build();
            source.setMediaItems(java.util.Arrays.asList(
                    item("one", first, "One", null, null),
                    item("gone", deleted, "Gone", null, null),
                    item("three", third, "Three", null, null)), 1, 0L);
            PlaybackStateStore.saveQueueAndSettings(context, source);
        });
        assertTrue(deleted.delete());
        runOnMain(() -> {
            restored = new ExoPlayer.Builder(context).build();
            assertTrue(PlaybackStateStore.restore(context, restored));
            assertEquals(2, restored.getMediaItemCount());
            assertEquals("one", restored.getMediaItemAt(0).mediaId);
            assertEquals("three", restored.getMediaItemAt(1).mediaId);
            assertEquals(1, restored.getCurrentMediaItemIndex());
        });
    }

    @Test
    public void malformedSavedQueueIsRejectedAndCleared() {
        context.preferences().edit().putString("queue", "not-json").commit();
        runOnMain(() -> {
            restored = new ExoPlayer.Builder(context).build();
            assertFalse(PlaybackStateStore.restore(context, restored));
        });
        assertFalse(context.preferences().contains("queue"));
    }

    private File temporaryAudio(String name) throws Exception {
        File file = File.createTempFile("viby-" + name, ".m4a", context.getCacheDir());
        file.deleteOnExit();
        return file;
    }

    private static MediaItem item(String id, File file, String title, String artist,
                                  Long duration) {
        MediaMetadata.Builder metadata = new MediaMetadata.Builder().setTitle(title);
        if (artist != null) {
            metadata.setArtist(artist);
        }
        if (duration != null) {
            metadata.setDurationMs(duration);
        }
        return new MediaItem.Builder()
                .setMediaId(id)
                .setUri(Uri.fromFile(file))
                .setMediaMetadata(metadata.build())
                .build();
    }

    private static void runOnMain(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                action.run();
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        if (failure.get() instanceof AssertionError) {
            throw (AssertionError) failure.get();
        }
        if (failure.get() != null) {
            throw new RuntimeException(failure.get());
        }
    }

    private static final class IsolatedPreferencesContext extends ContextWrapper {
        private static final String NAME = "viby_playback_state_instrumented_test";

        IsolatedPreferencesContext(Context base) {
            super(base);
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            return getBaseContext().getSharedPreferences(NAME, mode);
        }

        SharedPreferences preferences() {
            return getSharedPreferences(NAME, MODE_PRIVATE);
        }
    }
}
