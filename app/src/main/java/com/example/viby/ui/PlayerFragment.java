package com.example.viby.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;

import com.bumptech.glide.Glide;
import com.example.viby.R;
import com.example.viby.util.Formats;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

/** Главная страница: обложка, перемотка, prev/play/next, shuffle/repeat. */
public class PlayerFragment extends Fragment {

    private PlayerViewModel viewModel;
    private MediaController controller;

    private ImageView coverImage;
    private TextView trackTitle;
    private TextView trackArtist;
    private SeekBar seekBar;
    private TextView positionText;
    private TextView durationText;
    private FloatingActionButton playButton;
    private ImageButton shuffleButton;
    private ImageButton repeatButton;

    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private boolean userSeeking;

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
        seekBar = view.findViewById(R.id.seekBar);
        positionText = view.findViewById(R.id.positionText);
        durationText = view.findViewById(R.id.durationText);
        playButton = view.findViewById(R.id.playButton);
        shuffleButton = view.findViewById(R.id.shuffleButton);
        repeatButton = view.findViewById(R.id.repeatButton);
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

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    positionText.setText(Formats.duration(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                userSeeking = false;
                if (controller != null) {
                    controller.seekTo(bar.getProgress());
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
            seekBar.setMax((int) duration);
            seekBar.setProgress((int) Math.min(position, duration));
            durationText.setText(Formats.duration(duration));
        } else {
            seekBar.setMax(100);
            seekBar.setProgress(0);
            durationText.setText(Formats.duration(0));
        }
        positionText.setText(Formats.duration(position));
    }
}
