package com.example.viby.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CrossfadeTest {

    private static final long TRACK = 200_000L;

    @Test
    public void offOrMidTrackKeepsFullVolume() {
        assertEquals(1f, Crossfade.factor(1_000L, TRACK, 0, true), 0f);
        assertEquals(1f, Crossfade.factor(100_000L, TRACK, 3_000, true), 0f);
    }

    @Test
    public void startFadesInAndEndFadesOut() {
        assertEquals(0f, Crossfade.factor(0L, TRACK, 3_000, true), 0f);
        float early = Crossfade.factor(500L, TRACK, 3_000, true);
        float later = Crossfade.factor(2_000L, TRACK, 3_000, true);
        assertTrue(early > 0f && early < later && later < 1f);
        assertEquals(1f, Crossfade.factor(3_000L, TRACK, 3_000, true), 0f);

        float fading = Crossfade.factor(TRACK - 1_000L, TRACK, 3_000, true);
        assertTrue(fading > 0f && fading < 1f);
        assertEquals(0f, Crossfade.factor(TRACK, TRACK, 3_000, true), 0f);
    }

    @Test
    public void lastTrackKeepsItsNaturalEnding() {
        assertEquals(1f, Crossfade.factor(TRACK - 500L, TRACK, 3_000, false), 0f);
    }

    @Test
    public void shortTrackGetsAtMostAThirdPerFade() {
        // a 6 s jingle with a 3 s setting fades for 2 s at each end
        assertEquals(1f, Crossfade.factor(2_000L, 6_000L, 3_000, true), 0f);
        assertEquals(1f, Crossfade.factor(4_000L, 6_000L, 3_000, true), 0f);
        assertTrue(Crossfade.factor(4_500L, 6_000L, 3_000, true) < 1f);
    }
}
