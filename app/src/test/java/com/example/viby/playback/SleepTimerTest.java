package com.example.viby.playback;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SleepTimerTest {

    @Test
    public void volumeStaysFullUntilTheFadeThenFallsLinearlyToSilence() {
        long deadline = 1_000_000L;

        assertEquals(1f, SleepTimer.fadeFactor(deadline, deadline - 60_000L), 0f);
        assertEquals(1f, SleepTimer.fadeFactor(deadline, deadline - SleepTimer.FADE_MS), 0f);
        assertEquals(0.5f, SleepTimer.fadeFactor(deadline,
                deadline - SleepTimer.FADE_MS / 2), 0.001f);
        assertEquals(0f, SleepTimer.fadeFactor(deadline, deadline), 0f);
        assertEquals(0f, SleepTimer.fadeFactor(deadline, deadline + 5_000L), 0f);
    }

    @Test
    public void secondsLeftRoundUpSoTheLastSecondIsStillShown() {
        long deadline = 90_000L;

        assertEquals(90, SleepTimer.secondsLeft(deadline, 0));
        assertEquals(90, SleepTimer.secondsLeft(deadline, 500L));
        assertEquals(1, SleepTimer.secondsLeft(deadline, deadline - 1L));
        assertEquals(0, SleepTimer.secondsLeft(deadline, deadline));
    }

    @Test
    public void formatShowsHoursOnlyWhenNeeded() {
        assertEquals("0:45", SleepTimer.format(45));
        assertEquals("25:00", SleepTimer.format(25 * 60));
        assertEquals("1:05:30", SleepTimer.format(3600 + 5 * 60 + 30));
    }
}
