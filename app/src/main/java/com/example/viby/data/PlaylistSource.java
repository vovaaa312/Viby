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
}
