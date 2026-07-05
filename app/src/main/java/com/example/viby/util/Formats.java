package com.example.viby.util;

import java.util.Locale;

public final class Formats {

    private Formats() {
    }

    /** 245000 → "4:05", 3725000 → "1:02:05". */
    public static String duration(long ms) {
        if (ms <= 0) {
            return "0:00";
        }
        long totalSeconds = ms / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }
}
