package com.example.viby.playback;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Sleep timer state shared by the playback service (which acts on it) and the UI (which
 * shows the remaining time). Either a wall-clock deadline or "stop after this track".
 */
public final class SleepTimer {

    public static final String ACTION_SET = "com.example.viby.action.SET_SLEEP_TIMER";
    /** Seconds until playback stops; 0 cancels the timer. */
    public static final String EXTRA_SECONDS = "seconds";
    /** True to stop when the current track ends instead of after a time. */
    public static final String EXTRA_END_OF_TRACK = "end_of_track";

    /** Volume fades out over this time before the deadline. */
    public static final long FADE_MS = 15_000L;

    static final String KEY_DEADLINE = "sleep_deadline";
    static final String KEY_END_OF_TRACK = "sleep_end_of_track";
    static final String KEY_LAST_SECONDS = "sleep_last_seconds";
    /** What the time picker offers the first time. */
    static final int DEFAULT_SECONDS = 30 * 60;

    private SleepTimer() {
    }

    public static SharedPreferences prefs(Context context) {
        return VolumeNormalization.prefs(context);
    }

    /** Wall-clock time when playback stops, or 0. */
    public static long deadline(Context context) {
        long deadline = prefs(context).getLong(KEY_DEADLINE, 0L);
        return deadline > System.currentTimeMillis() ? deadline : 0L;
    }

    public static boolean isEndOfTrack(Context context) {
        return prefs(context).getBoolean(KEY_END_OF_TRACK, false);
    }

    public static boolean isActive(Context context) {
        return deadline(context) > 0 || isEndOfTrack(context);
    }

    /** Whole seconds left, rounded up, so "0:01" is shown until the very end. */
    public static long secondsLeft(long deadline, long now) {
        long left = deadline - now;
        return left <= 0 ? 0 : (left + 999L) / 1000L;
    }

    /** "1:05:30" with hours, otherwise "5:30". */
    public static String format(long seconds) {
        long h = seconds / 3600;
        long m = seconds % 3600 / 60;
        long s = seconds % 60;
        return h > 0
                ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s)
                : String.format(java.util.Locale.ROOT, "%d:%02d", m, s);
    }

    /** The duration picked last time, so the picker opens on it. */
    public static int lastSeconds(Context context) {
        return prefs(context).getInt(KEY_LAST_SECONDS, DEFAULT_SECONDS);
    }

    public static void setLastSeconds(Context context, int seconds) {
        prefs(context).edit().putInt(KEY_LAST_SECONDS, seconds).apply();
    }

    static void store(Context context, long deadline, boolean endOfTrack) {
        prefs(context).edit()
                .putLong(KEY_DEADLINE, deadline)
                .putBoolean(KEY_END_OF_TRACK, endOfTrack)
                .apply();
    }

    static void clear(Context context) {
        store(context, 0L, false);
    }

    /**
     * Volume multiplier while fading: 1 until {@link #FADE_MS} before the deadline,
     * then linearly down to 0 at the deadline.
     */
    static float fadeFactor(long deadline, long now) {
        long left = deadline - now;
        if (left >= FADE_MS) {
            return 1f;
        }
        if (left <= 0) {
            return 0f;
        }
        return left / (float) FADE_MS;
    }
}
