package com.example.viby.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.viby.R;
import com.example.viby.util.Formats;
import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Rows in the editable playback queue bottom sheet. */
final class PlaybackQueueAdapter
        extends RecyclerView.Adapter<PlaybackQueueAdapter.Holder> {

    interface Listener {
        void onPlay(int position);

        void onRemove(int position);

        void onStartDrag(RecyclerView.ViewHolder holder);
    }

    private final Listener listener;
    static final class QueueItem {
        final MediaItem mediaItem;
        final int playerIndex;

        QueueItem(MediaItem mediaItem, int playerIndex) {
            this.mediaItem = mediaItem;
            this.playerIndex = playerIndex;
        }
    }

    private final List<QueueItem> items = new ArrayList<>();
    private String currentMediaId;

    PlaybackQueueAdapter(Listener listener) {
        this.listener = listener;
    }

    void submit(List<QueueItem> newItems, String newCurrentMediaId) {
        items.clear();
        items.addAll(newItems);
        currentMediaId = newCurrentMediaId;
        notifyDataSetChanged();
    }

    void move(int from, int to) {
        if (from == to || from < 0 || to < 0
                || from >= items.size() || to >= items.size()) {
            return;
        }
        Collections.swap(items, from, to);
        notifyItemMoved(from, to);
    }

    int getPlayerIndex(int displayPosition) {
        return items.get(displayPosition).playerIndex;
    }

    int[] getPlayerOrder() {
        int[] order = new int[items.size()];
        for (int i = 0; i < items.size(); i++) {
            order[i] = items.get(i).playerIndex;
        }
        return order;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_playback_queue, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(items.get(position));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    final class Holder extends RecyclerView.ViewHolder {
        private final ImageView thumb;
        private final TextView title;
        private final TextView artist;
        private final TextView duration;
        private final ImageButton delete;
        private final ImageView drag;

        Holder(@NonNull View itemView) {
            super(itemView);
            thumb = itemView.findViewById(R.id.queueItemThumb);
            title = itemView.findViewById(R.id.queueItemTitle);
            artist = itemView.findViewById(R.id.queueItemArtist);
            duration = itemView.findViewById(R.id.queueItemDuration);
            delete = itemView.findViewById(R.id.queueItemDelete);
            drag = itemView.findViewById(R.id.queueItemDrag);
        }

        void bind(QueueItem queueItem) {
            MediaItem item = queueItem.mediaItem;
            MediaMetadata metadata = item.mediaMetadata;
            title.setText(metadata.title != null
                    ? metadata.title : itemView.getContext().getString(R.string.no_track));
            artist.setText(metadata.artist != null
                    ? metadata.artist
                    : itemView.getContext().getString(R.string.unknown_artist));
            duration.setText(metadata.durationMs != null
                    ? Formats.duration(metadata.durationMs) : "");

            boolean current = item.mediaId.equals(currentMediaId);
            title.setTextColor(MaterialColors.getColor(title, current
                    ? androidx.appcompat.R.attr.colorPrimary
                    : android.R.attr.textColorPrimary));

            Glide.with(thumb)
                    .load(metadata.artworkData != null
                            ? metadata.artworkData : metadata.artworkUri)
                    .placeholder(R.drawable.ic_music_note)
                    .error(R.drawable.ic_music_note)
                    .centerCrop()
                    .into(thumb);

            itemView.setOnClickListener(v -> {
                int position = getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    listener.onPlay(position);
                }
            });
            delete.setOnClickListener(v -> {
                int position = getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    listener.onRemove(position);
                }
            });
            drag.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                    listener.onStartDrag(this);
                }
                return false;
            });
        }
    }
}
