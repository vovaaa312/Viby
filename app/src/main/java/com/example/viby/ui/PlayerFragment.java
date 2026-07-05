package com.example.viby.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.session.MediaController;

import com.bumptech.glide.Glide;
import com.example.viby.R;
import com.example.viby.data.Track;
import com.example.viby.ui.widget.WaveformView;
import com.example.viby.util.Formats;
import com.example.viby.util.WaveformExtractor;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.List;

/** Главная страница: обложка, волна перемотки (как в AIMP), prev/play/next, shuffle/repeat. */
public class PlayerFragment extends Fragment {

    private PlayerViewModel viewModel;
    private MediaController controller;

    private ImageView coverImage;
    private TextView trackTitle;
    private TextView trackArtist;
    private WaveformView waveform;
    private TextView positionText;
    private TextView durationText;
    private FloatingActionButton playButton;
    private ImageButton shuffleButton;
    private ImageButton repeatButton;
    private TextView trackCounter;

    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private boolean userSeeking;
    @Nullable
    private String currentWaveformPath;

    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            progressHandler.postDelayed(this, 500);
        }
    };

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onMediaMetadataChanged(@NonNull MediaMetadata mediaMetadata) {
            updateMetadata();
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            updatePlayButton();
        }

        @Override
        public void onPlaybackStateChanged(int playbackState) {
            updateProgress();
        }

        @Override
        public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
            updateModeButtons();
        }

        @Override
        public void onRepeatModeChanged(int repeatMode) {
            updateModeButtons();
        }

        @Override
        public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
            updateCounter();
            loadWaveform();
        }

        @Override
        public void onTimelineChanged(@NonNull Timeline timeline, int reason) {
            updateCounter();
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_player, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        viewModel = new ViewModelProvider(requireActivity()).get(PlayerViewModel.class);

        coverImage = view.findViewById(R.id.coverImage);
        trackTitle = view.findViewById(R.id.trackTitle);
        trackArtist = view.findViewById(R.id.trackArtist);
        waveform = view.findViewById(R.id.waveform);
        positionText = view.findViewById(R.id.positionText);
        durationText = view.findViewById(R.id.durationText);
        playButton = view.findViewById(R.id.playButton);
        shuffleButton = view.findViewById(R.id.shuffleButton);
        repeatButton = view.findViewById(R.id.repeatButton);
        trackCounter = view.findViewById(R.id.trackCounter);
        ImageButton prevButton = view.findViewById(R.id.prevButton);
        ImageButton nextButton = view.findViewById(R.id.nextButton);

        trackTitle.setSelected(true); // бегущая строка для длинных названий

        playButton.setOnClickListener(v -> {
            if (controller == null) {
                return;
            }
            if (controller.isPlaying()) {
                controller.pause();
            } else {
                controller.play();
            }
        });
        prevButton.setOnClickListener(v -> {
            if (controller != null) {
                controller.seekToPrevious();
            }
        });
        nextButton.setOnClickListener(v -> {
            if (controller != null) {
                controller.seekToNext();
            }
        });
        shuffleButton.setOnClickListener(v -> {
            if (controller != null) {
                controller.setShuffleModeEnabled(!controller.getShuffleModeEnabled());
            }
        });
        repeatButton.setOnClickListener(v -> {
            if (controller == null) {
                return;
            }
            switch (controller.getRepeatMode()) {
                case Player.REPEAT_MODE_OFF:
                    controller.setRepeatMode(Player.REPEAT_MODE_ALL);
                    break;
                case Player.REPEAT_MODE_ALL:
                    controller.setRepeatMode(Player.REPEAT_MODE_ONE);
                    break;
                default:
                    controller.setRepeatMode(Player.REPEAT_MODE_OFF);
                    break;
            }
        });

        waveform.setListener(new WaveformView.Listener() {
            @Override
            public void onSeekPreview(float fraction) {
                userSeeking = true;
                if (controller != null && controller.getDuration() > 0) {
                    positionText.setText(Formats.duration(
                            (long) (fraction * controller.getDuration())));
                }
            }

            @Override
            public void onSeek(float fraction) {
                userSeeking = false;
                if (controller != null && controller.getDuration() > 0) {
                    controller.seekTo((long) (fraction * controller.getDuration()));
                }
            }
        });

        viewModel.controller.observe(getViewLifecycleOwner(), newController -> {
            if (controller != null) {
                controller.removeListener(playerListener);
            }
            controller = newController;
            if (controller != null) {
                controller.addListener(playerListener);
            }
            updateAll();
        });

        // волна зависит от списка треков (путь к файлу ищем по mediaId)
        viewModel.tracks.observe(getViewLifecycleOwner(), tracks -> loadWaveform());
    }

    @Override
    public void onResume() {
        super.onResume();
        progressHandler.post(progressTick);
    }

    @Override
    public void onPause() {
        super.onPause();
        progressHandler.removeCallbacks(progressTick);
    }

    @Override
    public void onDestroyView() {
        if (controller != null) {
            controller.removeListener(playerListener);
            controller = null;
        }
        super.onDestroyView();
    }

    private void updateAll() {
        updateMetadata();
        updatePlayButton();
        updateModeButtons();
        updateProgress();
        updateCounter();
        loadWaveform();
    }

    private void loadWaveform() {
        if (waveform == null) {
            return;
        }
        if (controller == null || controller.getCurrentMediaItem() == null) {
            currentWaveformPath = null;
            waveform.setWaveform(null);
            return;
        }
        String mediaId = controller.getCurrentMediaItem().mediaId;
        String path = null;
        List<Track> tracks = viewModel.tracks.getValue();
        if (tracks != null) {
            for (Track track : tracks) {
                if (String.valueOf(track.id).equals(mediaId)) {
                    path = track.filePath;
                    break;
                }
            }
        }
        if (path == null) {
            currentWaveformPath = null;
            waveform.setWaveform(null);
            return;
        }
        if (path.equals(currentWaveformPath)) {
            return;
        }
        currentWaveformPath = path;
        waveform.setWaveform(null);
        WaveformExtractor.load(requireContext(), path, (donePath, amps) -> {
            if (donePath.equals(currentWaveformPath) && waveform != null) {
                waveform.setWaveform(amps);
            }
        });
    }

    private void updateCounter() {
        if (controller == null || controller.getMediaItemCount() == 0) {
            trackCounter.setText("");
            return;
        }
        trackCounter.setText((controller.getCurrentMediaItemIndex() + 1)
                + "/" + controller.getMediaItemCount());
    }

    private void updateMetadata() {
        if (controller == null || controller.getCurrentMediaItem() == null) {
            trackTitle.setText(R.string.no_track);
            trackArtist.setText("");
            coverImage.setImageResource(R.drawable.ic_music_note);
            return;
        }
        MediaMetadata metadata = controller.getMediaMetadata();
        trackTitle.setText(metadata.title != null ? metadata.title
                : getString(R.string.no_track));
        trackArtist.setText(metadata.artist != null ? metadata.artist
                : getString(R.string.unknown_artist));

        if (metadata.artworkData != null) {
            // обложка, вшитая в файл (ID3) — работает офлайн
            Glide.with(this)
                    .load(metadata.artworkData)
                    .placeholder(R.drawable.ic_music_note)
                    .centerCrop()
                    .into(coverImage);
        } else if (metadata.artworkUri != null) {
            Glide.with(this)
                    .load(metadata.artworkUri)
                    .placeholder(R.drawable.ic_music_note)
                    .error(R.drawable.ic_music_note)
                    .centerCrop()
                    .into(coverImage);
        } else {
            coverImage.setImageResource(R.drawable.ic_music_note);
        }
    }

    private void updatePlayButton() {
        boolean playing = controller != null && controller.isPlaying();
        playButton.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private void updateModeButtons() {
        boolean shuffle = controller != null && controller.getShuffleModeEnabled();
        shuffleButton.setAlpha(shuffle ? 1f : 0.4f);

        int repeatMode = controller != null ? controller.getRepeatMode()
                : Player.REPEAT_MODE_OFF;
        repeatButton.setImageResource(repeatMode == Player.REPEAT_MODE_ONE
                ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        repeatButton.setAlpha(repeatMode == Player.REPEAT_MODE_OFF ? 0.4f : 1f);
    }

    private void updateProgress() {
        if (controller == null || userSeeking) {
            return;
        }
        long duration = Math.max(0, controller.getDuration());
        long position = Math.max(0, controller.getCurrentPosition());
        if (duration > 0) {
            waveform.setProgress(position / (float) duration);
            durationText.setText(Formats.duration(duration));
        } else {
            waveform.setProgress(0f);
            durationText.setText(Formats.duration(0));
        }
        positionText.setText(Formats.duration(position));
    }
}
