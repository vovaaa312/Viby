package com.example.viby.library;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import com.example.viby.R;
import com.example.viby.data.PlaylistSource;
import com.example.viby.data.Track;
import com.example.viby.data.TrackDao;
import com.example.viby.data.VibyDatabase;
import com.example.viby.download.DownloadService;
import com.example.viby.util.StorageHelper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Library safety operations: backups, playlist export, mirror folder, relinking and cleanup.
 * All work runs on one background thread so file operations never overlap each other.
 */
public final class LibraryMaintenance {

    private static final String TAG = "LibraryMaintenance";
    /** Temporary files untouched this long are leftovers of crashed or abandoned downloads. */
    public static final long STALE_TEMP_AGE_MS = 3L * 24L * 60L * 60L * 1000L;
    /** Backups larger than this are not Viby backups and would only waste memory. */
    private static final int MAX_BACKUP_BYTES = 32 * 1024 * 1024;

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    /** First tag scan after an update reads every file; keep it off the maintenance queue. */
    private static final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean autoSyncQueued = new AtomicBoolean();

    public interface Task<T> {
        T run() throws Exception;
    }

    public interface Callback<T> {
        void onResult(@Nullable T result, @Nullable Exception error);
    }

    public static final class StorageReport {
        public StorageScanner.Report files;
        public long freeBytes;
        public long totalBytes;
        public long cacheBytes;
        public int trackRows;
        public int distinctTracks;
        public int missingFiles;
        public int notDownloaded;
        public int failed;
        public final Map<String, Integer> playlistCounts = new LinkedHashMap<>();
    }

    public static final class RestoreResult {
        @Nullable public LibraryBackupImporter.Result imported;
        public LibraryRelinker.Result relinked;
    }

    public static final class MirrorResult {
        public LibraryFolderSync.Result files;
        public int playlists;
    }

    private LibraryMaintenance() {
    }

    /** Runs {@code task} in the background and delivers the outcome on the main thread. */
    public static <T> void run(Task<T> task, Callback<T> callback) {
        executor.execute(() -> {
            try {
                T result = task.run();
                mainHandler.post(() -> callback.onResult(result, null));
            } catch (Exception error) {
                Log.w(TAG, "Library task failed", error);
                mainHandler.post(() -> callback.onResult(null, error));
            }
        });
    }

    // ------------------------------------------------------------------ report

    public static StorageReport buildReport(Context context) {
        File root = StorageHelper.musicRoot(context);
        TrackDao dao = VibyDatabase.get(context).trackDao();
        List<Track> tracks = dao.getAllSync();
        StorageReport report = new StorageReport();
        report.files = StorageScanner.scan(root, referencedPaths(tracks));
        report.freeBytes = root.getUsableSpace();
        report.totalBytes = root.getTotalSpace();
        report.cacheBytes = StorageScanner.directoryBytes(context.getCacheDir());
        report.trackRows = tracks.size();
        report.distinctTracks = LibraryBrowser.distinct(tracks).size();
        for (Track track : tracks) {
            Integer count = report.playlistCounts.get(track.playlistName);
            report.playlistCounts.put(track.playlistName, count != null ? count + 1 : 1);
            if (LibraryBrowser.isFailed(track)) {
                report.failed++;
            } else if (!track.downloaded) {
                report.notDownloaded++;
            } else if (track.filePath.isEmpty() || !new File(track.filePath).isFile()) {
                report.missingFiles++;
            }
        }
        return report;
    }

    private static Set<String> referencedPaths(List<Track> tracks) {
        Set<String> paths = new HashSet<>();
        for (Track track : tracks) {
            if (!track.filePath.isEmpty()) {
                paths.add(new File(track.filePath).getAbsolutePath());
            }
        }
        return paths;
    }

    // ----------------------------------------------------------------- cleanup

    /** Deletes the given files and then any folders left empty under the Music root. */
    public static int deleteFiles(Context context, List<File> files) {
        int deleted = 0;
        for (File file : files) {
            if (file.delete()) {
                deleted++;
            }
        }
        File root = StorageHelper.musicRoot(context);
        StorageScanner.Report report = StorageScanner.scan(root, Collections.emptySet());
        for (File directory : report.emptyDirectories) {
            //noinspection ResultOfMethodCallIgnored
            directory.delete();
        }
        return deleted;
    }

    /** Re-checks references right before deleting, so a just-finished download survives. */
    public static int deleteOrphans(Context context, List<File> candidates) {
        Set<String> referenced = new HashSet<>(
                VibyDatabase.get(context).trackDao().getReferencedFilePathsSync());
        List<File> safe = new ArrayList<>();
        for (File file : candidates) {
            if (!referenced.contains(file.getAbsolutePath())) {
                safe.add(file);
            }
        }
        return deleteFiles(context, safe);
    }

    public static long clearCache(Context context) {
        long before = StorageScanner.directoryBytes(context.getCacheDir());
        deleteChildren(context.getCacheDir());
        return before - StorageScanner.directoryBytes(context.getCacheDir());
    }

    private static void deleteChildren(File directory) {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                deleteChildren(child);
            }
            //noinspection ResultOfMethodCallIgnored
            child.delete();
        }
    }

    /** Startup housekeeping: drops partial files of downloads abandoned days ago. */
    public static void cleanStaleTempFilesAsync(Context context) {
        Context app = context.getApplicationContext();
        executor.execute(() -> {
            try {
                List<File> stale = StorageScanner.staleTempFiles(
                        StorageHelper.musicRoot(app), STALE_TEMP_AGE_MS,
                        System.currentTimeMillis());
                for (File file : stale) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
                if (!stale.isEmpty()) {
                    Log.i(TAG, "Removed stale temporary files: " + stale.size());
                }
            } catch (Exception error) {
                Log.w(TAG, "Stale temp cleanup failed", error);
            }
        });
    }

    // ----------------------------------------------------------------- backups

    public static String libraryJson(Context context) throws Exception {
        VibyDatabase database = VibyDatabase.get(context);
        LibraryBackup.Snapshot snapshot = LibraryBackup.fromLibrary(
                database.trackDao().getAllSync(),
                database.playlistSourceDao().getAllSync(),
                StorageHelper.musicRoot(context), System.currentTimeMillis(),
                appVersion(context), LibraryBackup.SCOPE_LIBRARY);
        return LibraryBackup.toJson(snapshot);
    }

    public static String playlistJson(Context context, String playlist) throws Exception {
        VibyDatabase database = VibyDatabase.get(context);
        PlaylistSource source = database.playlistSourceDao().getSync(playlist);
        LibraryBackup.Snapshot snapshot = LibraryBackup.fromLibrary(
                database.trackDao().getPlaylistSync(playlist),
                source != null ? Collections.singletonList(source)
                        : Collections.emptyList(),
                StorageHelper.musicRoot(context), System.currentTimeMillis(),
                appVersion(context), LibraryBackup.SCOPE_PLAYLIST);
        return LibraryBackup.toJson(snapshot);
    }

    public static String playlistM3u(Context context, String playlist) {
        return PlaylistExporter.toM3u(playlist,
                VibyDatabase.get(context).trackDao().getPlaylistSync(playlist),
                StorageHelper.musicRoot(context));
    }

    public static LibraryBackupImporter.Result importLibrary(Context context, String json)
            throws Exception {
        LibraryBackup.Snapshot snapshot = LibraryBackup.parse(json);
        VibyDatabase database = VibyDatabase.get(context);
        LibraryBackupImporter.Result result = new LibraryBackupImporter.Result();
        database.runInTransaction(() -> {
            LibraryBackupImporter.Result imported = LibraryBackupImporter.create(
                    database.trackDao(), database.playlistSourceDao())
                    .importSnapshot(snapshot, StorageHelper.musicRoot(context),
                            context.getString(R.string.library_file_missing),
                            System.currentTimeMillis());
            result.added = imported.added;
            result.merged = imported.merged;
            result.playlistsRestored = imported.playlistsRestored;
            result.missingFiles = imported.missingFiles;
        });
        return result;
    }

    /** Imports a backup file and immediately tries to bring its files back. */
    public static RestoreResult importAndRelink(Context context, String json) throws Exception {
        RestoreResult result = new RestoreResult();
        result.imported = importLibrary(context, json);
        result.relinked = relink(context, LibraryFolderSync.open(context));
        return result;
    }

    // ------------------------------------------------------------------ relink

    public static LibraryRelinker.Result relink(Context context,
                                                @Nullable LibraryRelinker.Mirror mirror)
            throws IOException {
        if (!android.os.Environment.MEDIA_MOUNTED.equals(
                android.os.Environment.getExternalStorageState())) {
            // unmounted storage would make every file look missing
            throw new IOException(context.getString(R.string.storage_not_mounted));
        }
        TrackDao dao = VibyDatabase.get(context).trackDao();
        String missingMessage = context.getString(R.string.library_file_missing);
        LibraryRelinker relinker = new LibraryRelinker(new LibraryRelinker.Store() {
            @Override
            public void relink(Track track, String filePath) {
                dao.relinkFile(track.id, filePath);
            }

            @Override
            public void markMissing(Track track) {
                String source = track.sourceUrl != null ? track.sourceUrl
                        : LibraryBackupImporter.watchUrl(track.videoId);
                if (source != null) {
                    dao.markFileMissing(track.id, Track.DOWNLOAD_WAITING, null, null, source);
                } else {
                    dao.markFileMissing(track.id, Track.DOWNLOAD_FAILED_PERMANENT,
                            LibraryBackupImporter.ERROR_FILE_MISSING, missingMessage, null);
                }
            }
        });
        LibraryRelinker.Result result = relinker.relink(dao.getAllSync(),
                StorageHelper.musicRoot(context), mirror);
        if (result.relinked + result.restoredFromMirror > 0) {
            scanMetadata(context);
        }
        return result;
    }

    /** Restore after reinstall: read the mirrored backup, merge it, copy files back. */
    public static RestoreResult restoreFromMirror(Context context) throws Exception {
        LibraryFolderSync mirror = LibraryFolderSync.open(context);
        if (mirror == null) {
            throw new IOException(context.getString(R.string.storage_mirror_not_set));
        }
        RestoreResult result = new RestoreResult();
        String json = mirror.readRootText(LibraryBackup.MIRROR_FILE_NAME);
        if (json != null) {
            result.imported = importLibrary(context, json);
        }
        result.relinked = relink(context, mirror);
        return result;
    }

    // ------------------------------------------------------------------ mirror

    public static MirrorResult syncMirror(Context context) throws Exception {
        LibraryFolderSync mirror = LibraryFolderSync.open(context);
        if (mirror == null) {
            throw new IOException(context.getString(R.string.storage_mirror_not_set));
        }
        VibyDatabase database = VibyDatabase.get(context);
        List<Track> tracks = database.trackDao().getAllSync();
        File root = StorageHelper.musicRoot(context);
        Set<File> files = new LinkedHashSet<>();
        Set<String> playlists = new LinkedHashSet<>();
        for (Track track : tracks) {
            playlists.add(track.playlistName);
            if (track.downloaded && !track.filePath.isEmpty()) {
                File file = new File(track.filePath);
                if (file.isFile()) {
                    files.add(file);
                }
            }
        }
        MirrorResult result = new MirrorResult();
        result.files = mirror.mirrorFiles(files, root);
        for (String playlist : playlists) {
            mirror.writeRootText(PlaylistExporter.fileName(playlist, "m3u8"),
                    PlaylistExporter.toM3u(playlist,
                            database.trackDao().getPlaylistSync(playlist), root));
            result.playlists++;
        }
        mirror.writeRootText(LibraryBackup.MIRROR_FILE_NAME, libraryJson(context));
        StorageSettings.setLastSyncAt(context, System.currentTimeMillis());
        return result;
    }

    /** Called when a download job finishes; coalesces bursts into one background sync. */
    public static void requestAutoSync(Context context) {
        Context app = context.getApplicationContext();
        if (!StorageSettings.isAutoSync(app) || StorageSettings.mirrorTree(app) == null
                || !autoSyncQueued.compareAndSet(false, true)) {
            return;
        }
        executor.execute(() -> {
            autoSyncQueued.set(false);
            try {
                syncMirror(app);
            } catch (Exception error) {
                Log.w(TAG, "Automatic mirror sync failed", error);
            }
        });
    }

    // --------------------------------------------------------------- downloads

    /**
     * Queues single-track downloads for rows without a local file. Playlists with a running
     * job are skipped because that job already owns their placeholders.
     */
    public static int redownloadMissing(Context context, Set<String> busyPlaylists) {
        List<Track> tracks = VibyDatabase.get(context).trackDao().getAllSync();
        Set<String> queued = new HashSet<>();
        int count = 0;
        for (Track track : tracks) {
            if (track.downloaded || Track.DOWNLOAD_FAILED_PERMANENT.equals(track.downloadState)
                    || busyPlaylists.contains(track.playlistName)) {
                continue;
            }
            String url = track.sourceUrl != null ? track.sourceUrl
                    : LibraryBackupImporter.watchUrl(track.videoId);
            if (url == null || !queued.add(track.playlistName + '\u0000' + url)) {
                continue;
            }
            DownloadService.enqueue(context, url, track.playlistName, false);
            count++;
        }
        return count;
    }

    // ---------------------------------------------------------------- metadata

    /** Reads album tags for rows that were never scanned (downloads, restored files). */
    public static int scanMetadata(Context context) {
        TrackDao dao = VibyDatabase.get(context).trackDao();
        int scanned = 0;
        while (true) {
            List<Track> batch = dao.getUnscannedSync(100);
            if (batch.isEmpty()) {
                return scanned;
            }
            Set<String> seen = new HashSet<>();
            for (Track track : batch) {
                if (!seen.add(track.filePath)) {
                    continue;
                }
                dao.updateScannedMetadata(track.filePath, readAlbum(track.filePath));
                scanned++;
            }
        }
    }

    public static void scanMetadataAsync(Context context) {
        Context app = context.getApplicationContext();
        scanExecutor.execute(() -> {
            try {
                scanMetadata(app);
            } catch (Exception error) {
                Log.w(TAG, "Metadata scan failed", error);
            }
        });
    }

    @Nullable
    static String readAlbum(String filePath) {
        if (!new File(filePath).isFile()) {
            return null;
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(filePath);
            String album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
            return album != null && !album.trim().isEmpty() ? album.trim() : null;
        } catch (Exception ignored) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- file I/O

    public static void writeText(Context context, Uri uri, String text) throws IOException {
        OutputStream output;
        try {
            output = context.getContentResolver().openOutputStream(uri, "wt");
        } catch (IllegalArgumentException | UnsupportedOperationException unsupported) {
            output = context.getContentResolver().openOutputStream(uri, "w");
        }
        if (output == null) {
            throw new IOException("Cannot open " + uri);
        }
        try (OutputStream out = output) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static String readText(Context context, Uri uri) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException("Cannot open " + uri);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                bytes.write(buffer, 0, read);
                if (bytes.size() > MAX_BACKUP_BYTES) {
                    throw new IOException("File is too large for a Viby backup");
                }
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static String appVersion(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            return null;
        }
    }
}
