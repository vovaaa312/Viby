package com.example.viby.ui;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.pm.ActivityInfo;

import androidx.fragment.app.Fragment;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LyricsBottomSheetLifecycleInstrumentedTest {

    @Test
    public void lyricsSheetSurvivesActivityRecreation() {
        try (ActivityScenario<MainActivity> scenario =
                     ActivityScenario.launch(MainActivity.class)) {
            showLyricsSheet(scenario);
            assertLyricsSheetAttached(scenario);
            scenario.onActivity(activity -> activity.setRequestedOrientation(
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertLyricsSheetAttached(scenario);
            scenario.onActivity(activity -> activity.setRequestedOrientation(
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertLyricsSheetAttached(scenario);
        }
    }

    @Test
    public void lyricsSheetCanBeClosedAndOpenedAgain() {
        try (ActivityScenario<MainActivity> scenario =
                     ActivityScenario.launch(MainActivity.class)) {
            showLyricsSheet(scenario);
            scenario.onActivity(activity -> {
                Fragment fragment = activity.getSupportFragmentManager()
                        .findFragmentByTag("test_lyrics");
                assertNotNull(fragment);
                ((LyricsBottomSheetDialogFragment) fragment).dismissNow();
            });
            showLyricsSheet(scenario);
            assertLyricsSheetAttached(scenario);
        }
    }

    private static void showLyricsSheet(ActivityScenario<MainActivity> scenario) {
        scenario.onActivity(activity -> {
            MediaItem item = new MediaItem.Builder()
                    .setMediaId("local-test-track")
                    .setMediaMetadata(new MediaMetadata.Builder()
                            .setTitle("Lifecycle test")
                            .build())
                    .build();
            LyricsBottomSheetDialogFragment.newInstance(item)
                    .showNow(activity.getSupportFragmentManager(), "test_lyrics");
        });
    }

    private static void assertLyricsSheetAttached(
            ActivityScenario<MainActivity> scenario) {
        scenario.onActivity(activity -> {
            activity.getSupportFragmentManager().executePendingTransactions();
            Fragment fragment = activity.getSupportFragmentManager()
                    .findFragmentByTag("test_lyrics");
            assertNotNull(fragment);
            assertTrue(fragment instanceof LyricsBottomSheetDialogFragment);
            assertNotNull(fragment.getView());
        });
    }
}
