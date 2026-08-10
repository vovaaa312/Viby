package com.example.viby.ui;

import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.viby.R;
import com.example.viby.data.Track;
import com.example.viby.download.DownloadJob;
import com.example.viby.ui.widget.DownloadProgressOverlayView;
import com.example.viby.util.Formats;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TracksAdapter extends RecyclerView.Adapter<TracksAdapter.Holder> {

    public interface Listener {
        void onTrackClick(Track track, int position);

        void onTrackLongClick(Track track);

        /** Вызывается при каждом изменении выбора в режиме мультивыбора. */
        void onSelectionChanged(int selectedCount);
    }

    private final Listener listener;
    /** Полный список плейлиста; tracks — то, что видно после фильтра поиска. */
    private final List<Track> allTracks = new ArrayList<>();
    private final List<Track> tracks = new ArrayList<>();
    private final Set<Long> selectedIds = new HashSet<>();
    private final Map<String, Integer> pendingProgress = new HashMap<>();
    private String query = "";
    private boolean selectionMode;
    private long currentTrackId = -1;

    public TracksAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<Track> newTracks) {
        allTracks.clear();
        allTracks.addAll(newTracks);
        if (selectionMode) {
            // выкидываем из выбора треки, которых больше нет
            Set<Long> alive = new HashSet<>();
            for (Track track : newTracks) {
                alive.add(track.id);
            }
            selectedIds.retainAll(alive);
            listener.onSelectionChanged(selectedIds.size());
        }
        applyFilter();
    }

    /** Фильтр строки поиска: по названию и исполнителю. */
    public void setQuery(String newQuery) {
        query = newQuery != null
                ? newQuery.trim().toLowerCase(java.util.Locale.getDefault()) : "";
        applyFilter();
    }

    private void applyFilter() {
        tracks.clear();
        if (query.isEmpty()) {
            tracks.addAll(allTracks);
        } else {
            for (Track track : allTracks) {
                String title = track.title.toLowerCase(java.util.Locale.getDefault());
                String artist = track.uploader != null
                        ? track.uploader.toLowerCase(java.util.Locale.getDefault()) : "";
                if (title.contains(query) || artist.contains(query)) {
                    tracks.add(track);
                }
            }
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

    /** Updates progress overlays for incomplete tracks in active playlist jobs. */
    public void setDownloadJobs(List<DownloadJob> jobs) {
        Map<String, Integer> updated = new HashMap<>();
        if (jobs != null) {
            for (DownloadJob job : jobs) {
                if (!job.isActive() || job.playlistName == null) {
                    continue;
                }
                synchronized (job.tracks) {
                    for (DownloadJob.TrackItem item : job.tracks) {
                        if (item.status == DownloadJob.TrackItem.Status.WAITING
                                || item.status == DownloadJob.TrackItem.Status.DOWNLOADING) {
                            updated.put(progressKey(job.playlistName, item.videoId),
                                    item.progress);
                        }
                    }
                }
            }
        }
        if (!pendingProgress.equals(updated)) {
            pendingProgress.clear();
            pendingProgress.putAll(updated);
            notifyDataSetChanged();
        }
    }

    private static String progressKey(String playlist, String videoId) {
        return playlist + '\u0000' + (videoId != null ? videoId : "");
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
        for (Track track : allTracks) {
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
        private final DownloadProgressOverlayView downloadProgress;
        private final TextView title;
        private final TextView artist;
        private final TextView duration;
        private final View currentIndicator;
        private final Typeface normalTitleTypeface;
        private final Typeface boldTitleTypeface;

        Holder(@NonNull View itemView) {
            super(itemView);
            check = itemView.findViewById(R.id.trackCheck);
            thumb = itemView.findViewById(R.id.trackThumb);
            downloadProgress = itemView.findViewById(R.id.trackDownloadProgress);
            title = itemView.findViewById(R.id.trackItemTitle);
            artist = itemView.findViewById(R.id.trackItemArtist);
            duration = itemView.findViewById(R.id.trackItemDuration);
            currentIndicator = itemView.findViewById(R.id.trackCurrentIndicator);
            normalTitleTypeface = title.getTypeface();
            boldTitleTypeface = Typeface.create(normalTitleTypeface, Typeface.BOLD);
        }

        void bind(Track track, int position) {
            title.setText(track.title);
            String artistText = track.uploader != null ? track.uploader
                    : itemView.getContext().getString(R.string.unknown_artist);
            if (!track.downloaded) {
                artistText += " — "
                        + itemView.getContext().getString(R.string.pending_track_online);
            }
            artist.setText(artistText);
            duration.setText(Formats.duration(track.durationMs));

            check.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
            check.setChecked(selectedIds.contains(track.id));

            boolean isCurrent = track.id == currentTrackId;
            title.setTextColor(com.google.android.material.color.MaterialColors.getColor(
                    title, android.R.attr.textColorPrimary));
            title.setTypeface(isCurrent ? boldTitleTypeface : normalTitleTypeface);
            currentIndicator.setVisibility(isCurrent ? View.VISIBLE : View.INVISIBLE);
            itemView.setActivated(isCurrent);
            ViewCompat.setStateDescription(itemView, isCurrent
                    ? itemView.getContext().getString(R.string.currently_playing) : null);

            Glide.with(thumb)
                    .load(track.thumbnailUrl)
                    .placeholder(R.drawable.ic_music_note)
                    .error(R.drawable.ic_music_note)
                    .centerCrop()
                    .into(thumb);

            Integer progress = pendingProgress.get(
                    progressKey(track.playlistName, track.videoId));
            downloadProgress.showProgress(!track.downloaded && progress != null,
                    progress != null ? progress : 0);

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
