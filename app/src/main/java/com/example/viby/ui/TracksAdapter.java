package com.example.viby.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.viby.R;
import com.example.viby.data.Track;
import com.example.viby.util.Formats;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TracksAdapter extends RecyclerView.Adapter<TracksAdapter.Holder> {

    public interface Listener {
        void onTrackClick(Track track, int position);

        void onTrackLongClick(Track track);

        /** Вызывается при каждом изменении выбора в режиме мультивыбора. */
        void onSelectionChanged(int selectedCount);
    }

    private final Listener listener;
    private final List<Track> tracks = new ArrayList<>();
    private final Set<Long> selectedIds = new HashSet<>();
    private boolean selectionMode;
    private long currentTrackId = -1;

    public TracksAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<Track> newTracks) {
        tracks.clear();
        tracks.addAll(newTracks);
        if (selectionMode) {
            // выкидываем из выбора треки, которых больше нет
            Set<Long> alive = new HashSet<>();
            for (Track track : newTracks) {
                alive.add(track.id);
            }
            selectedIds.retainAll(alive);
            listener.onSelectionChanged(selectedIds.size());
        }
        notifyDataSetChanged();
    }

    /** Подсветка текущего трека; mediaId в очереди = Track.id. */
    public void setCurrentTrackId(long id) {
        if (currentTrackId != id) {
            currentTrackId = id;
            notifyDataSetChanged();
        }
    }

    // ------------------------------------------------------- multi-select

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public void enterSelectionMode(Track initial) {
        selectionMode = true;
        selectedIds.clear();
        if (initial != null) {
            selectedIds.add(initial.id);
        }
        listener.onSelectionChanged(selectedIds.size());
        notifyDataSetChanged();
    }

    public void exitSelectionMode() {
        selectionMode = false;
        selectedIds.clear();
        notifyDataSetChanged();
    }

    public void toggleSelection(Track track) {
        if (!selectedIds.remove(track.id)) {
            selectedIds.add(track.id);
        }
        listener.onSelectionChanged(selectedIds.size());
        notifyDataSetChanged();
    }

    public void selectAll(boolean select) {
        selectedIds.clear();
        if (select) {
            for (Track track : tracks) {
                selectedIds.add(track.id);
            }
        }
        listener.onSelectionChanged(selectedIds.size());
        notifyDataSetChanged();
    }

    public List<Track> getSelectedTracks() {
        List<Track> selected = new ArrayList<>();
        for (Track track : tracks) {
            if (selectedIds.contains(track.id)) {
                selected.add(track);
            }
        }
        return selected;
    }

    // ---------------------------------------------------------- adapter

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_track, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(tracks.get(position), position);
    }

    @Override
    public int getItemCount() {
        return tracks.size();
    }

    class Holder extends RecyclerView.ViewHolder {
        private final CheckBox check;
        private final ImageView thumb;
        private final TextView title;
        private final TextView artist;
        private final TextView duration;

        Holder(@NonNull View itemView) {
            super(itemView);
            check = itemView.findViewById(R.id.trackCheck);
            thumb = itemView.findViewById(R.id.trackThumb);
            title = itemView.findViewById(R.id.trackItemTitle);
            artist = itemView.findViewById(R.id.trackItemArtist);
            duration = itemView.findViewById(R.id.trackItemDuration);
        }

        void bind(Track track, int position) {
            title.setText(track.title);
            artist.setText(track.uploader != null ? track.uploader
                    : itemView.getContext().getString(R.string.unknown_artist));
            duration.setText(Formats.duration(track.durationMs));

            check.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
            check.setChecked(selectedIds.contains(track.id));

            boolean isCurrent = track.id == currentTrackId;
            title.setTextColor(isCurrent
                    ? com.google.android.material.color.MaterialColors.getColor(
                            title, androidx.appcompat.R.attr.colorPrimary)
                    : com.google.android.material.color.MaterialColors.getColor(
                            title, android.R.attr.textColorPrimary));

            Glide.with(thumb)
                    .load(track.thumbnailUrl)
                    .placeholder(R.drawable.ic_music_note)
                    .error(R.drawable.ic_music_note)
                    .centerCrop()
                    .into(thumb);

            itemView.setOnClickListener(v -> {
                if (selectionMode) {
                    toggleSelection(track);
                } else {
                    listener.onTrackClick(track, position);
                }
            });
            itemView.setOnLongClickListener(v -> {
                if (selectionMode) {
                    toggleSelection(track);
                } else {
                    listener.onTrackLongClick(track);
                }
                return true;
            });
        }
    }
}
