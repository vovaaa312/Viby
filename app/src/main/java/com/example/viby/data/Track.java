package com.example.viby.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "tracks", indices = {@Index("playlistName")})
public class Track {

    public static final String DOWNLOAD_WAITING = "WAITING";
    public static final String DOWNLOAD_DOWNLOADING = "DOWNLOADING";
    public static final String DOWNLOAD_DOWNLOADED = "DOWNLOADED";
    public static final String DOWNLOAD_RETRY_SCHEDULED = "RETRY_SCHEDULED";
    public static final String DOWNLOAD_FAILED_TEMPORARY = "FAILED_TEMPORARY";
    public static final String DOWNLOAD_FAILED_PERMANENT = "FAILED_PERMANENT";

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

    /** Durable download state. Kept separately from downloaded for migration compatibility. */
    @NonNull
    public String downloadState = DOWNLOAD_DOWNLOADED;

    /** Stable DownloadFailure.Code name; never contains credentials or raw command output. */
    public String downloadErrorCode;

    /** Localized, user-safe failure explanation. */
    public String downloadErrorMessage;

    public int downloadAttemptCount;

    public long downloadLastAttemptAt;

    /** mp3/m4a selected by the most recent attempt. */
    public String downloadFormat;

    public boolean downloadRequiresAuthorization;

    public int downloadProgress;
}
