package com.example.viby.playback;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 20-полосный эквалайзер на частотной сетке AIMP (31 Гц … 22 кГц).
 * На Android 9+ работает через {@link DynamicsProcessing} (честные 20 полос),
 * на старых устройствах — через системный {@link Equalizer} с интерполяцией
 * 20 пользовательских полос под реальные полосы устройства.
 * Настройки и пользовательские пресеты живут в SharedPreferences.
 */
public final class EqFx {

    private static final String TAG = "EqFx";
    private static final String PREFS = "viby_eq";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_PRESET = "preset";
    private static final String KEY_GAIN = "gain_";
    private static final String KEY_PREAMP_GAIN = "preamp_gain";
    private static final String KEY_BAND_SCHEMA = "band_schema";
    private static final String CUSTOM_PREFS = "viby_eq_custom";
    private static final int BAND_SCHEMA_VERSION = 2;

    public static final float MAX_GAIN_DB = 15f;
    public static final String PRESET_CUSTOM = "";

    private static final int BANDS = EqPresets.FREQS_HZ.length;

    @Nullable
    private static DynamicsProcessing dp;
    @Nullable
    private static Equalizer legacy;
    private static SharedPreferences prefs;
    private static SharedPreferences customPrefs;

    private EqFx() {
    }

    public static synchronized void init(Context context, int audioSessionId) {
        release();
        Context app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        customPrefs = app.getSharedPreferences(CUSTOM_PREFS, Context.MODE_PRIVATE);
        migrateBandSchemaIfNeeded();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                DynamicsProcessing.Config config = new DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                        /* channelCount= */ 2,
                        /* preEqInUse= */ true, BANDS,
                        /* mbcInUse= */ false, 0,
                        /* postEqInUse= */ false, 0,
                        /* limiterInUse= */ false).build();
                dp = new DynamicsProcessing(0, audioSessionId, config);
                Log.i(TAG, "DynamicsProcessing initialized with " + BANDS + " bands");
            } catch (Exception e) {
                Log.w(TAG, "DynamicsProcessing unavailable, falling back", e);
                dp = null;
            }
        }
        if (dp == null) {
            try {
                legacy = new Equalizer(0, audioSessionId);
            } catch (Exception e) {
                Log.w(TAG, "equalizer unavailable", e);
                legacy = null;
            }
        }
        applyAllGains();
        setEnabledInternal(prefs.getBoolean(KEY_ENABLED, false));
    }

    public static synchronized void release() {
        if (dp != null) {
            try {
                dp.release();
            } catch (Exception ignored) {
            }
            dp = null;
        }
        if (legacy != null) {
            try {
                legacy.release();
            } catch (Exception ignored) {
            }
            legacy = null;
        }
    }

    public static synchronized boolean isAvailable() {
        return dp != null || legacy != null;
    }

    public static synchronized boolean isEnabled() {
        return prefs != null && prefs.getBoolean(KEY_ENABLED, false);
    }

    public static synchronized void setEnabled(boolean enabled) {
        if (prefs != null) {
            prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
        }
        setEnabledInternal(enabled);
    }

    /** Полос всегда 20; на старых устройствах они мапятся внутри. */
    public static int getBandCount() {
        return BANDS;
    }

    public static int getCenterFreqHz(int band) {
        return (int) EqPresets.FREQS_HZ[band];
    }

    public static synchronized float getBandGainDb(int band) {
        return prefs != null ? prefs.getFloat(KEY_GAIN + band, 0f) : 0f;
    }

    public static synchronized void setBandGainDb(int band, float db) {
        db = clamp(db);
        if (prefs != null) {
            prefs.edit()
                    .putFloat(KEY_GAIN + band, db)
                    .putString(KEY_PRESET, PRESET_CUSTOM)
                    .apply();
        }
        applyBand(band, db);
        if (legacy != null) {
            applyLegacyAll(); // у устройства свои полосы — пересчитываем все
        }
    }

    public static synchronized float getPreampGainDb() {
        return prefs != null ? prefs.getFloat(KEY_PREAMP_GAIN, 0f) : 0f;
    }

    public static synchronized void setPreampGainDb(float db) {
        db = clamp(db);
        if (prefs != null) {
            prefs.edit().putFloat(KEY_PREAMP_GAIN, db).apply();
        }
        applyPreamp(db);
    }

    public static synchronized String getPresetName() {
        return prefs != null ? prefs.getString(KEY_PRESET, "Zero") : "Zero";
    }

    /** Встроенные пресеты AIMP + пользовательские (сверху). */
    public static synchronized List<String> getPresetNames() {
        List<String> names = new ArrayList<>();
        if (customPrefs != null) {
            names.addAll(new TreeSet<>(customPrefs.getAll().keySet()));
        }
        for (String name : EqPresets.NAMES) {
            names.add(name);
        }
        return names;
    }

    public static synchronized void applyPreset(String name) {
        float[] curve = resolveCurve(name);
        SharedPreferences.Editor editor = prefs != null ? prefs.edit() : null;
        for (int band = 0; band < BANDS; band++) {
            float db = clamp(curve[band]);
            if (editor != null) {
                editor.putFloat(KEY_GAIN + band, db);
            }
            applyBand(band, db);
        }
        if (editor != null) {
            editor.putString(KEY_PRESET, name).apply();
        }
        if (legacy != null) {
            applyLegacyAll();
        }
    }

    /** Сохранить текущие ползунки как пользовательский пресет. */
    public static synchronized void saveCurrentAsPreset(String name) {
        if (customPrefs == null || name.isEmpty()) {
            return;
        }
        StringBuilder csv = new StringBuilder();
        for (int band = 0; band < BANDS; band++) {
            if (band > 0) {
                csv.append(',');
            }
            csv.append(getBandGainDb(band));
        }
        customPrefs.edit().putString(name, csv.toString()).apply();
        if (prefs != null) {
            prefs.edit().putString(KEY_PRESET, name).apply();
        }
    }

    // ------------------------------------------------------------ internals

    private static float[] resolveCurve(String name) {
        if (customPrefs != null) {
            String csv = customPrefs.getString(name, null);
            if (csv != null) {
                float[] curve = parseCurve(csv);
                if (curve != null) {
                    return curve;
                }
            }
        }
        return EqPresets.curve(name);
    }

    /** Переносит настройки 1.2.3 и старше со старой 10-полосной сетки без сброса звука. */
    private static void migrateBandSchemaIfNeeded() {
        if (prefs == null || prefs.getInt(KEY_BAND_SCHEMA, 1) >= BAND_SCHEMA_VERSION) {
            return;
        }

        float[] legacyCurve = new float[EqPresets.LEGACY_FREQS_HZ.length];
        for (int band = 0; band < legacyCurve.length; band++) {
            legacyCurve[band] = prefs.getFloat(KEY_GAIN + band, 0f);
        }
        float[] migrated = EqPresets.migrateLegacyCurve(legacyCurve);
        SharedPreferences.Editor editor = prefs.edit();
        for (int band = 0; band < migrated.length; band++) {
            editor.putFloat(KEY_GAIN + band, clamp(migrated[band]));
        }
        editor.putInt(KEY_BAND_SCHEMA, BAND_SCHEMA_VERSION).apply();

        if (customPrefs != null) {
            SharedPreferences.Editor customEditor = customPrefs.edit();
            boolean changed = false;
            for (Map.Entry<String, ?> entry : customPrefs.getAll().entrySet()) {
                if (!(entry.getValue() instanceof String)) {
                    continue;
                }
                String[] parts = ((String) entry.getValue()).split(",");
                if (parts.length != EqPresets.LEGACY_FREQS_HZ.length) {
                    continue;
                }
                float[] curve = parseRawCurve(parts);
                if (curve != null) {
                    customEditor.putString(entry.getKey(),
                            serializeCurve(EqPresets.migrateLegacyCurve(curve)));
                    changed = true;
                }
            }
            if (changed) {
                customEditor.apply();
            }
        }
        Log.i(TAG, "Migrated equalizer settings to " + BANDS + " bands");
    }

    @Nullable
    private static float[] parseCurve(String csv) {
        String[] parts = csv.split(",");
        float[] parsed = parseRawCurve(parts);
        if (parsed == null) {
            return null;
        }
        if (parsed.length == BANDS) {
            return parsed;
        }
        if (parsed.length == EqPresets.LEGACY_FREQS_HZ.length) {
            return EqPresets.migrateLegacyCurve(parsed);
        }
        return null;
    }

    @Nullable
    private static float[] parseRawCurve(String[] parts) {
        float[] curve = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                float value = Float.parseFloat(parts[i]);
                if (Float.isNaN(value) || Float.isInfinite(value)) {
                    return null;
                }
                curve[i] = clamp(value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return curve;
    }

    private static String serializeCurve(float[] curve) {
        StringBuilder csv = new StringBuilder();
        for (int i = 0; i < curve.length; i++) {
            if (i > 0) {
                csv.append(',');
            }
            csv.append(clamp(curve[i]));
        }
        return csv.toString();
    }

    private static void applyAllGains() {
        for (int band = 0; band < BANDS; band++) {
            applyBand(band, getBandGainDb(band));
        }
        if (dp != null) {
            applyPreamp(getPreampGainDb());
        } else if (legacy != null) {
            applyLegacyAll();
        }
    }

    private static void applyPreamp(float db) {
        if (dp != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                dp.setInputGainAllChannelsTo(db);
            } catch (Exception e) {
                Log.w(TAG, "setInputGain failed", e);
            }
        }
        if (legacy != null) {
            // Legacy Equalizer has no input-gain stage, so emulate it as a curve offset.
            applyLegacyAll();
        }
    }

    private static void applyBand(int band, float db) {
        if (dp != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                dp.setPreEqBandAllChannelsTo(band,
                        new DynamicsProcessing.EqBand(true, cutoffHz(band), db));
            } catch (Exception e) {
                Log.w(TAG, "setPreEqBand failed", e);
            }
        }
    }

    /** Fallback: интерполируем 20 UI-полос под реальные полосы устройства. */
    private static void applyLegacyAll() {
        if (legacy == null) {
            return;
        }
        float[] curve = new float[BANDS];
        for (int band = 0; band < BANDS; band++) {
            curve[band] = getBandGainDb(band);
        }
        try {
            short[] range = legacy.getBandLevelRange();
            float preampDb = getPreampGainDb();
            for (short devBand = 0; devBand < legacy.getNumberOfBands(); devBand++) {
                float db = EqPresets.gainAt(curve,
                        legacy.getCenterFreq(devBand) / 1000f) + preampDb;
                short level = (short) Math.max(range[0], Math.min(range[1], db * 100));
                legacy.setBandLevel(devBand, level);
            }
        } catch (Exception e) {
            Log.w(TAG, "legacy apply failed", e);
        }
    }

    private static void setEnabledInternal(boolean enabled) {
        try {
            if (dp != null) {
                dp.setEnabled(enabled);
            }
            if (legacy != null) {
                legacy.setEnabled(enabled);
            }
        } catch (Exception e) {
            Log.w(TAG, "setEnabled failed", e);
        }
    }

    /** Верхняя граница полосы = геометрическая середина до следующей. */
    private static float cutoffHz(int band) {
        float[] f = EqPresets.FREQS_HZ;
        return band < f.length - 1
                ? (float) Math.sqrt((double) f[band] * f[band + 1]) : 24000f;
    }

    private static float clamp(float db) {
        return Math.max(-MAX_GAIN_DB, Math.min(MAX_GAIN_DB, db));
    }
}
