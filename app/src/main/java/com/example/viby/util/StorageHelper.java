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
        File musicRoot = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        File dir = new File(musicRoot, sanitize(playlistName));
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /** Убирает из имени папки символы, запрещённые в файловых системах. */
    public static String sanitize(String name) {
        String cleaned = name.trim().replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_");
        return cleaned.isEmpty() ? "Default" : cleaned;
    }
}
