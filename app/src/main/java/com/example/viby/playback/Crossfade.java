package com.example.viby.playback;

import android.content.Context;

/**
 * Smooth track change: the end of a track fades out and the start of the next one fades
 * in, so tracks do not cut in and out abruptly. Done with the volume of the one player,
 * so the two tracks do not overlap.
 */
public final class Crossfade {

    public static final String KEY_MS = "crossfade_ms";
    /** Presets offered first; any other length up to {@link #MAX_MS} can be picked. */
    public static final int[] PRESETS_MS = {0, 1_000, 3_000};
    public static final int MAX_MS = 12_000;

    private Crossfade() {
    }

    /** 0 means off. */
    public static int durationMs(Context context) {
        int stored = VolumeNormalization.prefs(context).getInt(KEY_MS, 0);
        return Math.max(0, Math.min(MAX_MS, stored));
    }

    public static void setDurationMs(Context context, int ms) {
        VolumeNormalization.prefs(context).edit()
                .putInt(KEY_MS, Math.max(0, Math.min(MAX_MS, ms))).apply();
    }

    /**
     * Volume multiplier, 0..1, at {@code positionMs} of a track lasting {@code durationMs}.
     * The fade-out happens only when another track follows, so the last track of the queue
     * and a single repeated track keep their natural ending.
     */
    public static float factor(long positionMs, long durationMs, int fadeMs, boolean fadeOut) {
        if (fadeMs <= 0 || positionMs < 0) {
            return 1f;
        }
        // a very short track gets at most a third of its length per fade
        long fade = durationMs > 0 ? Math.min(fadeMs, durationMs / 3) : fadeMs;
        if (fade <= 0) {
            return 1f;
        }
        float in = curve((float) positionMs / fade);
        float out = 1f;
        if (fadeOut && durationMs > 0) {
            out = curve((float) (durationMs - positionMs) / fade);
        }
        return Math.min(in, out);
    }

    /** Equal-power shape: the loudness changes evenly instead of dropping at the end. */
    private static float curve(float t) {
        if (t >= 1f) {
            return 1f;
        }
        if (t <= 0f) {
            return 0f;
        }
        return (float) Math.sin(t * Math.PI / 2.0);
    }
}
