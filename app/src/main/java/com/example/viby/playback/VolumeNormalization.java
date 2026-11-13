package com.example.viby.playback;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

/**
 * Volume normalization settings and the gain rule: every track is moved towards one
 * target loudness, boosts are limited so the track does not clip, and the result is
 * clamped to a sane range.
 */
public final class VolumeNormalization {

    public static final String PREFS = "viby_playback";
    public static final String KEY_ENABLED = "normalization_enabled";
    public static final String KEY_LEVEL = "normalization_level";

    /** Target loudness presets; NORMAL matches common streaming services. */
    public enum Level {
        QUIET(-18.0),
        NORMAL(-14.0),
        LOUD(-11.0);

        public final double targetLufs;

        Level(double targetLufs) {
            this.targetLufs = targetLufs;
        }
    }

    static final double MAX_CUT_DB = 20.0;
    static final double MAX_BOOST_DB = 9.0;
    /** The limiter of the boost stage tolerates a little over the original peak. */
    static final double PEAK_HEADROOM_DB = 1.0;

    private VolumeNormalization() {
    }

    /**
     * Gain in dB that brings a track measured at {@code lufs} with sample peak
     * {@code peak} to {@code targetLufs}. Unknown loudness means no change.
     */
    public static double gainDb(@Nullable Float lufs, @Nullable Float peak, double targetLufs) {
        if (lufs == null || lufs.isNaN() || lufs.isInfinite()) {
            return 0.0;
        }
        double gain = targetLufs - lufs;
        if (gain > 0 && peak != null && peak > 0 && !peak.isNaN()) {
            double headroom = -20.0 * Math.log10(peak) + PEAK_HEADROOM_DB;
            gain = Math.min(gain, Math.max(0.0, headroom));
        }
        return Math.max(-MAX_CUT_DB, Math.min(MAX_BOOST_DB, gain));
    }

    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static Level level(Context context) {
        String stored = prefs(context).getString(KEY_LEVEL, Level.NORMAL.name());
        try {
            return Level.valueOf(stored);
        } catch (IllegalArgumentException unknown) {
            return Level.NORMAL;
        }
    }

    public static void setLevel(Context context, Level level) {
        prefs(context).edit().putString(KEY_LEVEL, level.name()).apply();
    }
}
