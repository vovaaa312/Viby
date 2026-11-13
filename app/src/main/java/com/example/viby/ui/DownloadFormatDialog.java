package com.example.viby.ui;

import android.content.Context;
import android.widget.Toast;

import com.example.viby.R;
import com.example.viby.download.DownloadSettings;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Format and quality of new downloads, shared by the download screen and the settings. */
final class DownloadFormatDialog {

    private static final DownloadSettings.Mp3Quality[] MP3_QUALITIES =
            DownloadSettings.Mp3Quality.values();

    private DownloadFormatDialog() {
    }

    private static String qualityLabel(Context context, DownloadSettings.Mp3Quality quality) {
        switch (quality) {
            case K320:
                return context.getString(R.string.download_quality_kbps, 320);
            case K192:
                return context.getString(R.string.download_quality_kbps, 192);
            case K128:
                return context.getString(R.string.download_quality_kbps, 128);
            default:
                return context.getString(R.string.download_quality_best);
        }
    }

    /** The current choice, e.g. "MP3 · best". */
    static String currentLabel(Context context) {
        return DownloadSettings.format(context) == DownloadSettings.Format.M4A
                ? context.getString(R.string.download_format_m4a)
                : context.getString(R.string.download_format_mp3,
                        qualityLabel(context, DownloadSettings.mp3Quality(context)));
    }

    /** One list: each MP3 bitrate plus M4A, so a single tap picks format and quality. */
    static void show(Context context, Runnable onChanged) {
        String[] labels = new String[MP3_QUALITIES.length + 1];
        for (int i = 0; i < MP3_QUALITIES.length; i++) {
            labels[i] = context.getString(R.string.download_format_mp3,
                    qualityLabel(context, MP3_QUALITIES[i]));
        }
        labels[MP3_QUALITIES.length] = context.getString(R.string.download_format_m4a);
        int checked = DownloadSettings.format(context) == DownloadSettings.Format.M4A
                ? MP3_QUALITIES.length : DownloadSettings.mp3Quality(context).ordinal();
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.download_format_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    if (which < MP3_QUALITIES.length) {
                        DownloadSettings.setFormat(context, DownloadSettings.Format.MP3);
                        DownloadSettings.setMp3Quality(context, MP3_QUALITIES[which]);
                    } else {
                        DownloadSettings.setFormat(context, DownloadSettings.Format.M4A);
                        Toast.makeText(context, R.string.download_format_m4a_hint,
                                Toast.LENGTH_LONG).show();
                    }
                    onChanged.run();
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
