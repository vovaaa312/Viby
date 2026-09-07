package com.example.viby.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "tracks", indices = {@Index("playlistName")})
public class Track {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public String videoId;

    @NonNull
    public String title = "";

    public String uploader;

    public long durationMs;

    /** Абсолютный путь к mp3/m4a файлу. */
    @NonNull
    public String filePath = "";

    /** Плейлист = имя подпапки в Music/. */
    @NonNull
    public String playlistName = "";

    /** URL миниатюры YouTube (обложка также вшита в сам файл). */
    public String thumbnailUrl;

    /** Порядок внутри плейлиста. */
    public int position;

    /** Position in the source YouTube playlist; null for local-only tracks. */
    public Integer youtubePosition;

    /** ID of this exact occurrence in a YouTube playlist; differs from videoId. */
    public String youtubePlaylistItemId;

    public long createdAt;

    /** True once filePath points to a complete local audio file. */
    public boolean downloaded = true;

    /** YouTube page used to resolve a temporary online stream for pending tracks. */
    public String sourceUrl;
}
