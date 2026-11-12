package com.example.viby.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import android.graphics.drawable.Drawable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.example.viby.R;
import com.example.viby.data.Track;
import com.example.viby.library.LibraryBrowser;
import com.example.viby.util.Formats;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Library rows: either artist/album/folder groups or tracks from any playlist. */
final class LibraryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    interface Listener {
        void onGroupClick(LibraryBrowser.Group group);

        void onGroupLongClick(LibraryBrowser.Group group);

        void onTrackClick(Track track);

        void onTrackLongClick(Track track);

        void onSelectionChanged(int selectedCount);
    }

    private static final int TYPE_GROUP = 0;
    private static final int TYPE_TRACK = 1;

    private final Listener listener;
    private final List<LibraryBrowser.Group> groups = new ArrayList<>();
    private final List<Track> tracks = new ArrayList<>();
    private final Set<Long> selectedIds = new HashSet<>();
    private boolean showingGroups;
    private LibraryBrowser.Tab groupTab = LibraryBrowser.Tab.ARTISTS;
    private boolean selectionMode;
    private long currentTrackId = -1;

    LibraryAdapter(Listener listener) {
        this.listener = listener;
    }

    void showGroups(List<LibraryBrowser.Group> newGroups, LibraryBrowser.Tab tab) {
        showingGroups = true;
        groupTab = tab;
        groups.clear();
        groups.addAll(newGroups);
        tracks.clear();
        notifyDataSetChanged();
    }

    void showTracks(List<Track> newTracks) {
        showingGroups = false;
        groups.clear();
        tracks.clear();
        tracks.addAll(newTracks);
        if (selectionMode) {
            Set<Long> visible = new HashSet<>();
            for (Track track : newTracks) {
                visible.add(track.id);
            }
            if (selectedIds.retainAll(visible)) {
                listener.onSelectionChanged(selectedIds.size());
            }
        }
        notifyDataSetChanged();
    }

    boolean isShowingGroups() {
        return showingGroups;
    }

    List<Track> getTracks() {
        return new ArrayList<>(tracks);
    }

    void setCurrentTrackId(long id) {
        if (currentTrackId != id) {
            currentTrackId = id;
            if (!showingGroups) {
                notifyDataSetChanged();
            }
        }
    }

    // --------------------------------------------------------------- selection

    boolean isSelectionMode() {
        return selectionMode;
    }

    void enterSelectionMode(Track initial) {
        selectionMode = true;
        selectedIds.clear();
        if (initial != null) {
            selectedIds.add(initial.id);
        }
        listener.onSelectionChanged(selectedIds.size());
        notifyDataSetChanged();
    }

    void exitSelectionMode() {
        selectionMode = false;
        selectedIds.clear();
        notifyDataSetChanged();
    }

    void selectAll(boolean select) {
        selectedIds.clear();
        if (select) {
            for (Track track : tracks) {
                selectedIds.add(track.id);
            }
        }
        listener.onSelectionChanged(selectedIds.size());
        notifyDataSetChanged();
    }

    List<Track> getSelectedTracks() {
        List<Track> selected = new ArrayList<>();
        for (Track track : tracks) {
            if (selectedIds.contains(track.id)) {
                selected.add(track);
            }
        }
        return selected;
    }

    private void toggle(Track track) {
        if (!selectedIds.remove(track.id)) {
            selectedIds.add(track.id);
        }
        listener.onSelectionChanged(selectedIds.size());
        notifyDataSetChanged();
    }

    // ----------------------------------------------------------------- adapter

    @Override
    public int getItemViewType(int position) {
        return showingGroups ? TYPE_GROUP : TYPE_TRACK;
    }

    @Override
    public int getItemCount() {
        return showingGroups ? groups.size() : tracks.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_GROUP) {
            return new GroupHolder(inflater.inflate(R.layout.item_library_group, parent, false));
        }
        return new TrackHolder(inflater.inflate(R.layout.item_track, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof GroupHolder) {
            ((GroupHolder) holder).bind(groups.get(position));
        } else {
            ((TrackHolder) holder).bind(tracks.get(position));
        }
    }

    final class GroupHolder extends RecyclerView.ViewHolder {
        private final ImageView icon;
        private final TextView title;
        private final TextView subtitle;

        GroupHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.groupIcon);
            title = itemView.findViewById(R.id.groupTitle);
            subtitle = itemView.findViewById(R.id.groupSubtitle);
        }

        void bind(LibraryBrowser.Group group) {
            Context context = itemView.getContext();
            title.setText(group.title);
            subtitle.setText(context.getResources().getQuantityString(
                    R.plurals.library_group_tracks, group.tracks.size(),
                    group.tracks.size(), Formats.duration(group.durationMs)));
            int fallback = groupTab == LibraryBrowser.Tab.ALBUMS ? R.drawable.ic_album
                    : groupTab == LibraryBrowser.Tab.FOLDERS ? R.drawable.ic_folder
                    : R.drawable.ic_person;
            // like the desktop app: an artist or album shows the cover of its first track,
            // artists as a circle so the two tabs are easy to tell apart
            String cover = groupTab == LibraryBrowser.Tab.FOLDERS ? null : group.firstThumbnail();
            if (cover != null) {
                RequestBuilder<Drawable> request = Glide.with(icon).load(cover)
                        .placeholder(fallback).error(fallback);
                if (groupTab == LibraryBrowser.Tab.ARTISTS) {
                    request = request.circleCrop();
                } else {
                    request = request.centerCrop();
                }
                request.into(icon);
            } else {
                Glide.with(icon).clear(icon);
                icon.setImageResource(fallback);
            }
            itemView.setOnClickListener(v -> listener.onGroupClick(group));
            itemView.setOnLongClickListener(v -> {
                listener.onGroupLongClick(group);
                return true;
            });
        }
    }

    final class TrackHolder extends RecyclerView.ViewHolder {
        private final CheckBox check;
        private final ImageView thumb;
        private final TextView title;
        private final TextView artist;
        private final TextView duration;
        private final ImageView favorite;
        private final View currentIndicator;
        private final Typeface normalTypeface;
        private final Typeface boldTypeface;

        TrackHolder(@NonNull View itemView) {
            super(itemView);
            check = itemView.findViewById(R.id.trackCheck);
            thumb = itemView.findViewById(R.id.trackThumb);
            title = itemView.findViewById(R.id.trackItemTitle);
            artist = itemView.findViewById(R.id.trackItemArtist);
            duration = itemView.findViewById(R.id.trackItemDuration);
            favorite = itemView.findViewById(R.id.trackFavorite);
            currentIndicator = itemView.findViewById(R.id.trackCurrentIndicator);
            normalTypeface = title.getTypeface();
            boldTypeface = Typeface.create(normalTypeface, Typeface.BOLD);
        }

        void bind(Track track) {
            Context context = itemView.getContext();
            title.setText(track.title);
            String artistName = LibraryBrowser.artistName(track);
            StringBuilder subtitle = new StringBuilder(artistName != null ? artistName
                    : context.getString(R.string.unknown_artist));
            subtitle.append(" · ").append(track.playlistName);
            if (LibraryBrowser.isFailed(track)) {
                subtitle.append(" — ").append(track.downloadErrorMessage != null
                        ? track.downloadErrorMessage
                        : context.getString(R.string.download_error_unknown));
            } else if (!track.downloaded) {
                subtitle.append(" — ").append(context.getString(R.string.library_not_downloaded));
            }
            artist.setText(subtitle);
            duration.setText(Formats.duration(track.durationMs));
            favorite.setVisibility(track.favorite ? View.VISIBLE : View.GONE);

            check.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
            check.setChecked(selectedIds.contains(track.id));
            boolean current = track.id == currentTrackId;
            title.setTypeface(current ? boldTypeface : normalTypeface);
            currentIndicator.setVisibility(current ? View.VISIBLE : View.INVISIBLE);
            itemView.setActivated(current);
            itemView.setAlpha(track.downloaded ? 1f : 0.6f);

            Glide.with(thumb)
                    .load(track.thumbnailUrl)
                    .placeholder(R.drawable.ic_music_note)
                    .error(R.drawable.ic_music_note)
                    .centerCrop()
                    .into(thumb);

            itemView.setOnClickListener(v -> {
                if (selectionMode) {
                    toggle(track);
                } else {
                    listener.onTrackClick(track);
                }
            });
            itemView.setOnLongClickListener(v -> {
                if (selectionMode) {
                    toggle(track);
                } else {
                    listener.onTrackLongClick(track);
                }
                return true;
            });
        }
    }
}
