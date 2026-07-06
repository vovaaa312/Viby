package com.example.viby.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Незавершённое задание загрузки. Строка живёт, пока задание не закончилось,
 * поэтому после краша/перезагрузки/обновления приложения очередь
 * восстанавливается и докачивает с того же места.
 */
@Entity(tableName = "pending_downloads")
public class PendingDownload {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    public String url = "";

    public String playlistName;

    public boolean isPlaylist;

    public long createdAt;
}
