package com.example.viby.download;

import java.util.concurrent.atomic.AtomicLong;

/** Одно задание в очереди загрузок (трек или целый плейлист). */
public class DownloadJob {

    public enum Status { QUEUED, PREPARING, DOWNLOADING, DONE, FAILED, CANCELED }

    private static final AtomicLong SEQ = new AtomicLong(1);

    public final long id = SEQ.getAndIncrement();
    public final String url;
    public final boolean isPlaylist;
    /** Имя папки-плейлиста; для плейлиста null = взять название с YouTube. */
    public volatile String playlistName;

    public volatile Status status = Status.QUEUED;
    /** Что показывать в списке/уведомлении (название трека или плейлиста). */
    public volatile String title;
    /** Прогресс текущего трека, 0–100. */
    public volatile int progress;
    /** Для плейлистов: номер текущего трека и всего треков. */
    public volatile int currentIndex;
    public volatile int totalCount;
    public volatile int failedCount;
    public volatile String error;
    public volatile boolean cancelRequested;

    public DownloadJob(String url, String playlistName, boolean isPlaylist) {
        this.url = url;
        this.playlistName = playlistName;
        this.isPlaylist = isPlaylist;
        this.title = url;
    }
}
