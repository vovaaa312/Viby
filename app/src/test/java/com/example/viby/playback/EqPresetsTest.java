package com.example.viby.playback;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EqPresetsTest {

    @Test
    public void frequencyGridMatchesAimpTwentyBandLayout() {
        assertEquals(20, EqPresets.FREQS_HZ.length);
        assertEquals(31f, EqPresets.FREQS_HZ[0], 0f);
        assertEquals(1000f, EqPresets.FREQS_HZ[10], 0f);
        assertEquals(22000f, EqPresets.FREQS_HZ[19], 0f);
        for (int i = 1; i < EqPresets.FREQS_HZ.length; i++) {
            assertTrue(EqPresets.FREQS_HZ[i] > EqPresets.FREQS_HZ[i - 1]);
        }
    }

    @Test
    public void everyBuiltInPresetMigratesToTwentyFiniteValues() {
        for (String name : EqPresets.NAMES) {
            float[] curve = EqPresets.curve(name);
            assertEquals(name, 20, curve.length);
            for (float gain : curve) {
                assertTrue(name, !Float.isNaN(gain) && !Float.isInfinite(gain));
                assertTrue(name, gain >= -EqFx.MAX_GAIN_DB && gain <= EqFx.MAX_GAIN_DB);
            }
        }
    }

    @Test
    public void resamplingOnSameGridDoesNotChangeCurve() {
        float[] frequencies = {31f, 100f, 1000f, 22000f};
        float[] curve = {-4f, 2.5f, 8f, -1f};
        assertArrayEquals(curve,
                EqPresets.resample(curve, frequencies, frequencies), 0.0001f);
    }

    @Test
    public void legacyMigrationPreservesOuterValues() {
        float[] legacy = {7f, 6f, 5f, 4f, 3f, 2f, 1f, 0f, -1f, -2f};
        float[] migrated = EqPresets.migrateLegacyCurve(legacy);
        assertEquals(legacy[0], migrated[0], 0.0001f);
        assertEquals(legacy[legacy.length - 1], migrated[migrated.length - 1], 0.0001f);
    }
}
