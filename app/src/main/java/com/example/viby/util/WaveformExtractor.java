package com.example.viby.util;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Считает амплитудную «волну» аудиофайла для WaveformView:
 * декодирует трек в PCM и берёт пик в каждой из {@link #BARS} корзин.
 * Результат кэшируется в cacheDir/waveforms, чтобы декодировать один раз.
 */
public final class WaveformExtractor {

    private static final String TAG = "WaveformExtractor";
    public static final int BARS = 200;
    private static final long TIMEOUT_US = 10_000;

    public interface Callback {
        void onWaveform(String filePath, @Nullable float[] amps);
    }

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WaveformExtractor() {
    }

    public static void load(Context context, String filePath, Callback callback) {
        Context app = context.getApplicationContext();
        executor.execute(() -> {
            float[] amps = null;
            try {
                amps = loadOrExtract(app, filePath);
            } catch (Exception e) {
                Log.w(TAG, "waveform failed: " + filePath, e);
            }
            float[] result = amps;
            mainHandler.post(() -> callback.onWaveform(filePath, result));
        });
    }

    @Nullable
    private static float[] loadOrExtract(Context context, String filePath) throws Exception {
        File source = new File(filePath);
        if (!source.exists()) {
            return null;
        }
        File cache = cacheFile(context, source);
        if (cache.exists()) {
            return readCache(cache);
        }
        float[] amps = extract(filePath);
        if (amps != null) {
            writeCache(cache, amps);
        }
        return amps;
    }

    @Nullable
    private static float[] extract(String filePath) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(filePath);
            int trackIndex = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = i;
                    format = f;
                    break;
                }
            }
            if (trackIndex < 0 || format == null) {
                return null;
            }
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION)
                    ? format.getLong(MediaFormat.KEY_DURATION) : 0;
            if (durationUs <= 0) {
                return null;
            }
            extractor.selectTrack(trackIndex);

            String mime = format.getString(MediaFormat.KEY_MIME);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            float[] peaks = new float[BARS];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;

            while (!outputDone) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(TIMEOUT_US);
                    if (inIndex >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                        int size = extractor.readSampleData(inBuf, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        ByteBuffer outBuf = codec.getOutputBuffer(outIndex);
                        outBuf.position(info.offset);
                        outBuf.limit(info.offset + info.size);
                        int bucket = (int) Math.min(BARS - 1,
                                info.presentationTimeUs * BARS / durationUs);
                        if (bucket >= 0) {
                            ByteBuffer pcm = outBuf.order(ByteOrder.LITTLE_ENDIAN);
                            int peak = 0;
                            // шаг 8 сэмплов — пика хватает, а декод не тормозим
                            for (int p = pcm.position(); p + 1 < pcm.limit(); p += 16) {
                                int v = Math.abs(pcm.getShort(p));
                                if (v > peak) {
                                    peak = v;
                                }
                            }
                            if (peak > peaks[bucket]) {
                                peaks[bucket] = peak;
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }

            float max = 0;
            for (float p : peaks) {
                if (p > max) {
                    max = p;
                }
            }
            if (max <= 0) {
                return null;
            }
            for (int i = 0; i < peaks.length; i++) {
                // sqrt — перцептивно приятнее, тихие места не исчезают
                peaks[i] = (float) Math.sqrt(peaks[i] / max);
            }
            return peaks;
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception ignored) {
                }
                codec.release();
            }
            extractor.release();
        }
    }

    // ------------------------------------------------------------- cache

    private static File cacheFile(Context context, File source) {
        File dir = new File(context.getCacheDir(), "waveforms");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        int key = (source.getAbsolutePath() + "|" + source.length()
                + "|" + source.lastModified() + "|" + BARS).hashCode();
        return new File(dir, Integer.toHexString(key) + ".wf");
    }

    @Nullable
    private static float[] readCache(File cache) {
        try (FileInputStream in = new FileInputStream(cache)) {
            byte[] bytes = new byte[(int) cache.length()];
            if (in.read(bytes) != bytes.length) {
                return null;
            }
            float[] amps = new float[bytes.length];
            for (int i = 0; i < bytes.length; i++) {
                amps[i] = (bytes[i] & 0xFF) / 255f;
            }
            return amps;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeCache(File cache, float[] amps) {
        try (FileOutputStream out = new FileOutputStream(cache)) {
            byte[] bytes = new byte[amps.length];
            for (int i = 0; i < amps.length; i++) {
                bytes[i] = (byte) (Math.min(1f, amps[i]) * 255);
            }
            out.write(bytes);
        } catch (Exception e) {
            Log.w(TAG, "cache write failed", e);
        }
    }
}
