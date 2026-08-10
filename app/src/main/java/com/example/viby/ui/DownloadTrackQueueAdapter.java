package com.example.viby.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.viby.R;
import com.example.viby.download.DownloadJob;

import java.util.ArrayList;
import java.util.List;

final class DownloadTrackQueueAdapter
        extends RecyclerView.Adapter<DownloadTrackQueueAdapter.Holder> {

    interface Listener {
        void onStartDrag(RecyclerView.ViewHolder holder);
    }

    private final Listener listener;
    private final List<DownloadJob.TrackItem> tracks = new ArrayList<>();

    DownloadTrackQueueAdapter(Listener listener) {
        this.listener = listener;
    }

    void submit(List<DownloadJob.TrackItem> newTracks) {
        tracks.clear();
        tracks.addAll(newTracks);
        notifyDataSetChanged();
    }

    boolean canMove(int position) {
        return position >= 0 && position < tracks.size()
                && tracks.get(position).status == DownloadJob.TrackItem.Status.WAITING;
    }

    void move(int from, int to) {
        if (from == to || !canMove(from) || !canMove(to)) {
            return;
        }
        DownloadJob.TrackItem moved = tracks.remove(from);
        tracks.add(to, moved);
        notifyItemMoved(from, to);
    }

    String getVideoId(int position) {
        return tracks.get(position).videoId;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_download_track, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(tracks.get(position));
    }

    @Override
    public int getItemCount() {
        return tracks.size();
    }

    final class Holder extends RecyclerView.ViewHolder {
        private final TextView title;
        private final TextView state;
        private final ImageView drag;

        Holder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.downloadTrackTitle);
            state = itemView.findViewById(R.id.downloadTrackState);
            drag = itemView.findViewById(R.id.downloadTrackDrag);
        }

        void bind(DownloadJob.TrackItem item) {
            title.setText(item.title);
            String status;
            switch (item.status) {
                case DOWNLOADING:
                    status = itemView.getContext().getString(R.string.download_track_active);
                    break;
                case DONE:
                    status = itemView.getContext().getString(R.string.download_track_done);
                    break;
                case FAILED:
                    status = itemView.getContext().getString(R.string.download_track_failed);
                    break;
                default:
                    status = itemView.getContext().getString(R.string.download_track_waiting);
                    break;
            }
            state.setText(item.uploader == null || item.uploader.isEmpty()
                    ? status : item.uploader + " — " + status);
            boolean movable = item.status == DownloadJob.TrackItem.Status.WAITING;
            drag.setVisibility(movable ? View.VISIBLE : View.INVISIBLE);
            drag.setOnClickListener(view -> {
                // Accessibility click target; dragging starts from touch-down below.
            });
            drag.setOnTouchListener((view, event) -> {
                if (movable && event.getActionMasked()
                        == android.view.MotionEvent.ACTION_DOWN) {
                    listener.onStartDrag(this);
                } else if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                    view.performClick();
                }
                return false;
            });
        }
    }
}
