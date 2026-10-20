package com.example.viby.ui;

import android.app.Dialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.core.widget.NestedScrollView;

import com.example.viby.R;
import com.example.viby.lyrics.YoutubeLyricsRepository;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Pull-up sheet that loads the current track's available YouTube subtitle text. */
public class LyricsBottomSheetDialogFragment extends BottomSheetDialogFragment {

    private static final String TAG = "LyricsSheet";
    private static final String ARG_MEDIA_ID = "media_id";
    private static final String ARG_VIDEO_ID = "video_id";
    private static final String ARG_SOURCE_URL = "source_url";
    private static final String ARG_TITLE = "title";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int requestGeneration;

    private ProgressBar progress;
    private TextView status;
    private TextView source;
    private TextView lyricsText;
    private NestedScrollView scroll;

    static LyricsBottomSheetDialogFragment newInstance(MediaItem item) {
        LyricsBottomSheetDialogFragment fragment = new LyricsBottomSheetDialogFragment();
        Bundle arguments = new Bundle();
        arguments.putString(ARG_MEDIA_ID, item.mediaId);
        if (item.mediaMetadata.title != null) {
            arguments.putString(ARG_TITLE, item.mediaMetadata.title.toString());
        }
        Bundle extras = item.mediaMetadata.extras;
        if (extras != null) {
            arguments.putString(ARG_VIDEO_ID,
                    extras.getString(PlaybackQueueController.METADATA_VIDEO_ID));
            arguments.putString(ARG_SOURCE_URL,
                    extras.getString(PlaybackQueueController.METADATA_SOURCE_URL));
        }
        fragment.setArguments(arguments);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_lyrics, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        progress = view.findViewById(R.id.lyricsProgress);
        status = view.findViewById(R.id.lyricsStatus);
        source = view.findViewById(R.id.lyricsSource);
        lyricsText = view.findViewById(R.id.lyricsText);
        scroll = view.findViewById(R.id.lyricsScroll);

        Bundle arguments = requireArguments();
        String title = arguments.getString(ARG_TITLE);
        ((TextView) view.findViewById(R.id.lyricsTitle)).setText(
                title != null && !title.isEmpty() ? title : getString(R.string.lyrics_title));
        loadLyrics(arguments);
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog instanceof BottomSheetDialog) {
            View sheet = ((BottomSheetDialog) dialog)
                    .findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) {
                int height = (int) (getResources().getDisplayMetrics().heightPixels * 0.82f);
                sheet.getLayoutParams().height = height;
                sheet.requestLayout();
                BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(sheet);
                behavior.setSkipCollapsed(true);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        }
    }

    private void loadLyrics(Bundle arguments) {
        int generation = ++requestGeneration;
        String mediaId = arguments.getString(ARG_MEDIA_ID, "");
        String videoId = arguments.getString(ARG_VIDEO_ID);
        String sourceUrl = arguments.getString(ARG_SOURCE_URL);
        android.content.Context app = requireContext().getApplicationContext();
        worker.execute(() -> {
            try {
                YoutubeLyricsRepository.Lyrics lyrics = YoutubeLyricsRepository.load(
                        app, mediaId, videoId, sourceUrl);
                mainHandler.post(() -> showLyrics(generation, lyrics));
            } catch (YoutubeLyricsRepository.MissingLyricsException missing) {
                mainHandler.post(() -> showMissing(generation, missing.reason));
            } catch (Exception error) {
                mainHandler.post(() -> showError(generation, error));
            }
        });
    }

    private void showLyrics(int generation, YoutubeLyricsRepository.Lyrics lyrics) {
        if (!canUpdate(generation)) {
            return;
        }
        progress.setVisibility(View.GONE);
        status.setVisibility(View.GONE);
        if (lyrics.source == YoutubeLyricsRepository.Source.YOUTUBE_MUSIC) {
            if (lyrics.detail == null || lyrics.detail.isEmpty()) {
                source.setText(R.string.lyrics_source_music);
            } else {
                source.setText(getString(R.string.lyrics_source_music_attributed,
                        lyrics.detail));
            }
        } else {
            source.setText(getString(
                    lyrics.source == YoutubeLyricsRepository.Source.AUTOMATIC_SUBTITLES
                            ? R.string.lyrics_source_automatic : R.string.lyrics_source_manual,
                    lyrics.detail));
        }
        lyricsText.setText(lyrics.text);
        scroll.setVisibility(View.VISIBLE);
        scroll.scrollTo(0, 0);
    }

    private void showMissing(int generation,
                             YoutubeLyricsRepository.MissingReason reason) {
        if (!canUpdate(generation)) {
            return;
        }
        progress.setVisibility(View.GONE);
        source.setText("");
        status.setText(reason == YoutubeLyricsRepository.MissingReason.NO_YOUTUBE_SOURCE
                ? R.string.lyrics_local_track : R.string.lyrics_not_found);
    }

    private void showError(int generation, Exception error) {
        if (!canUpdate(generation)) {
            return;
        }
        Log.e(TAG, "Could not load YouTube lyrics", error);
        progress.setVisibility(View.GONE);
        source.setText("");
        status.setText(R.string.lyrics_load_failed);
    }

    private boolean canUpdate(int generation) {
        return generation == requestGeneration && isAdded() && status != null;
    }

    @Override
    public void onDestroyView() {
        requestGeneration++;
        progress = null;
        status = null;
        source = null;
        lyricsText = null;
        scroll = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
