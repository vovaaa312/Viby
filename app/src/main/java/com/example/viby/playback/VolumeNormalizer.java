package com.example.viby.playback;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;

import com.example.viby.data.Track;
import com.example.viby.data.VibyDatabase;

/**
 * Applies {@link VolumeNormalization} to the playing track: cuts through the player
 * volume (cannot clip) and boosts through a {@link LoudnessEnhancer} on the player's
 * audio session, whose limiter keeps boosted peaks in range.
 */
final class VolumeNormalizer implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "VolumeNormalizer";

    private final Context context;
    private final ExoPlayer player;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    @Nullable private LoudnessEnhancer enhancer;
    private boolean enhancerFailed;
    /** Increases on every request so a slow lookup cannot apply a stale track's gain. */
    private int generation;
    /** Player volume chosen by normalization, before the sleep-timer fade. */
    private float baseVolume = 1f;
    private float fade = 1f;
    private float transitionFade = 1f;
    private boolean released;

    VolumeNormalizer(Context context, ExoPlayer player, int audioSessionId) {
        this.context = context.getApplicationContext();
        this.player = player;
        prefs = VolumeNormalization.prefs(this.context);
        prefs.registerOnSharedPreferenceChangeListener(this);
        try {
            enhancer = new LoudnessEnhancer(audioSessionId);
            enhancer.setTargetGain(0);
            enhancer.setEnabled(false);
        } catch (RuntimeException unsupported) {
            enhancerFailed = true; // quiet tracks then simply stay quiet
            Log.w(TAG, "LoudnessEnhancer unavailable", unsupported);
        }
    }

    /** Looks up the current track's loudness and applies the matching gain. */
    void applyForCurrentItem() {
        int request = ++generation;
        MediaItem item = player.getCurrentMediaItem();
        if (item == null || !VolumeNormalization.isEnabled(context)) {
            setGainDb(0.0);
            return;
        }
        long trackId;
        try {
            trackId = Long.parseLong(item.mediaId);
        } catch (NumberFormatException streamOrForeignItem) {
            setGainDb(0.0);
            return;
        }
        double target = VolumeNormalization.level(context).targetLufs;
        long nextId = nextTrackId();
        VibyDatabase.dbExecutor.execute(() -> {
            com.example.viby.data.TrackDao dao = VibyDatabase.get(context).trackDao();
            Track track = dao.getByIdSync(trackId);
            if (track != null && !track.loudnessScanned) {
                // not measured yet (on battery the library is only scanned while charging):
                // measure it now and apply the gain as soon as the number is known
                LoudnessScanner.measureSoon(context, track.filePath, this::onMeasured);
            }
            Track next = nextId < 0 ? null : dao.getByIdSync(nextId);
            if (next != null && !next.loudnessScanned) {
                LoudnessScanner.measureSoon(context, next.filePath, null);
            }
            double gain = track == null ? 0.0 : VolumeNormalization.gainDb(
                    track.loudnessLufs, track.loudnessPeak, target);
            main.post(() -> {
                if (request == generation) {
                    setGainDb(gain);
                }
            });
        });
    }

    private void onMeasured() {
        if (!released) {
            applyForCurrentItem();
        }
    }

    private long nextTrackId() {
        int index = player.getNextMediaItemIndex();
        if (index == androidx.media3.common.C.INDEX_UNSET) {
            return -1;
        }
        try {
            return Long.parseLong(player.getMediaItemAt(index).mediaId);
        } catch (NumberFormatException streamOrForeignItem) {
            return -1;
        }
    }

    /** Sleep-timer fade multiplier, 0..1, applied on top of the normalization gain. */
    void setFade(float fade) {
        this.fade = Math.max(0f, Math.min(1f, fade));
        updateVolume();
    }

    /** Track-change fade multiplier ({@link Crossfade}), 0..1. */
    void setTransitionFade(float fade) {
        float clamped = Math.max(0f, Math.min(1f, fade));
        if (clamped != transitionFade) {
            transitionFade = clamped;
            updateVolume();
        }
    }

    private void updateVolume() {
        player.setVolume(baseVolume * fade * transitionFade);
    }

    private void setGainDb(double gainDb) {
        Log.d(TAG, String.format(java.util.Locale.ROOT, "gain %.1f dB", gainDb));
        if (gainDb <= 0.0 || enhancerFailed || enhancer == null) {
            disableEnhancer();
            baseVolume = (float) Math.pow(10.0, Math.min(0.0, gainDb) / 20.0);
            updateVolume();
            return;
        }
        baseVolume = 1f;
        updateVolume();
        try {
            enhancer.setTargetGain((int) Math.round(gainDb * 100.0)); // millibels
            enhancer.setEnabled(true);
        } catch (RuntimeException error) {
            enhancerFailed = true;
            Log.w(TAG, "LoudnessEnhancer failed", error);
            disableEnhancer();
        }
    }

    private void disableEnhancer() {
        if (enhancer == null) {
            return;
        }
        try {
            enhancer.setTargetGain(0);
            enhancer.setEnabled(false);
        } catch (RuntimeException ignored) {
            // the effect died with its audio session; nothing to undo
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
                                          @Nullable String key) {
        if (VolumeNormalization.KEY_ENABLED.equals(key)
                || VolumeNormalization.KEY_LEVEL.equals(key)) {
            applyForCurrentItem();
        }
    }

    void release() {
        released = true;
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        main.removeCallbacksAndMessages(null);
        if (enhancer != null) {
            try {
                enhancer.release();
            } catch (RuntimeException ignored) {
                // already released by the audio framework
            }
            enhancer = null;
        }
    }
}
