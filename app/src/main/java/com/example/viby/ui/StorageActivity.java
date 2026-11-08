package com.example.viby.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.viby.R;
import com.example.viby.download.DownloadJob;
import com.example.viby.download.DownloadService;
import com.example.viby.library.LibraryBackupImporter;
import com.example.viby.library.LibraryFolderSync;
import com.example.viby.library.LibraryMaintenance;
import com.example.viby.library.LibraryRelinker;
import com.example.viby.library.StorageSettings;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Storage usage, cleanup, backups, the SAF mirror folder and file relinking. */
public class StorageActivity extends AppCompatActivity {

    private LinearProgressIndicator progress;
    private TextView summary;
    private TextView folders;
    private Button cleanTemp;
    private Button cleanOrphans;
    private Button clearCache;
    private Button redownload;
    private TextView mirrorFolder;
    private Button syncNow;
    private Button restoreFolder;
    private MaterialSwitch autoSync;

    @Nullable private LibraryMaintenance.StorageReport report;
    private int runningTasks;

    private final ActivityResultLauncher<String> exportLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"), this::exportTo);
    private final ActivityResultLauncher<String[]> importLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::importFrom);
    private final ActivityResultLauncher<Uri> folderLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(), this::onFolderChosen);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_storage);
        MaterialToolbar toolbar = findViewById(R.id.storageToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        progress = findViewById(R.id.storageProgress);
        summary = findViewById(R.id.storageSummary);
        folders = findViewById(R.id.storageFolders);
        cleanTemp = findViewById(R.id.storageCleanTemp);
        cleanOrphans = findViewById(R.id.storageCleanOrphans);
        clearCache = findViewById(R.id.storageClearCache);
        redownload = findViewById(R.id.storageRedownload);
        mirrorFolder = findViewById(R.id.storageMirrorFolder);
        syncNow = findViewById(R.id.storageSyncNow);
        restoreFolder = findViewById(R.id.storageRestoreFolder);
        autoSync = findViewById(R.id.storageAutoSync);

        cleanTemp.setOnClickListener(v -> confirmCleanTemp());
        cleanOrphans.setOnClickListener(v -> confirmCleanOrphans());
        clearCache.setOnClickListener(v -> runTask(
                () -> LibraryMaintenance.clearCache(getApplicationContext()),
                freed -> {
                    toast(getString(R.string.storage_cache_cleared, size(freed)));
                    refresh();
                }));
        findViewById(R.id.storageRelink).setOnClickListener(v -> relink());
        redownload.setOnClickListener(v -> redownloadMissing());
        findViewById(R.id.storageExport).setOnClickListener(v -> exportLauncher.launch(
                "viby-library-" + new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                        .format(new Date()) + ".json"));
        findViewById(R.id.storageImport).setOnClickListener(v -> importLauncher.launch(
                new String[]{"application/json", "text/plain", "application/octet-stream"}));
        findViewById(R.id.storageChooseFolder).setOnClickListener(
                v -> folderLauncher.launch(StorageSettings.mirrorTree(this)));
        syncNow.setOnClickListener(v -> syncMirror());
        restoreFolder.setOnClickListener(v -> restoreFromMirror());
        autoSync.setChecked(StorageSettings.isAutoSync(this));
        autoSync.setOnCheckedChangeListener(
                (button, checked) -> StorageSettings.setAutoSync(this, checked));

        renderMirror();
        render();
        refresh();
    }

    // ------------------------------------------------------------------ report

    private void refresh() {
        runTask(() -> LibraryMaintenance.buildReport(getApplicationContext()), result -> {
            report = result;
            render();
        });
    }

    private void render() {
        LibraryMaintenance.StorageReport r = report;
        if (r == null) {
            summary.setText(R.string.storage_scanning);
            folders.setText("");
            cleanTemp.setText(getString(R.string.storage_clean_temp, 0, size(0)));
            cleanOrphans.setText(getString(R.string.storage_clean_orphans, 0, size(0)));
            clearCache.setText(getString(R.string.storage_clear_cache, size(0)));
            redownload.setText(getString(R.string.storage_redownload, 0));
            cleanTemp.setEnabled(false);
            cleanOrphans.setEnabled(false);
            redownload.setEnabled(false);
            return;
        }
        summary.setText(getString(R.string.storage_summary,
                size(r.freeBytes), size(r.totalBytes),
                size(r.files.libraryBytes), r.files.libraryFiles,
                r.distinctTracks, r.trackRows, r.playlistCounts.size(),
                r.notDownloaded, r.failed, r.missingFiles));
        StringBuilder folderText = new StringBuilder();
        for (Map.Entry<String, Long> entry : r.files.folderBytes.entrySet()) {
            if (folderText.length() > 0) {
                folderText.append('\n');
            }
            String name = entry.getKey().isEmpty() ? "/" : entry.getKey();
            folderText.append(name).append(" — ").append(size(entry.getValue()));
        }
        folders.setText(folderText.length() > 0 ? folderText
                : getString(R.string.storage_no_files));
        cleanTemp.setText(getString(R.string.storage_clean_temp,
                r.files.tempFiles.size(), size(r.files.tempBytes)));
        cleanOrphans.setText(getString(R.string.storage_clean_orphans,
                r.files.orphanFiles.size(), size(r.files.orphanBytes)));
        clearCache.setText(getString(R.string.storage_clear_cache, size(r.cacheBytes)));
        redownload.setText(getString(R.string.storage_redownload, r.notDownloaded));
        cleanTemp.setEnabled(!r.files.tempFiles.isEmpty()
                || !r.files.emptyDirectories.isEmpty());
        cleanOrphans.setEnabled(!r.files.orphanFiles.isEmpty());
        redownload.setEnabled(r.notDownloaded > 0);
    }

    private void renderMirror() {
        Uri tree = StorageSettings.mirrorTree(this);
        boolean usable = tree != null && LibraryFolderSync.hasWritePermission(this, tree);
        if (tree == null) {
            mirrorFolder.setText(R.string.storage_mirror_none);
        } else if (!usable) {
            mirrorFolder.setText(getString(R.string.storage_mirror_lost,
                    LibraryFolderSync.displayName(tree)));
        } else {
            long last = StorageSettings.lastSyncAt(this);
            String lastText = last > 0
                    ? android.text.format.DateUtils.getRelativeTimeSpanString(last).toString()
                    : getString(R.string.storage_mirror_never);
            mirrorFolder.setText(getString(R.string.storage_mirror_current,
                    LibraryFolderSync.displayName(tree), lastText));
        }
        syncNow.setEnabled(usable);
        restoreFolder.setEnabled(usable);
        autoSync.setEnabled(usable);
    }

    // ----------------------------------------------------------------- cleanup

    private void confirmCleanTemp() {
        LibraryMaintenance.StorageReport r = report;
        if (r == null || refuseWhileDownloading()) {
            return;
        }
        List<java.io.File> files = r.files.tempFiles;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.storage_section_cleanup)
                .setMessage(getString(R.string.storage_clean_temp_confirm,
                        files.size(), size(r.files.tempBytes)))
                .setPositiveButton(R.string.btn_delete, (d, w) -> runTask(
                        () -> LibraryMaintenance.deleteFiles(getApplicationContext(), files),
                        deleted -> {
                            toast(getString(R.string.storage_files_deleted, deleted));
                            refresh();
                        }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmCleanOrphans() {
        LibraryMaintenance.StorageReport r = report;
        if (r == null || refuseWhileDownloading()) {
            return;
        }
        List<java.io.File> files = r.files.orphanFiles;
        // folder/name, so duplicates in different playlists can be told apart before deleting
        java.io.File musicRoot = com.example.viby.util.StorageHelper.musicRoot(this);
        StringBuilder names = new StringBuilder("\n");
        int shown = Math.min(files.size(), 100);
        for (int i = 0; i < shown; i++) {
            String relative = com.example.viby.library.LibraryPaths.relativePath(
                    musicRoot, files.get(i).getAbsolutePath());
            names.append("\n• ").append(relative != null ? relative : files.get(i).getName());
        }
        if (files.size() > shown) {
            names.append("\n…");
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.storage_section_cleanup)
                .setMessage(getString(R.string.storage_clean_orphans_confirm,
                        files.size(), size(r.files.orphanBytes)) + names)
                .setPositiveButton(R.string.btn_delete, (d, w) -> runTask(
                        () -> LibraryMaintenance.deleteOrphans(getApplicationContext(), files),
                        deleted -> {
                            toast(getString(R.string.storage_files_deleted, deleted));
                            refresh();
                        }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Partial files of the running download must not disappear under yt-dlp. */
    private boolean refuseWhileDownloading() {
        if (!hasActiveDownloads()) {
            return false;
        }
        toast(getString(R.string.storage_busy_downloads));
        return true;
    }

    private static boolean hasActiveDownloads() {
        List<DownloadJob> jobs = DownloadService.getJobs().getValue();
        if (jobs != null) {
            for (DownloadJob job : jobs) {
                if (job.isActive()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<String> activeDownloadPlaylists() {
        Set<String> playlists = new HashSet<>();
        List<DownloadJob> jobs = DownloadService.getJobs().getValue();
        if (jobs != null) {
            for (DownloadJob job : jobs) {
                if (job.isActive() && job.playlistName != null) {
                    playlists.add(job.playlistName);
                }
            }
        }
        return playlists;
    }

    // ------------------------------------------------------------ files/relink

    private void relink() {
        Context app = getApplicationContext();
        runTask(() -> LibraryMaintenance.relink(app, LibraryFolderSync.open(app)), result -> {
            showResult(R.string.storage_relink, relinkText(result));
            refresh();
        });
    }

    private void redownloadMissing() {
        Set<String> busy = activeDownloadPlaylists();
        Context app = getApplicationContext();
        runTask(() -> LibraryMaintenance.redownloadMissing(app, busy), count -> {
            toast(getString(R.string.storage_redownload_started, count));
            refresh();
        });
    }

    // ----------------------------------------------------------------- backups

    private void exportTo(@Nullable Uri uri) {
        if (uri == null) {
            return;
        }
        Context app = getApplicationContext();
        runTask(() -> {
            LibraryMaintenance.writeText(app, uri, LibraryMaintenance.libraryJson(app));
            return Boolean.TRUE;
        }, ignored -> toast(getString(R.string.storage_export_done)));
    }

    private void importFrom(@Nullable Uri uri) {
        if (uri == null) {
            return;
        }
        Context app = getApplicationContext();
        runTask(() -> LibraryMaintenance.importAndRelink(app,
                LibraryMaintenance.readText(app, uri)), result -> {
            showResult(R.string.storage_import_library, restoreText(result));
            refresh();
        });
    }

    // ------------------------------------------------------------------ mirror

    private void onFolderChosen(@Nullable Uri tree) {
        if (tree == null) {
            return;
        }
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try {
            getContentResolver().takePersistableUriPermission(tree, flags);
        } catch (SecurityException denied) {
            toast(getString(R.string.storage_operation_failed, denied.getMessage()));
            return;
        }
        Uri previous = StorageSettings.mirrorTree(this);
        if (previous != null && !previous.equals(tree)) {
            try {
                getContentResolver().releasePersistableUriPermission(previous, flags);
            } catch (SecurityException ignored) {
                // already revoked by the system
            }
        }
        StorageSettings.setMirrorTree(this, tree);
        renderMirror();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.storage_section_mirror)
                .setMessage(R.string.storage_mirror_chosen)
                .setPositiveButton(R.string.storage_sync_now, (d, w) -> syncMirror())
                .setNeutralButton(R.string.storage_restore_folder, (d, w) -> restoreFromMirror())
                .setNegativeButton(R.string.storage_later, null)
                .show();
    }

    private void syncMirror() {
        Context app = getApplicationContext();
        toast(getString(R.string.storage_sync_started));
        runTask(() -> LibraryMaintenance.syncMirror(app), result -> {
            renderMirror();
            showResult(R.string.storage_sync_now, getString(R.string.storage_sync_result,
                    result.files.copied, size(result.files.copiedBytes),
                    result.files.skipped, result.files.failed, result.playlists));
        });
    }

    private void restoreFromMirror() {
        Context app = getApplicationContext();
        runTask(() -> LibraryMaintenance.restoreFromMirror(app), result -> {
            showResult(R.string.storage_restore_folder, restoreText(result));
            refresh();
        });
    }

    // ----------------------------------------------------------------- helpers

    private String restoreText(LibraryMaintenance.RestoreResult result) {
        StringBuilder text = new StringBuilder();
        LibraryBackupImporter.Result imported = result.imported;
        if (imported != null) {
            text.append(getString(R.string.storage_import_result, imported.added,
                    imported.merged, imported.playlistsRestored, imported.missingFiles))
                    .append("\n\n");
        } else {
            text.append(getString(R.string.storage_restore_no_backup)).append("\n\n");
        }
        return text.append(relinkText(result.relinked)).toString();
    }

    private String relinkText(LibraryRelinker.Result result) {
        return getString(R.string.storage_relink_result, result.checked, result.alreadyOk,
                result.relinked, result.restoredFromMirror, result.markedMissing,
                result.stillMissing);
    }

    private void showResult(int titleRes, String message) {
        if (isFinishing() || isDestroyed()) {
            toast(message);
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(titleRes)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private interface Success<T> {
        void accept(T result);
    }

    private <T> void runTask(LibraryMaintenance.Task<T> task, Success<T> onSuccess) {
        setBusy(+1);
        LibraryMaintenance.run(task, (result, error) -> {
            setBusy(-1);
            if (error != null) {
                toast(getString(R.string.storage_operation_failed,
                        error.getMessage() != null ? error.getMessage()
                                : error.getClass().getSimpleName()));
            } else if (!isDestroyed()) {
                onSuccess.accept(result);
            }
        });
    }

    private void setBusy(int delta) {
        runningTasks = Math.max(0, runningTasks + delta);
        if (!isDestroyed()) {
            progress.setVisibility(runningTasks > 0 ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private String size(long bytes) {
        return Formatter.formatShortFileSize(this, Math.max(0L, bytes));
    }

    private void toast(String message) {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
    }
}
