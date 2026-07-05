package com.example.viby.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.viby.R;
import com.example.viby.data.PlaylistInfo;

import java.util.ArrayList;
import java.util.List;

public class PlaylistsAdapter extends RecyclerView.Adapter<PlaylistsAdapter.Holder> {

    public interface Listener {
        void onPlaylistClick(String playlistName);
    }

    private final Listener listener;
    private final List<PlaylistInfo> playlists = new ArrayList<>();

    public PlaylistsAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<PlaylistInfo> newPlaylists) {
        playlists.clear();
        playlists.addAll(newPlaylists);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_playlist, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(playlists.get(position));
    }

    @Override
    public int getItemCount() {
        return playlists.size();
    }

    class Holder extends RecyclerView.ViewHolder {
        private final TextView name;
        private final TextView count;

        Holder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.playlistName);
            count = itemView.findViewById(R.id.playlistCount);
        }

        void bind(PlaylistInfo info) {
            name.setText(info.playlistName);
            count.setText(String.valueOf(info.trackCount));
            itemView.setOnClickListener(v -> listener.onPlaylistClick(info.playlistName));
        }
    }
}
