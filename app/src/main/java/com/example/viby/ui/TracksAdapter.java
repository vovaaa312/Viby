package com.example.viby.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.viby.R;
import com.example.viby.data.Track;
import com.example.viby.util.Formats;

import java.util.ArrayList;
import java.util.List;

public class TracksAdapter extends RecyclerView.Adapter<TracksAdapter.Holder> {

    public interface Listener {
        void onTrackClick(Track track, int position);

        void onTrackLongClick(Track track);
    }

    private final Listener listener;
    private final List<Track> tracks = new ArrayList<>();
    private long currentTrackId = -1;

    public TracksAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<Track> newTracks) {
        tracks.clear();
        tracks.addAll(newTracks);
        notifyDataSetChanged();
    }

    /** Подсветка текущего трека; mediaId в очереди = Track.id. */
    public void setCurrentTrackId(long id) {
        if (currentTrackId != id) {
            currentTrackId = id;
            notifyDataSetChanged();
        }
    }

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
        private final ImageView thumb;
        private final TextView title;
        private final TextView artist;
        private final TextView duration;

        Holder(@NonNull View itemView) {
            super(itemView);
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

            itemView.setOnClickListener(v -> listener.onTrackClick(track, position));
            itemView.setOnLongClickListener(v -> {
                listener.onTrackLongClick(track);
                return true;
            });
        }
    }
}
