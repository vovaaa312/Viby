package com.example.viby.update;

import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.viby.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Checks GitHub Releases, downloads a newer APK, verifies it, and opens Android's installer. */
public final class AppUpdateManager implements AutoCloseable {

    private static final String TAG = "AppUpdateManager";
    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/vovaaa312/Viby/releases/latest";
    private static final String PREFS = "app_updates";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final String KEY_DOWNLOAD_ID = "download_id";
    private static final String KEY_DOWNLOAD_VERSION = "download_version";
    private static final String KEY_DOWNLOAD_DIGEST = "download_digest";
    private static final String KEY_DOWNLOAD_STARTED_AT = "download_started_at";
    private static final long NO_DOWNLOAD = -1L;
    private static final long AUTO_CHECK_INTERVAL_MS = TimeUnit.DAYS.toMillis(1);
    private static final long STALE_PAUSED_DOWNLOAD_MS = TimeUnit.MINUTES.toMillis(15);
    private static final String APK_MIME = "application/vnd.android.package-archive";

    private final AppCompatActivity activity;
    private final SharedPreferences prefs;
    private final DownloadManager downloadManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean checking = new AtomicBoolean();
    private final AtomicBoolean verifying = new AtomicBoolean();

    private boolean receiverRegistered;
    private boolean activityResumed;
    private boolean waitingForInstallPermission;
    @Nullable
    private Uri pendingInstallUri;
    @Nullable
    private Release pendingRelease;
    @Nullable
    private String pendingCurrentVersion;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                return;
            }
            long completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID,
                    NO_DOWNLOAD);
            if (activityResumed
                    && completedId == prefs.getLong(KEY_DOWNLOAD_ID, NO_DOWNLOAD)) {
                processCompletedDownload(true);
            }
        }
    };

    public AppUpdateManager(AppCompatActivity activity) {
        this.activity = activity;
        prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        downloadManager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
    }

    public void start() {
        ContextCompat.registerReceiver(activity, downloadReceiver,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_EXPORTED);
        receiverRegistered = true;

        boolean hasPendingDownload = prefs.getLong(KEY_DOWNLOAD_ID, NO_DOWNLOAD)
                != NO_DOWNLOAD;
        checkPendingDownload();
        if (!hasPendingDownload) {
            checkForUpdates(false);
        }
    }

    public void checkManually() {
        checkForUpdates(true);
    }

    public void onResume() {
        activityResumed = true;
        if (pendingRelease != null && pendingCurrentVersion != null) {
            Release release = pendingRelease;
            String currentVersion = pendingCurrentVersion;
            pendingRelease = null;
            pendingCurrentVersion = null;
            showUpdateDialog(release, currentVersion);
        }
        if (!waitingForInstallPermission || pendingInstallUri == null
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            checkPendingDownload();
            return;
        }
        waitingForInstallPermission = false;
        if (activity.getPackageManager().canRequestPackageInstalls()) {
            Uri uri = pendingInstallUri;
            pendingInstallUri = null;
            launchInstaller(uri);
        } else {
            pendingInstallUri = null;
            Toast.makeText(activity, R.string.app_update_install_permission_denied,
                    Toast.LENGTH_LONG).show();
        }
    }

    public void onPause() {
        activityResumed = false;
    }

    @Override
    public void close() {
        if (receiverRegistered) {
            activity.unregisterReceiver(downloadReceiver);
            receiverRegistered = false;
        }
        executor.shutdownNow();
    }

    private void checkForUpdates(boolean manual) {
        checkForUpdates(manual, false);
    }

    private void checkForUpdates(boolean manual, boolean bypassThrottle) {
        if (!manual && !bypassThrottle) {
            long lastCheck = prefs.getLong(KEY_LAST_CHECK, 0L);
            if (System.currentTimeMillis() - lastCheck < AUTO_CHECK_INTERVAL_MS) {
                return;
            }
        }
        if (!checking.compareAndSet(false, true)) {
            if (manual) {
                Toast.makeText(activity, R.string.app_update_check_in_progress,
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (manual) {
            Toast.makeText(activity, R.string.app_update_checking,
                    Toast.LENGTH_SHORT).show();
        }

        executor.execute(() -> {
            try {
                Release release = fetchLatestRelease();
                String currentVersion = getCurrentVersion();
                Log.i(TAG, "Latest release " + release.tagName
                        + ", installed " + currentVersion);
                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
                mainHandler.post(() -> {
                    if (!isActivityUsable()) {
                        return;
                    }
                    if (ReleaseVersion.isNewer(release.tagName, currentVersion)) {
                        if (activityResumed) {
                            showUpdateDialog(release, currentVersion);
                        } else {
                            pendingRelease = release;
                            pendingCurrentVersion = currentVersion;
                        }
                    } else if (manual) {
                        Toast.makeText(activity,
                                activity.getString(R.string.app_update_up_to_date,
                                        currentVersion),
                                Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "Release check failed", e);
                if (manual) {
                    mainHandler.post(() -> {
                        if (isActivityUsable()) {
                            Toast.makeText(activity,
                                    activity.getString(R.string.app_update_check_error,
                                            readableError(e)),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            } finally {
                checking.set(false);
            }
        });
    }

    private void showUpdateDialog(Release release, String currentVersion) {
        StringBuilder message = new StringBuilder(activity.getString(
                R.string.app_update_available_message, currentVersion, release.tagName));
        if (!release.notes.isEmpty()) {
            String notes = release.notes.length() > 2500
                    ? release.notes.substring(0, 2500) + "…" : release.notes;
            message.append("\n\n").append(notes);
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.app_update_available_title)
                .setMessage(message)
                .setPositiveButton(R.string.app_update_download,
                        (dialog, which) -> enqueueDownload(release))
                .setNegativeButton(R.string.app_update_later, null)
                .show();
    }

    private void enqueueDownload(Release release) {
        long existingId = prefs.getLong(KEY_DOWNLOAD_ID, NO_DOWNLOAD);
        String existingVersion = prefs.getString(KEY_DOWNLOAD_VERSION, "");
        if (existingId != NO_DOWNLOAD && release.tagName.equals(existingVersion)) {
            DownloadState state = queryDownload(existingId);
            if (state != null && state.status == DownloadManager.STATUS_PAUSED) {
                showPausedDownloadDialog(release, existingId, state);
                return;
            }
            if (state != null && (state.status == DownloadManager.STATUS_PENDING
                    || state.status == DownloadManager.STATUS_RUNNING)) {
                Toast.makeText(activity,
                        activity.getString(R.string.app_update_download_progress,
                                formatProgress(state)),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            if (state != null && state.status == DownloadManager.STATUS_SUCCESSFUL) {
                processCompletedDownload(true);
                return;
            }
            downloadManager.remove(existingId);
            clearPendingDownload();
        }

        File downloadsDir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloadsDir == null) {
            Toast.makeText(activity, R.string.app_update_download_failed,
                    Toast.LENGTH_LONG).show();
            return;
        }
        String safeName = release.assetName.replaceAll("[^A-Za-z0-9._-]", "_");
        File oldFile = new File(downloadsDir, safeName);
        if (oldFile.exists() && !oldFile.delete()) {
            Log.w(TAG, "Could not delete old update APK: " + oldFile);
        }

        try {
            DownloadManager.Request request = new DownloadManager.Request(
                    Uri.parse(release.downloadUrl))
                    .setTitle(activity.getString(R.string.app_update_download_title,
                            release.tagName))
                    .setDescription(activity.getString(R.string.app_update_download_description))
                    .setMimeType(APK_MIME)
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(false)
                    .setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(activity,
                            Environment.DIRECTORY_DOWNLOADS, safeName);
            long downloadId = downloadManager.enqueue(request);
            prefs.edit()
                    .putLong(KEY_DOWNLOAD_ID, downloadId)
                    .putString(KEY_DOWNLOAD_VERSION, release.tagName)
                    .putString(KEY_DOWNLOAD_DIGEST, release.digest)
                    .putLong(KEY_DOWNLOAD_STARTED_AT, System.currentTimeMillis())
                    .apply();
            Toast.makeText(activity, R.string.app_update_download_started,
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.w(TAG, "Could not enqueue update", e);
            Toast.makeText(activity,
                    activity.getString(R.string.app_update_check_error, readableError(e)),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void checkPendingDownload() {
        long downloadId = prefs.getLong(KEY_DOWNLOAD_ID, NO_DOWNLOAD);
        if (downloadId == NO_DOWNLOAD) {
            return;
        }
        String targetVersion = prefs.getString(KEY_DOWNLOAD_VERSION, "");
        try {
            if (!ReleaseVersion.isNewer(targetVersion, getCurrentVersion())) {
                clearPendingDownload();
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not inspect pending update", e);
        }

        DownloadState state = queryDownload(downloadId);
        if (state != null && state.status == DownloadManager.STATUS_SUCCESSFUL) {
            processCompletedDownload(false);
        } else if (state == null || state.status == DownloadManager.STATUS_FAILED) {
            clearPendingDownload();
            checkForUpdates(false, true);
        } else if (state.status == DownloadManager.STATUS_PAUSED
                && isStalePausedDownload(state)) {
            downloadManager.remove(downloadId);
            clearPendingDownload();
            if (activityResumed) {
                Toast.makeText(activity, R.string.app_update_download_stale,
                        Toast.LENGTH_LONG).show();
            }
            checkForUpdates(false, true);
        }
    }

    @Nullable
    private DownloadState queryDownload(long downloadId) {
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
        try (Cursor cursor = downloadManager.query(query)) {
            if (cursor != null && cursor.moveToFirst()) {
                return new DownloadState(
                        cursor.getInt(cursor.getColumnIndexOrThrow(
                                DownloadManager.COLUMN_STATUS)),
                        cursor.getInt(cursor.getColumnIndexOrThrow(
                                DownloadManager.COLUMN_REASON)),
                        cursor.getLong(cursor.getColumnIndexOrThrow(
                                DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                        cursor.getLong(cursor.getColumnIndexOrThrow(
                                DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                        cursor.getLong(cursor.getColumnIndexOrThrow(
                                DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)));
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not query update download", e);
        }
        return null;
    }

    private void showPausedDownloadDialog(Release release, long downloadId,
                                          DownloadState state) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.app_update_download_paused_title)
                .setMessage(activity.getString(R.string.app_update_download_paused_message,
                        pausedReason(state.reason), formatProgress(state)))
                .setPositiveButton(R.string.app_update_download_restart,
                        (dialog, which) -> {
                            downloadManager.remove(downloadId);
                            clearPendingDownload();
                            enqueueDownload(release);
                        })
                .setNegativeButton(R.string.app_update_download_wait, null)
                .show();
    }

    private String pausedReason(int reason) {
        switch (reason) {
            case DownloadManager.PAUSED_WAITING_TO_RETRY:
                return activity.getString(R.string.app_update_paused_waiting_retry);
            case DownloadManager.PAUSED_WAITING_FOR_NETWORK:
                return activity.getString(R.string.app_update_paused_waiting_network);
            case DownloadManager.PAUSED_QUEUED_FOR_WIFI:
                return activity.getString(R.string.app_update_paused_waiting_wifi);
            default:
                return activity.getString(R.string.app_update_paused_unknown);
        }
    }

    private String formatProgress(DownloadState state) {
        double downloadedMb = Math.max(0L, state.downloadedBytes) / (1024d * 1024d);
        if (state.totalBytes > 0L) {
            double totalMb = state.totalBytes / (1024d * 1024d);
            return String.format(Locale.getDefault(), "%.1f / %.1f MB",
                    downloadedMb, totalMb);
        }
        return String.format(Locale.getDefault(), "%.1f MB", downloadedMb);
    }

    private boolean isStalePausedDownload(DownloadState state) {
        long startedAt = prefs.getLong(KEY_DOWNLOAD_STARTED_AT, 0L);
        long lastActivity = Math.max(startedAt, state.lastModified);
        return lastActivity > 0L
                && System.currentTimeMillis() - lastActivity >= STALE_PAUSED_DOWNLOAD_MS;
    }

    private void processCompletedDownload(boolean reportErrors) {
        if (!verifying.compareAndSet(false, true)) {
            return;
        }
        long downloadId = prefs.getLong(KEY_DOWNLOAD_ID, NO_DOWNLOAD);
        if (downloadId == NO_DOWNLOAD) {
            verifying.set(false);
            return;
        }
        executor.execute(() -> {
            Uri apkUri = null;
            Exception error = null;
            try {
                apkUri = downloadManager.getUriForDownloadedFile(downloadId);
                if (apkUri == null) {
                    throw new IOException("Downloaded APK is unavailable");
                }
                verifyDigest(apkUri, prefs.getString(KEY_DOWNLOAD_DIGEST, ""));
            } catch (Exception e) {
                error = e;
            }
            Uri resultUri = apkUri;
            Exception resultError = error;
            verifying.set(false);
            mainHandler.post(() -> {
                if (!isActivityUsable() || !activityResumed) {
                    return;
                }
                if (resultError != null) {
                    Log.w(TAG, "Downloaded APK verification failed", resultError);
                    downloadManager.remove(downloadId);
                    clearPendingDownload();
                    if (reportErrors) {
                        Toast.makeText(activity, R.string.app_update_integrity_failed,
                                Toast.LENGTH_LONG).show();
                    }
                    return;
                }
                requestInstall(resultUri);
            });
        });
    }

    private void verifyDigest(Uri apkUri, String expectedDigest) throws Exception {
        if (expectedDigest == null || expectedDigest.isEmpty()) {
            return;
        }
        String prefix = "sha256:";
        if (!expectedDigest.toLowerCase(Locale.US).startsWith(prefix)) {
            throw new IOException("Unsupported release digest");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = activity.getContentResolver().openInputStream(apkUri)) {
            if (input == null) {
                throw new IOException("Could not open downloaded APK");
            }
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder actual = new StringBuilder(prefix);
        for (byte value : digest.digest()) {
            actual.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        if (!expectedDigest.equalsIgnoreCase(actual.toString())) {
            throw new IOException("APK digest mismatch");
        }
    }

    private void requestInstall(Uri apkUri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            pendingInstallUri = apkUri;
            new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.app_update_install_permission_title)
                    .setMessage(R.string.app_update_install_permission_message)
                    .setPositiveButton(R.string.app_update_open_settings, (dialog, which) -> {
                        waitingForInstallPermission = true;
                        Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.getPackageName()));
                        activity.startActivity(settings);
                    })
                    .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                        waitingForInstallPermission = false;
                        pendingInstallUri = null;
                    })
                    .show();
            return;
        }
        launchInstaller(apkUri);
    }

    private void launchInstaller(Uri apkUri) {
        Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(apkUri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        install.setClipData(ClipData.newRawUri("Viby update", apkUri));
        try {
            activity.startActivity(install);
            clearPendingDownload();
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "Package installer is unavailable", e);
            Toast.makeText(activity, R.string.app_update_installer_unavailable,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void clearPendingDownload() {
        prefs.edit()
                .remove(KEY_DOWNLOAD_ID)
                .remove(KEY_DOWNLOAD_VERSION)
                .remove(KEY_DOWNLOAD_DIGEST)
                .remove(KEY_DOWNLOAD_STARTED_AT)
                .apply();
    }

    private Release fetchLatestRelease() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_RELEASE_API)
                .openConnection();
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(12_000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "Viby-Android");
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub HTTP " + responseCode);
            }
            JSONObject json = new JSONObject(readUtf8(connection.getInputStream()));
            String tagName = json.getString("tag_name");
            String notes = json.optString("body", "").trim();
            JSONArray assets = json.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                String name = asset.optString("name", "");
                if (!"uploaded".equals(asset.optString("state"))
                        || !name.toLowerCase(Locale.US).endsWith(".apk")) {
                    continue;
                }
                String downloadUrl = asset.getString("browser_download_url");
                Uri uri = Uri.parse(downloadUrl);
                if (!"https".equalsIgnoreCase(uri.getScheme())
                        || !"github.com".equalsIgnoreCase(uri.getHost())) {
                    throw new IOException("Unexpected release asset URL");
                }
                return new Release(tagName, notes, name, downloadUrl,
                        asset.optString("digest", ""));
            }
            throw new IOException("Release has no APK asset");
        } finally {
            connection.disconnect();
        }
    }

    private String getCurrentVersion() throws Exception {
        PackageInfo info = activity.getPackageManager().getPackageInfo(
                activity.getPackageName(), 0);
        return info.versionName != null ? info.versionName : "0";
    }

    private boolean isActivityUsable() {
        return !activity.isFinishing() && !activity.isDestroyed();
    }

    private static String readUtf8(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output =
                     new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8 * 1024];
            int read;
            while ((read = source.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String readableError(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private static final class Release {
        final String tagName;
        final String notes;
        final String assetName;
        final String downloadUrl;
        final String digest;

        Release(String tagName, String notes, String assetName,
                String downloadUrl, String digest) {
            this.tagName = tagName;
            this.notes = notes;
            this.assetName = assetName;
            this.downloadUrl = downloadUrl;
            this.digest = digest;
        }
    }

    private static final class DownloadState {
        final int status;
        final int reason;
        final long downloadedBytes;
        final long totalBytes;
        final long lastModified;

        DownloadState(int status, int reason, long downloadedBytes,
                      long totalBytes, long lastModified) {
            this.status = status;
            this.reason = reason;
            this.downloadedBytes = downloadedBytes;
            this.totalBytes = totalBytes;
            this.lastModified = lastModified;
        }
    }
}
