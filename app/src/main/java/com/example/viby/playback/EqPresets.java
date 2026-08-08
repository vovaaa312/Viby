package com.example.viby.playback;

/**
 * Пресеты эквалайзера, перенесённые со старой 10-полосной сетки Viby
 * на 20-полосную логарифмическую сетку AIMP.
 */
public final class EqPresets {

    static final float[] LEGACY_FREQS_HZ =
            {60, 170, 310, 600, 1000, 3000, 6000, 12000, 14000, 16000};

    public static final float[] FREQS_HZ = {
            31, 43, 63, 87, 125, 175, 250, 350, 500, 700,
            1000, 1400, 2000, 2800, 4000, 5600, 8000, 11200, 16000, 22000
    };

    public static final String[] NAMES = {
            "Zero",
            "Classical",
            "Club",
            "Dance",
            "Full Bass",
            "Full Bass & Treble",
            "Full Treble",
            "Headphones",
            "Heavy Metal",
            "Hip-Hop",
            "Industrial",
            "Jazz",
            "Live",
            "Party",
            "Pop",
            "Rap",
            "Rock",
            "Ska",
            "Soft",
            "Soft Rock",
            "Techno",
            "Vocal",
    };

    private static final float[][] CURVES = {
            /* Zero */              {0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            /* Classical */         {0, 0, 0, 0, 0, 0, -7.2f, -7.2f, -7.2f, -9.6f},
            /* Club */              {0, 0, 2.4f, 5.6f, 5.6f, 5.6f, 3.2f, 0, 0, 0},
            /* Dance */             {9.6f, 7.2f, 2.4f, 0, 0, -5.6f, -7.2f, -7.2f, 0, 0},
            /* Full Bass */         {9.6f, 9.6f, 9.6f, 5.6f, 1.6f, -4, -8, -10.4f, -11.2f, -11.2f},
            /* Full Bass & Treble */{7.2f, 5.6f, 0, -7.2f, -4.8f, 1.6f, 8, 11.2f, 12, 12},
            /* Full Treble */       {-9.6f, -9.6f, -9.6f, -4, 2.4f, 11.2f, 12, 12, 12, 12},
            /* Headphones */        {4.8f, 11.2f, 5.6f, -3.2f, -2.4f, 1.6f, 4.8f, 9.6f, 12, 12},
            /* Heavy Metal */       {4.8f, 1.6f, 9.6f, 3.2f, 0, 0, 3.2f, 6.4f, 8.8f, 8.8f},
            /* Hip-Hop */           {8, 7.2f, 2.4f, 4, -1.6f, -1.6f, 1.6f, -0.8f, 3.2f, 4.8f},
            /* Industrial */        {4.8f, 3.2f, 0, -3.2f, -4, -1.6f, 3.2f, 6.4f, 7.2f, 5.6f},
            /* Jazz */              {4, 3.2f, 1.6f, 2.4f, -1.6f, -1.6f, 0, 1.6f, 3.2f, 4},
            /* Live */              {-4.8f, 0, 4, 5.6f, 5.6f, 5.6f, 4, 2.4f, 2.4f, 2.4f},
            /* Party */             {7.2f, 7.2f, 0, 0, 0, 0, 0, 0, 7.2f, 7.2f},
            /* Pop */               {-1.6f, 4.8f, 7.2f, 8, 5.6f, 0, -2.4f, -2.4f, -1.6f, -1.6f},
            /* Rap */               {8, 7.2f, 2.4f, 4, -1.6f, -1.6f, 2.4f, -0.8f, 3.2f, 5.6f},
            /* Rock */              {8, 4.8f, -5.6f, -8, -3.2f, 4, 8.8f, 11.2f, 11.2f, 11.2f},
            /* Ska */               {-2.4f, -4.8f, -4, 0, 4, 5.6f, 8.8f, 9.6f, 11.2f, 9.6f},
            /* Soft */              {4.8f, 1.6f, -1.6f, -2.4f, -1.6f, 4, 8, 9.6f, 11.2f, 12},
            /* Soft Rock */         {4, 4, 2.4f, -0.8f, -4.8f, -5.6f, -3.2f, -0.8f, 2.4f, 8.8f},
            /* Techno */            {8, 5.6f, 0, -5.6f, -4.8f, 0, 8, 9.6f, 9.6f, 8.8f},
            /* Vocal */             {-3.2f, -4, -3.2f, 1.6f, 4, 4, 3.2f, 1.6f, 0, -1.6f},
    };

    private EqPresets() {
    }

    public static float[] curve(String name) {
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equals(name)) {
                return migrateLegacyCurve(CURVES[i]);
            }
        }
        return migrateLegacyCurve(CURVES[0]);
    }

    /**
     * Значение кривой (дБ) на произвольной частоте — линейная интерполяция
     * по логарифму частоты между 10 опорными полосами.
     */
    public static float gainAt(float[] curve, float freqHz) {
        return gainAt(curve, FREQS_HZ, freqHz);
    }

    static float[] migrateLegacyCurve(float[] legacyCurve) {
        return resample(legacyCurve, LEGACY_FREQS_HZ, FREQS_HZ);
    }

    static float[] resample(float[] curve, float[] sourceFreqs, float[] targetFreqs) {
        if (curve.length != sourceFreqs.length || sourceFreqs.length == 0) {
            throw new IllegalArgumentException("Curve and frequency counts must match");
        }
        float[] result = new float[targetFreqs.length];
        for (int i = 0; i < targetFreqs.length; i++) {
            result[i] = gainAt(curve, sourceFreqs, targetFreqs[i]);
        }
        return result;
    }

    private static float gainAt(float[] curve, float[] frequencies, float freqHz) {
        if (freqHz <= frequencies[0]) {
            return curve[0];
        }
        if (freqHz >= frequencies[frequencies.length - 1]) {
            return curve[curve.length - 1];
        }
        for (int i = 0; i < frequencies.length - 1; i++) {
            if (freqHz <= frequencies[i + 1]) {
                double logLow = Math.log(frequencies[i]);
                double logHigh = Math.log(frequencies[i + 1]);
                double t = (Math.log(freqHz) - logLow) / (logHigh - logLow);
                return (float) (curve[i] + t * (curve[i + 1] - curve[i]));
            }
        }
        return 0;
    }
}
