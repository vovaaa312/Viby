package com.example.viby.playback;

import java.util.Arrays;

/**
 * Integrated loudness (LUFS) and sample peak of a track, following ITU-R BS.1770 /
 * EBU R128 (the basis of ReplayGain 2.0): K-weighting, 400 ms blocks with 75% overlap,
 * an absolute gate at -70 LUFS and a relative gate 10 LU below the ungated mean.
 *
 * <p>Feed interleaved samples in the range [-1, 1] with {@link #add}, then read
 * {@link #integratedLufs()}. Pure Java so it can be unit-tested off-device.
 */
public final class LoudnessMeter {

    /** Returned when the track is silent or too short to be measured. */
    public static final double SILENCE = Double.NEGATIVE_INFINITY;

    private static final double ABSOLUTE_GATE_LUFS = -70.0;
    private static final double RELATIVE_GATE_LU = -10.0;

    private final int channels;
    private final int samplesPer100ms;
    private final Biquad[] shelf;
    private final Biquad[] highPass;

    /** Mean square of each finished 100 ms step, summed over channels. */
    private double[] steps = new double[256];
    private int stepCount;
    private double stepSum;
    private int stepFill;
    private double peak;

    public LoudnessMeter(int sampleRate, int channels) {
        if (sampleRate <= 0 || channels <= 0) {
            throw new IllegalArgumentException("Bad audio format " + sampleRate + "/" + channels);
        }
        this.channels = channels;
        this.samplesPer100ms = Math.max(1, sampleRate / 10);
        shelf = new Biquad[channels];
        highPass = new Biquad[channels];
        for (int c = 0; c < channels; c++) {
            shelf[c] = Biquad.kWeightingShelf(sampleRate);
            highPass[c] = Biquad.kWeightingHighPass(sampleRate);
        }
    }

    /** Adds {@code frames} interleaved frames starting at {@code offset}. */
    public void add(float[] interleaved, int offset, int frames) {
        int index = offset;
        for (int f = 0; f < frames; f++) {
            double frameEnergy = 0;
            for (int c = 0; c < channels; c++) {
                double sample = interleaved[index++];
                double magnitude = Math.abs(sample);
                if (magnitude > peak) {
                    peak = magnitude;
                }
                double weighted = highPass[c].process(shelf[c].process(sample));
                // BS.1770 channel weights are 1.0 for left, right and centre
                frameEnergy += weighted * weighted;
            }
            stepSum += frameEnergy;
            if (++stepFill == samplesPer100ms) {
                pushStep(stepSum / samplesPer100ms);
                stepSum = 0;
                stepFill = 0;
            }
        }
    }

    /** Highest absolute sample value seen, 0..1 (can exceed 1 for clipped sources). */
    public double samplePeak() {
        return peak;
    }

    /** Gated integrated loudness in LUFS, or {@link #SILENCE}. */
    public double integratedLufs() {
        int blocks = stepCount - 3; // a 400 ms block spans four 100 ms steps
        if (blocks <= 0) {
            return SILENCE;
        }
        double[] blockPower = new double[blocks];
        for (int b = 0; b < blocks; b++) {
            blockPower[b] = (steps[b] + steps[b + 1] + steps[b + 2] + steps[b + 3]) / 4.0;
        }
        double absoluteSum = 0;
        int absoluteCount = 0;
        for (double power : blockPower) {
            if (loudness(power) > ABSOLUTE_GATE_LUFS) {
                absoluteSum += power;
                absoluteCount++;
            }
        }
        if (absoluteCount == 0) {
            return SILENCE;
        }
        double relativeGate = loudness(absoluteSum / absoluteCount) + RELATIVE_GATE_LU;
        double gatedSum = 0;
        int gatedCount = 0;
        for (double power : blockPower) {
            double blockLoudness = loudness(power);
            if (blockLoudness > ABSOLUTE_GATE_LUFS && blockLoudness > relativeGate) {
                gatedSum += power;
                gatedCount++;
            }
        }
        return gatedCount == 0 ? SILENCE : loudness(gatedSum / gatedCount);
    }

    private void pushStep(double meanSquare) {
        if (stepCount == steps.length) {
            steps = Arrays.copyOf(steps, steps.length * 2);
        }
        steps[stepCount++] = meanSquare;
    }

    private static double loudness(double power) {
        return power <= 0 ? SILENCE : -0.691 + 10.0 * Math.log10(power);
    }

    /** Direct form I biquad; coefficients for any sample rate (as in libebur128). */
    static final class Biquad {
        private final double b0;
        private final double b1;
        private final double b2;
        private final double a1;
        private final double a2;
        private double x1;
        private double x2;
        private double y1;
        private double y2;

        Biquad(double b0, double b1, double b2, double a1, double a2) {
            this.b0 = b0;
            this.b1 = b1;
            this.b2 = b2;
            this.a1 = a1;
            this.a2 = a2;
        }

        /** Stage 1: high shelf modelling the acoustic effect of the head. */
        static Biquad kWeightingShelf(int sampleRate) {
            double f0 = 1681.974450955533;
            double gainDb = 3.999843853973347;
            double q = 0.7071752369554196;
            double k = Math.tan(Math.PI * f0 / sampleRate);
            double vh = Math.pow(10.0, gainDb / 20.0);
            double vb = Math.pow(vh, 0.4996667741545416);
            double a0 = 1.0 + k / q + k * k;
            return new Biquad(
                    (vh + vb * k / q + k * k) / a0,
                    2.0 * (k * k - vh) / a0,
                    (vh - vb * k / q + k * k) / a0,
                    2.0 * (k * k - 1.0) / a0,
                    (1.0 - k / q + k * k) / a0);
        }

        /** Stage 2: RLB high-pass filter. */
        static Biquad kWeightingHighPass(int sampleRate) {
            double f0 = 38.13547087602444;
            double q = 0.5003270373238773;
            double k = Math.tan(Math.PI * f0 / sampleRate);
            double a0 = 1.0 + k / q + k * k;
            return new Biquad(1.0, -2.0, 1.0,
                    2.0 * (k * k - 1.0) / a0,
                    (1.0 - k / q + k * k) / a0);
        }

        double process(double x) {
            double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x;
            y2 = y1;
            y1 = y;
            return y;
        }
    }
}
