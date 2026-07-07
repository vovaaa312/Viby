package com.example.viby.util;

import android.content.Context;
import android.util.Log;
import android.webkit.CookieManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Куки YouTube-аккаунта для yt-dlp (--cookies): снимают возрастные
 * ограничения и открывают доступ к плейлистам аккаунта.
 * После логина в WebView куки экспортируются в Netscape-формат.
 */
public final class YtCookies {

    private static final String TAG = "YtCookies";
    private static final String FILE_NAME = "youtube_cookies.txt";
    /** По этим кукам понимаем, что логин действительно случился. */
    private static final String AUTH_COOKIE = "SAPISID";

    private YtCookies() {
    }

    public static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    public static boolean isLoggedIn(Context context) {
        File file = file(context);
        if (!file.exists()) {
            return false;
        }
        try {
            String content = new String(Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8);
            return content.contains(AUTH_COOKIE);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Забирает куки .youtube.com из WebView и пишет cookies.txt.
     *
     * @return true, если среди кук есть авторизационные (логин удался)
     */
    public static synchronized boolean exportFromWebView(Context context) {
        String cookieHeader = CookieManager.getInstance()
                .getCookie("https://www.youtube.com");
        if (cookieHeader == null || !cookieHeader.contains(AUTH_COOKIE)) {
            return false;
        }
        long expiry = System.currentTimeMillis() / 1000 + 365L * 24 * 3600;
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(file(context)), StandardCharsets.UTF_8)) {
            writer.write("# Netscape HTTP Cookie File\n");
            for (String pair : cookieHeader.split(";\\s*")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String name = pair.substring(0, eq).trim();
                String value = pair.substring(eq + 1).trim();
                writer.write(".youtube.com\tTRUE\t/\tTRUE\t" + expiry
                        + "\t" + name + "\t" + value + "\n");
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "cookie export failed", e);
            return false;
        }
    }

    /** Выход из аккаунта: удалить файл и куки WebView. */
    public static void clear(Context context) {
        //noinspection ResultOfMethodCallIgnored
        file(context).delete();
        try {
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
        } catch (Exception e) {
            Log.w(TAG, "cookie clear failed", e);
        }
    }
}
