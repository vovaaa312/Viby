package com.example.viby.util;

import android.content.Context;
import android.os.Environment;

import java.io.File;

public final class StorageHelper {

    private StorageHelper() {
    }

    /**
     * Папка плейлиста: Android/data/com.example.viby/files/Music/&lt;плейлист&gt;/
     * Доступна без разрешений, файлы можно забрать по USB (MTP) или adb.
     */
    public static File playlistDir(Context context, String playlistName) {
        File dir = new File(musicRoot(context), sanitize(playlistName));
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /** Root of the app-owned library: Android/data/com.example.viby/files/Music. */
    public static File musicRoot(Context context) {
        File root = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (root == null) {
            // external storage unavailable (rare emulator/OEM states): stay app-private
            root = new File(context.getFilesDir(), Environment.DIRECTORY_MUSIC);
        }
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
        return root;
    }

    /** Убирает из имени папки символы, запрещённые в файловых системах. */
    public static String sanitize(String name) {
        String cleaned = name.trim().replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_");
        return cleaned.isEmpty() ? "Default" : cleaned;
    }
}
