package com.example.viby.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Откуда скачан плейлист — для кнопки «обновить плейлист» (докачать новые треки). */
@Entity(tableName = "playlist_sources")
public class PlaylistSource {

    @PrimaryKey
    @NonNull
    public String playlistName = "";

    @NonNull
    public String sourceUrl = "";

    public long updatedAt;

    /** Stable YouTube playlist id extracted from sourceUrl. */
    public String youtubePlaylistId;

    /** Remote writes are opt-in and are never inferred from a cookie session. */
    public boolean youtubeSyncEnabled;

    /** Channel which owned the playlist when synchronization was enabled. */
    public String youtubeOwnerChannelId;
}
