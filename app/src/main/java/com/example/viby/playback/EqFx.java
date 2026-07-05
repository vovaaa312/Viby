package com.example.viby.playback;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.audiofx.Equalizer;
import android.util.Log;

import androidx.annotation.Nullable;

/**
 * Обёртка над системным {@link Equalizer}, привязанным к аудиосессии ExoPlayer.
 * Живёт, пока жив PlaybackService; настройки хранятся в SharedPreferences
 * и восстанавливаются при каждом старте сервиса.
 */
public final class EqFx {

    private static final String TAG = "EqFx";
    private static final String PREFS = "viby_eq";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_PRESET = "preset";
    private static final String KEY_LEVEL = "level_";

    public static final String PRESET_CUSTOM = "";

    @Nullable
    private static Equalizer equalizer;
    private static SharedPreferences prefs;

    private EqFx() {
    }

    public static synchronized void init(Context context, int audioSessionId) {
        release();
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            equalizer = new Equalizer(0, audioSessionId);
            applySaved();
        } catch (Exception e) {
            Log.w(TAG, "equalizer unavailable", e);
            equalizer = null;
        }
    }

    public static synchronized void release() {
        if (equalizer != null) {
            try {
                equalizer.release();
            } catch (Exception ignored) {
            }
            equalizer = null;
        }
    }

    public static synchronized boolean isAvailable() {
        return equalizer != null;
    }

    public static synchronized boolean isEnabled() {
        return prefs != null && prefs.getBoolean(KEY_ENABLED, false);
    }

    public static synchronized void setEnabled(boolean enabled) {
        if (prefs != null) {
            prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
        }
        if (equalizer != null) {
            equalizer.setEnabled(enabled);
        }
    }

    public static synchronized short getBandCount() {
        return equalizer != null ? equalizer.getNumberOfBands() : 0;
    }

    /** Центральная частота полосы в Гц. */
    public static synchronized int getCenterFreqHz(short band) {
        return equalizer != null ? equalizer.getCenterFreq(band) / 1000 : 0;
    }

    /** Диапазон уровней в миллибелах, например [-1500, 1500]. */
    public static synchronized short[] getLevelRange() {
        return equalizer != null ? equalizer.getBandLevelRange() : new short[]{0, 0};
    }

    public static synchronized short getBandLevel(short band) {
        return equalizer != null ? equalizer.getBandLevel(band) : 0;
    }

    public static synchronized void setBandLevel(short band, short levelMb) {
        if (equalizer == null) {
            return;
        }
        try {
            equalizer.setBandLevel(band, levelMb);
        } catch (Exception e) {
            Log.w(TAG, "setBandLevel failed", e);
            return;
        }
        if (prefs != null) {
            prefs.edit()
                    .putInt(KEY_LEVEL + band, levelMb)
                    .putString(KEY_PRESET, PRESET_CUSTOM)
                    .apply();
        }
    }

    public static synchronized String getPresetName() {
        return prefs != null ? prefs.getString(KEY_PRESET, "Zero") : "Zero";
    }

    /** Применить AIMP-пресет: кривая 10 полос интерполируется под полосы устройства. */
    public static synchronized void applyPreset(String name) {
        if (equalizer == null) {
            return;
        }
        float[] curve = EqPresets.curve(name);
        short[] range = equalizer.getBandLevelRange();
        SharedPreferences.Editor editor = prefs != null ? prefs.edit() : null;
        for (short band = 0; band < equalizer.getNumberOfBands(); band++) {
            float db = EqPresets.gainAt(curve, equalizer.getCenterFreq(band) / 1000f);
            short level = (short) Math.max(range[0], Math.min(range[1], db * 100));
            try {
                equalizer.setBandLevel(band, level);
            } catch (Exception e) {
                Log.w(TAG, "applyPreset band " + band + " failed", e);
            }
            if (editor != null) {
                editor.putInt(KEY_LEVEL + band, level);
            }
        }
        if (editor != null) {
            editor.putString(KEY_PRESET, name).apply();
        }
    }

    private static void applySaved() {
        if (equalizer == null || prefs == null) {
            return;
        }
        for (short band = 0; band < equalizer.getNumberOfBands(); band++) {
            int saved = prefs.getInt(KEY_LEVEL + band, Short.MIN_VALUE);
            if (saved != Short.MIN_VALUE) {
                try {
                    equalizer.setBandLevel(band, (short) saved);
                } catch (Exception e) {
                    Log.w(TAG, "restore band " + band + " failed", e);
                }
            }
        }
        equalizer.setEnabled(prefs.getBoolean(KEY_ENABLED, false));
    }
}
