package com.example.viby.playback;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Decodes an audio file on the device and measures its loudness with {@link LoudnessMeter}. */
public final class LoudnessAnalyzer {

    private static final long TIMEOUT_US = 10_000L;

    public static final class Result {
        public final float lufs;
        public final float peak;

        Result(float lufs, float peak) {
            this.lufs = lufs;
            this.peak = peak;
        }
    }

    private LoudnessAnalyzer() {
    }

    /** @return the measurement, or null when the file has no decodable audio or is silent */
    @Nullable
    public static Result analyze(String filePath) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(filePath);
            MediaFormat input = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    extractor.selectTrack(i);
                    input = format;
                    break;
                }
            }
            if (input == null) {
                return null;
            }
            codec = MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME));
            codec.configure(input, null, null, 0);
            codec.start();

            int sampleRate = input.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = input.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int encoding = AudioFormat.ENCODING_PCM_16BIT;
            LoudnessMeter meter = null;
            PcmBuffer pcmBuffer = new PcmBuffer();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            while (!outputDone) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("loudness analysis cancelled");
                }
                // Keep the codec busy: hand it every free input buffer, then take every ready
                // output, and only wait when neither side moved. Feeding one frame per round
                // trip made a 4-minute track take seconds.
                boolean progressed = false;
                while (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(0);
                    if (inIndex < 0) {
                        break;
                    }
                    progressed = true;
                    ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                    int size = inBuf != null ? extractor.readSampleData(inBuf, 0) : -1;
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
                int outIndex = codec.dequeueOutputBuffer(info, progressed ? 0 : TIMEOUT_US);
                if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;
                }
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat output = codec.getOutputFormat();
                    sampleRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        encoding = output.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    }
                    meter = null; // the stream starts again with the real format
                } else if (outIndex >= 0) {
                    if (info.size > 0) {
                        if (meter == null) {
                            meter = new LoudnessMeter(sampleRate, channels);
                        }
                        ByteBuffer out = codec.getOutputBuffer(outIndex);
                        if (out != null) {
                            out.position(info.offset);
                            out.limit(info.offset + info.size);
                            pcmBuffer.feed(meter, out.order(ByteOrder.LITTLE_ENDIAN),
                                    encoding, channels);
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
            if (meter == null) {
                return null;
            }
            double lufs = meter.integratedLufs();
            if (Double.isInfinite(lufs) || Double.isNaN(lufs)) {
                return null;
            }
            return new Result((float) lufs, (float) meter.samplePeak());
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception ignored) {
                    // already stopped or in an error state
                }
                codec.release();
            }
            extractor.release();
        }
    }

    /** Reusable conversion buffers, so a long track does not allocate per output buffer. */
    private static final class PcmBuffer {
        private float[] samples = new float[0];
        private short[] shorts = new short[0];

        void feed(LoudnessMeter meter, ByteBuffer pcm, int encoding, int channels) {
            int bytesPerSample = encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2;
            int count = pcm.remaining() / bytesPerSample;
            count -= count % channels;
            if (samples.length < count) {
                samples = new float[count];
            }
            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                pcm.asFloatBuffer().get(samples, 0, count);
            } else {
                if (shorts.length < count) {
                    shorts = new short[count];
                }
                pcm.asShortBuffer().get(shorts, 0, count);
                for (int i = 0; i < count; i++) {
                    samples[i] = shorts[i] / 32768f;
                }
            }
            meter.add(samples, 0, count / channels);
        }
    }
}
