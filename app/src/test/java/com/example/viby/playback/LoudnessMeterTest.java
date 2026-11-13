package com.example.viby.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LoudnessMeterTest {

    private static final int RATE = 48_000;

    /** EBU Tech 3341 reference: a 1 kHz stereo sine at -23 dBFS measures -23 LUFS. */
    @Test
    public void referenceSineMeasuresMinus23Lufs() {
        LoudnessMeter meter = new LoudnessMeter(RATE, 2);
        feedSine(meter, 1000, dbToAmplitude(-23), 20.0);

        assertEquals(-23.0, meter.integratedLufs(), 0.1);
        assertEquals(dbToAmplitude(-23), meter.samplePeak(), 0.001);
    }

    @Test
    public void otherSampleRatesMeasureTheSame() {
        LoudnessMeter meter = new LoudnessMeter(44_100, 2);
        feedSine(meter, 1000, dbToAmplitude(-23), 10.0, 44_100);

        assertEquals(-23.0, meter.integratedLufs(), 0.1);
    }

    @Test
    public void silenceAndTooShortInputHaveNoLoudness() {
        LoudnessMeter silent = new LoudnessMeter(RATE, 2);
        silent.add(new float[RATE * 2 * 2], 0, RATE * 2);
        assertTrue(Double.isInfinite(silent.integratedLufs()));

        LoudnessMeter tooShort = new LoudnessMeter(RATE, 2);
        feedSine(tooShort, 1000, 0.5, 0.2);
        assertTrue(Double.isInfinite(tooShort.integratedLufs()));
    }

    /** Quiet intros and pauses must not drag the result down (relative gate). */
    @Test
    public void quietPassagesAreGatedOut() {
        LoudnessMeter meter = new LoudnessMeter(RATE, 2);
        feedSine(meter, 1000, dbToAmplitude(-20), 10.0);
        feedSine(meter, 1000, dbToAmplitude(-50), 10.0);

        assertEquals(-20.0, meter.integratedLufs(), 0.3);
    }

    @Test
    public void gainBringsTracksToTargetWithoutClipping() {
        double target = VolumeNormalization.Level.NORMAL.targetLufs; // -14

        // loud master: cut
        assertEquals(-6.0, VolumeNormalization.gainDb(-8f, 1f, target), 1e-9);
        // quiet track with headroom: full boost
        assertEquals(4.0, VolumeNormalization.gainDb(-18f, 0.25f, target), 1e-9);
        // quiet but peaky: boost limited to the peak headroom (+1 dB limiter margin)
        double limited = VolumeNormalization.gainDb(-20f, 0.9f, target);
        assertEquals(-20 * Math.log10(0.9) + VolumeNormalization.PEAK_HEADROOM_DB,
                limited, 1e-6);
        // extreme values are clamped
        assertEquals(VolumeNormalization.MAX_BOOST_DB,
                VolumeNormalization.gainDb(-40f, 0.01f, target), 1e-9);
        assertEquals(-VolumeNormalization.MAX_CUT_DB,
                VolumeNormalization.gainDb(10f, 1f, target), 1e-9);
        // unknown loudness: leave the track alone
        assertEquals(0.0, VolumeNormalization.gainDb(null, null, target), 1e-9);
        assertEquals(0.0, VolumeNormalization.gainDb(Float.NaN, 1f, target), 1e-9);
    }

    private static void feedSine(LoudnessMeter meter, double hz, double amplitude,
                                 double seconds) {
        feedSine(meter, hz, amplitude, seconds, RATE);
    }

    private static void feedSine(LoudnessMeter meter, double hz, double amplitude,
                                 double seconds, int rate) {
        int frames = (int) (rate * seconds);
        float[] chunk = new float[4096 * 2];
        int written = 0;
        while (written < frames) {
            int count = Math.min(4096, frames - written);
            for (int i = 0; i < count; i++) {
                float value = (float) (amplitude
                        * Math.sin(2 * Math.PI * hz * (written + i) / rate));
                chunk[i * 2] = value;
                chunk[i * 2 + 1] = value;
            }
            meter.add(chunk, 0, count);
            written += count;
        }
    }

    private static double dbToAmplitude(double db) {
        return Math.pow(10, db / 20);
    }
}
