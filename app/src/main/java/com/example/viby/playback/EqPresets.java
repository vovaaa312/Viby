package com.example.viby.playback;

/**
 * Пресеты эквалайзера как в AIMP/Winamp: 10 полос
 * (60, 170, 310, 600, 1k, 3k, 6k, 12k, 14k, 16k Гц), значения в дБ.
 * Кривые интерполируются под реальные полосы системного эквалайзера устройства.
 */
public final class EqPresets {

    public static final float[] FREQS_HZ =
            {60, 170, 310, 600, 1000, 3000, 6000, 12000, 14000, 16000};

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
                return CURVES[i];
            }
        }
        return CURVES[0];
    }

    /**
     * Значение кривой (дБ) на произвольной частоте — линейная интерполяция
     * по логарифму частоты между 10 опорными полосами.
     */
    public static float gainAt(float[] curve, float freqHz) {
        if (freqHz <= FREQS_HZ[0]) {
            return curve[0];
        }
        if (freqHz >= FREQS_HZ[FREQS_HZ.length - 1]) {
            return curve[curve.length - 1];
        }
        for (int i = 0; i < FREQS_HZ.length - 1; i++) {
            if (freqHz <= FREQS_HZ[i + 1]) {
                double logLow = Math.log(FREQS_HZ[i]);
                double logHigh = Math.log(FREQS_HZ[i + 1]);
                double t = (Math.log(freqHz) - logLow) / (logHigh - logLow);
                return (float) (curve[i] + t * (curve[i + 1] - curve[i]));
            }
        }
        return 0;
    }
}
