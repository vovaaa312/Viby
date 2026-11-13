package com.example.viby.download;

import android.content.Context;
import android.content.SharedPreferences;

/** User choice of the audio format and MP3 bitrate for new downloads. */
public final class DownloadSettings {

    private static final String PREFS = "viby_download";
    private static final String KEY_FORMAT = "format";
    private static final String KEY_MP3_QUALITY = "mp3_quality";

    public enum Format {
        /** Re-encoded by FFmpeg; plays everywhere. */
        MP3("mp3"),
        /** YouTube's own AAC stream, saved without re-encoding. */
        M4A("m4a");

        public final String extension;

        Format(String extension) {
            this.extension = extension;
        }

        /** The format tried when this one fails. */
        public Format fallback() {
            return this == MP3 ? M4A : MP3;
        }
    }

    public enum Mp3Quality {
        /** LAME VBR V0 (about 245 kbit/s), the previous fixed setting. */
        BEST("0"),
        K320("320K"),
        K192("192K"),
        K128("128K");

        /** Value for yt-dlp's --audio-quality. */
        public final String ytDlpValue;

        Mp3Quality(String ytDlpValue) {
            this.ytDlpValue = ytDlpValue;
        }
    }

    private DownloadSettings() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Format format(Context context) {
        return parse(Format.class, prefs(context).getString(KEY_FORMAT, null), Format.MP3);
    }

    public static Mp3Quality mp3Quality(Context context) {
        return parse(Mp3Quality.class, prefs(context).getString(KEY_MP3_QUALITY, null),
                Mp3Quality.BEST);
    }

    public static void setFormat(Context context, Format format) {
        prefs(context).edit().putString(KEY_FORMAT, format.name()).apply();
    }

    public static void setMp3Quality(Context context, Mp3Quality quality) {
        prefs(context).edit().putString(KEY_MP3_QUALITY, quality.name()).apply();
    }

    /** --audio-quality for a format: the chosen bitrate for MP3, best for AAC. */
    static String audioQuality(String extension, Mp3Quality mp3Quality) {
        return Format.MP3.extension.equals(extension) ? mp3Quality.ytDlpValue : "0";
    }

    private static <T extends Enum<T>> T parse(Class<T> type, String value, T fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException unknown) {
            return fallback;
        }
    }
}
