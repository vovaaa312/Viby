package com.example.viby.download;

import java.util.concurrent.atomic.AtomicLong;

/** Одно задание в очереди загрузок (трек или целый плейлист). */
public class DownloadJob {

    public enum Status { QUEUED, PREPARING, DOWNLOADING, PAUSED, DONE, FAILED, CANCELED }

    private static final AtomicLong SEQ = new AtomicLong(1);

    public final long id = SEQ.getAndIncrement();
    public final String url;
    public final boolean isPlaylist;
    /** Имя папки-плейлиста; для плейлиста null = взять название с YouTube. */
    public volatile String playlistName;

    public volatile Status status = Status.QUEUED;
    /** Что показывать в списке/уведомлении (название трека или плейлиста). */
    public volatile String title;
    /** Название трека, который качается прямо сейчас (для плейлистов ≠ title). */
    public volatile String currentTrackTitle;
    /** Прогресс текущего трека, 0–100. */
    public volatile int progress;
    /** Байты текущего трека: скачано / всего (0 если yt-dlp не сообщил размер). */
    public volatile long downloadedBytes;
    public volatile long totalBytes;
    /** Для плейлистов: номер текущего трека и всего треков. */
    public volatile int currentIndex;
    public volatile int totalCount;
    public volatile int failedCount;
    /** Названия треков плейлиста, которые не удалось скачать. */
    public final java.util.List<String> failedTitles =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    public volatile String error;
    public volatile boolean cancelRequested;
    public volatile boolean pauseRequested;

    public DownloadJob(String url, String playlistName, boolean isPlaylist) {
        this.url = url;
        this.playlistName = playlistName;
        this.isPlaylist = isPlaylist;
        this.title = url;
    }

    public boolean isActive() {
        return status == Status.QUEUED || status == Status.PREPARING
                || status == Status.DOWNLOADING || status == Status.PAUSED;
    }
}
