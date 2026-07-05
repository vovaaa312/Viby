package com.example.viby.ui;

import android.os.Bundle;
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
import androidx.media3.session.MediaController;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.viby.R;
import com.example.viby.data.Track;

/** Страница справа: список треков активного плейлиста + мини-плеер снизу (как в AIMP). */
public class QueueFragment extends Fragment implements TracksAdapter.Listener {

    private PlayerViewModel viewModel;
    private MediaController controller;
    private TracksAdapter adapter;
    private TextView emptyText;

    private ImageView miniThumb;
    private TextView miniTitle;
    private TextView miniArtist;
    private ImageButton miniPlay;

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
            highlightCurrent();
            updateMiniPlayer();
        }

        @Override
        public void onMediaMetadataChanged(@NonNull MediaMetadata mediaMetadata) {
            updateMiniPlayer();
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            updateMiniPlayer();
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_queue, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        viewModel = new ViewModelProvider(requireActivity()).get(PlayerViewModel.class);

        emptyText = view.findViewById(R.id.emptyText);
        RecyclerView tracksList = view.findViewById(R.id.tracksList);
        adapter = new TracksAdapter(this);
        tracksList.setAdapter(adapter);

        View miniPlayer = view.findViewById(R.id.miniPlayer);
        miniThumb = view.findViewById(R.id.miniThumb);
        miniTitle = view.findViewById(R.id.miniTitle);
        miniArtist = view.findViewById(R.id.miniArtist);
        miniPlay = view.findViewById(R.id.miniPlay);
        ImageButton miniPrev = view.findViewById(R.id.miniPrev);
        ImageButton miniNext = view.findViewById(R.id.miniNext);

        miniPlayer.setOnClickListener(v ->
                ((MainActivity) requireActivity()).showPlayerPage());
        miniPlay.setOnClickListener(v -> {
            if (controller == null) {
                return;
            }
            if (controller.isPlaying()) {
                controller.pause();
            } else {
                controller.play();
            }
        });
        miniPrev.setOnClickListener(v -> {
            if (controller != null) {
                controller.seekToPrevious();
            }
        });
        miniNext.setOnClickListener(v -> {
            if (controller != null) {
                controller.seekToNext();
            }
        });

        viewModel.tracks.observe(getViewLifecycleOwner(), tracks -> {
            adapter.submit(tracks);
            emptyText.setVisibility(tracks.isEmpty() ? View.VISIBLE : View.GONE);
        });

        viewModel.controller.observe(getViewLifecycleOwner(), newController -> {
            if (controller != null) {
                controller.removeListener(playerListener);
            }
            controller = newController;
            if (controller != null) {
                controller.addListener(playerListener);
            }
            highlightCurrent();
            updateMiniPlayer();
        });
    }

    @Override
    public void onDestroyView() {
        if (controller != null) {
            controller.removeListener(playerListener);
            controller = null;
        }
        super.onDestroyView();
    }

    private void highlightCurrent() {
        if (controller == null || controller.getCurrentMediaItem() == null) {
            adapter.setCurrentTrackId(-1);
            return;
        }
        try {
            adapter.setCurrentTrackId(
                    Long.parseLong(controller.getCurrentMediaItem().mediaId));
        } catch (NumberFormatException ignored) {
            adapter.setCurrentTrackId(-1);
        }
    }

    private void updateMiniPlayer() {
        if (miniTitle == null) {
            return;
        }
        if (controller == null || controller.getCurrentMediaItem() == null) {
            miniTitle.setText(R.string.no_track);
            miniArtist.setText("");
            miniPlay.setImageResource(R.drawable.ic_play);
            miniThumb.setImageResource(R.drawable.ic_music_note);
            return;
        }
        MediaMetadata metadata = controller.getMediaMetadata();
        miniTitle.setText(metadata.title != null ? metadata.title
                : getString(R.string.no_track));
        miniArtist.setText(metadata.artist != null ? metadata.artist
                : getString(R.string.unknown_artist));
        miniPlay.setImageResource(controller.isPlaying()
                ? R.drawable.ic_pause : R.drawable.ic_play);

        if (metadata.artworkData != null) {
            Glide.with(this)
                    .load(metadata.artworkData)
                    .placeholder(R.drawable.ic_music_note)
                    .centerCrop()
                    .into(miniThumb);
        } else if (metadata.artworkUri != null) {
            Glide.with(this)
                    .load(metadata.artworkUri)
                    .placeholder(R.drawable.ic_music_note)
                    .error(R.drawable.ic_music_note)
                    .centerCrop()
                    .into(miniThumb);
        } else {
            miniThumb.setImageResource(R.drawable.ic_music_note);
        }
    }

    @Override
    public void onTrackClick(Track track, int position) {
        ((MainActivity) requireActivity()).playTrack(track, position);
    }

    @Override
    public void onTrackLongClick(Track track) {
        ((MainActivity) requireActivity()).confirmDeleteTrack(track);
    }
}
