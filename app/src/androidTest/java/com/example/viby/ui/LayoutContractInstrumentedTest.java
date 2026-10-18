package com.example.viby.ui;

import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.content.res.Configuration;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ContextThemeWrapper;

import androidx.annotation.IdRes;
import androidx.annotation.LayoutRes;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.viby.R;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class LayoutContractInstrumentedTest {

    @Test
    public void everyScreenAndRowInflatesInPortraitAndLandscape() {
        int[] layouts = {
                R.layout.activity_main,
                R.layout.activity_download,
                R.layout.activity_equalizer,
                R.layout.activity_youtube_login,
                R.layout.activity_youtube_playlists,
                R.layout.fragment_player,
                R.layout.fragment_queue,
                R.layout.fragment_lyrics,
                R.layout.fragment_playback_queue,
                R.layout.fragment_download_track_queue,
                R.layout.item_track,
                R.layout.item_playlist,
                R.layout.item_download,
                R.layout.item_download_track,
                R.layout.item_playback_queue,
                R.layout.item_yt_playlist,
        };
        for (int orientation : new int[]{
                Configuration.ORIENTATION_PORTRAIT,
                Configuration.ORIENTATION_LANDSCAPE}) {
            for (int layout : layouts) {
                assertNotNull("Could not inflate layout " + layout
                        + " in orientation " + orientation,
                        inflate(layout, orientation));
            }
        }
    }

    @Test
    public void playerLayoutHasEveryRequiredControlInBothOrientations() {
        int[] ids = {
                R.id.coverImage, R.id.trackTitle, R.id.trackArtist,
                R.id.waveform, R.id.positionText, R.id.durationText,
                R.id.playButton, R.id.shuffleButton, R.id.repeatButton,
                R.id.trackCounter, R.id.prevButton, R.id.nextButton,
                R.id.lyricsPullHandle,
        };
        assertIds(inflate(R.layout.fragment_player, Configuration.ORIENTATION_PORTRAIT), ids);
        assertIds(inflate(R.layout.fragment_player, Configuration.ORIENTATION_LANDSCAPE), ids);
    }

    @Test
    public void lyricsAndPlaylistLayoutsKeepTheirInteractionContracts() {
        assertIds(inflate(R.layout.fragment_lyrics, Configuration.ORIENTATION_PORTRAIT),
                R.id.lyricsProgress, R.id.lyricsStatus, R.id.lyricsSource,
                R.id.lyricsText, R.id.lyricsScroll, R.id.lyricsTitle);
        assertIds(inflate(R.layout.fragment_queue, Configuration.ORIENTATION_PORTRAIT),
                R.id.tracksList, R.id.playlistFastScroller, R.id.searchInput,
                R.id.addTracks, R.id.refreshPlaylist, R.id.miniPlayer,
                R.id.selectionBar, R.id.selectionCount, R.id.selectionMove,
                R.id.selectionDelete, R.id.selectionClose);
    }

    private static View inflate(@LayoutRes int layout, int orientation) {
        AtomicReference<View> result = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
                Configuration configuration = new Configuration(
                        base.getResources().getConfiguration());
                configuration.orientation = orientation;
                Context context = new ContextThemeWrapper(
                        base.createConfigurationContext(configuration), R.style.Theme_Viby);
                result.set(LayoutInflater.from(context).inflate(layout, null, false));
            } catch (RuntimeException error) {
                failure.set(error);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    private static void assertIds(View root, @IdRes int... ids) {
        for (int id : ids) {
            assertNotNull("Missing required view id " + id, root.findViewById(id));
        }
    }
}
