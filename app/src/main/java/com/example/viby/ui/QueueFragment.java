package com.example.viby.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.recyclerview.widget.RecyclerView;

import com.example.viby.R;
import com.example.viby.data.Track;

/** Страница справа: список треков активного плейлиста. */
public class QueueFragment extends Fragment implements TracksAdapter.Listener {

    private PlayerViewModel viewModel;
    private MediaController controller;
    private TracksAdapter adapter;
    private TextView emptyText;

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
            highlightCurrent();
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

    @Override
    public void onTrackClick(Track track, int position) {
        ((MainActivity) requireActivity()).playTrack(track, position);
    }

    @Override
    public void onTrackLongClick(Track track) {
        ((MainActivity) requireActivity()).confirmDeleteTrack(track);
    }
}
