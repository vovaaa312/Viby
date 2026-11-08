package com.example.viby.ui;

import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;

import com.bumptech.glide.Glide;
import com.example.viby.R;

/**
 * Binds the shared {@code view_mini_player} layout to a {@link Player}: title, artist,
 * cover, play/pause and previous/next. Screens only supply the player and what a tap on
 * the bar should open.
 */
public final class MiniPlayerBinder {

    private final View root;
    private final ImageView thumb;
    private final TextView title;
    private final TextView artist;
    private final ImageButton play;
    @Nullable private Player player;

    private final Player.Listener listener = new Player.Listener() {
        @Override
        public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
            update();
        }

        @Override
        public void onMediaMetadataChanged(@NonNull MediaMetadata mediaMetadata) {
            update();
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            update();
        }
    };

    public MiniPlayerBinder(@NonNull View root, @NonNull Runnable onOpen) {
        this.root = root;
        thumb = root.findViewById(R.id.miniThumb);
        title = root.findViewById(R.id.miniTitle);
        artist = root.findViewById(R.id.miniArtist);
        play = root.findViewById(R.id.miniPlay);
        root.setOnClickListener(v -> onOpen.run());
        play.setOnClickListener(v -> {
            if (player == null) {
                return;
            }
            if (player.isPlaying()) {
                player.pause();
            } else {
                player.play();
            }
        });
        root.findViewById(R.id.miniPrev).setOnClickListener(v -> {
            if (player != null) {
                player.seekToPrevious();
            }
        });
        root.findViewById(R.id.miniNext).setOnClickListener(v -> {
            if (player != null) {
                player.seekToNext();
            }
        });
        update();
    }

    /** Pass {@code null} when the controller is released so no listener leaks. */
    public void setPlayer(@Nullable Player newPlayer) {
        if (player == newPlayer) {
            update();
            return;
        }
        if (player != null) {
            player.removeListener(listener);
        }
        player = newPlayer;
        if (player != null) {
            player.addListener(listener);
        }
        update();
    }

    private void update() {
        if (player == null || player.getCurrentMediaItem() == null) {
            title.setText(R.string.no_track);
            artist.setText("");
            play.setImageResource(R.drawable.ic_play);
            thumb.setImageResource(R.drawable.ic_music_note);
            return;
        }
        MediaMetadata metadata = player.getMediaMetadata();
        title.setText(metadata.title != null ? metadata.title
                : root.getContext().getString(R.string.no_track));
        artist.setText(metadata.artist != null ? metadata.artist
                : root.getContext().getString(R.string.unknown_artist));
        play.setImageResource(player.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);

        if (metadata.artworkData != null) {
            Glide.with(thumb)
                    .load(metadata.artworkData)
                    .placeholder(R.drawable.ic_music_note)
                    .centerCrop()
                    .into(thumb);
        } else if (metadata.artworkUri != null) {
            Glide.with(thumb)
                    .load(metadata.artworkUri)
                    .placeholder(R.drawable.ic_music_note)
                    .error(R.drawable.ic_music_note)
                    .centerCrop()
                    .into(thumb);
        } else {
            Glide.with(thumb).clear(thumb);
            thumb.setImageResource(R.drawable.ic_music_note);
        }
    }
}
