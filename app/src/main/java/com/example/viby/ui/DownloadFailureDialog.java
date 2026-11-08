package com.example.viby.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.Toast;

import com.example.viby.R;
import com.example.viby.data.Track;
import com.example.viby.download.DownloadService;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** "Why did this not download?" dialog shared by playlists and the library. */
final class DownloadFailureDialog {

    private DownloadFailureDialog() {
    }

    static void show(Context context, Track track, Runnable onRemove) {
        String reason = track.downloadErrorMessage != null
                ? track.downloadErrorMessage
                : context.getString(R.string.download_error_unknown);
        String diagnostic = context.getString(R.string.download_diagnostic_template,
                track.title,
                track.videoId != null ? track.videoId : "—",
                reason,
                track.downloadErrorCode != null ? track.downloadErrorCode : "UNKNOWN",
                track.downloadAttemptCount,
                track.downloadFormat != null ? track.downloadFormat : "—",
                track.downloadRequiresAuthorization ? "yes" : "no");
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.download_failure_reason_title)
                .setMessage(reason)
                .setPositiveButton(R.string.download_retry_one, (dialog, which) ->
                        DownloadService.retryPersistedTrack(context, track))
                .setNegativeButton(R.string.download_remove_failed,
                        (dialog, which) -> onRemove.run())
                .setNeutralButton(R.string.download_copy_diagnostic, (dialog, which) -> {
                    ClipboardManager clipboard =
                            context.getSystemService(ClipboardManager.class);
                    clipboard.setPrimaryClip(ClipData.newPlainText(
                            "Viby download diagnostic", diagnostic));
                    Toast.makeText(context, R.string.download_diagnostic_copied,
                            Toast.LENGTH_SHORT).show();
                })
                .show();
    }
}
