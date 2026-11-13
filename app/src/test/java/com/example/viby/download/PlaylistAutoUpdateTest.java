package com.example.viby.download;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlaylistAutoUpdateTest {

    @Test
    public void runsOnFirstLaunchAndAfterThePauseButNotOnAQuickReopen() {
        long now = 1_000_000_000L;
        assertTrue(PlaylistAutoUpdate.isDue(0L, now));
        assertFalse(PlaylistAutoUpdate.isDue(now - 60_000L, now));
        assertTrue(PlaylistAutoUpdate.isDue(now - PlaylistAutoUpdate.MIN_INTERVAL_MS, now));
        // a clock moved backwards must not block the check forever
        assertTrue(PlaylistAutoUpdate.isDue(now + 60_000L, now));
    }
}
