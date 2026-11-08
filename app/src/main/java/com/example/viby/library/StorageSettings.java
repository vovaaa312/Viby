package com.example.viby.library;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.annotation.Nullable;

/** Persisted choice of the Storage Access Framework mirror folder. */
public final class StorageSettings {

    private static final String PREFS = "viby_storage";
    private static final String KEY_TREE_URI = "mirror_tree_uri";
    private static final String KEY_AUTO_SYNC = "mirror_auto_sync";
    private static final String KEY_LAST_SYNC = "mirror_last_sync";

    private StorageSettings() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Nullable
    public static Uri mirrorTree(Context context) {
        String value = prefs(context).getString(KEY_TREE_URI, null);
        return value != null ? Uri.parse(value) : null;
    }

    public static void setMirrorTree(Context context, @Nullable Uri uri) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (uri == null) {
            editor.remove(KEY_TREE_URI);
        } else {
            editor.putString(KEY_TREE_URI, uri.toString());
        }
        editor.apply();
    }

    public static boolean isAutoSync(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_SYNC, false);
    }

    public static void setAutoSync(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTO_SYNC, enabled).apply();
    }

    public static long lastSyncAt(Context context) {
        return prefs(context).getLong(KEY_LAST_SYNC, 0L);
    }

    static void setLastSyncAt(Context context, long time) {
        prefs(context).edit().putLong(KEY_LAST_SYNC, time).apply();
    }
}
