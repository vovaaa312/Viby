package com.example.viby.download;

import java.util.concurrent.atomic.AtomicLong;

/** Одно задание в очереди загрузок (трек или целый плейлист). */
public class DownloadJob {

    public enum Status { QUEUED, PREPARING, DOWNLOADING, PAUSED, DONE, FAILED, CANCELED }

    public static final class TrackItem {
        public enum Status { WAITING, DOWNLOADING, DONE, FAILED }

        public final String videoId;
        public final String title;
        public final String uploader;
        public final long durationMs;
        public final int youtubePosition;
        public volatile Status status = Status.WAITING;
        /** Download progress for this exact playlist entry, from 0 to 100. */
        public volatile int progress;

        public TrackItem(String videoId, String title, String uploader,
                         long durationMs, int youtubePosition) {
            this.videoId = videoId;
            this.title = title;
            this.uploader = uploader;
            this.durationMs = durationMs;
            this.youtubePosition = youtubePosition;
        }
    }

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
    /** Editable queue of tracks inside a playlist download. */
    public final java.util.List<TrackItem> tracks =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    /** Playlist entry currently handled by yt-dlp; used by track-level progress UI. */
    public volatile TrackItem activeTrack;
    public volatile String error;
    public volatile boolean cancelRequested;
    public volatile boolean pauseRequested;
    /** A running yt-dlp process was interrupted specifically to yield this job. */
    volatile boolean pauseInterrupted;
    /** The active process should yield so a user-selected pending track can run first. */
    volatile boolean yieldInterrupted;
    /** Guards persistent cleanup when cancel races with the worker finishing. */
    volatile boolean completionHandled;
    /** id строки в pending_downloads (0 — ещё не сохранено). */
    public volatile long pendingId;
    /** Stable order among unfinished jobs; persisted across process restarts. */
    public volatile long queuePosition;
    /** Persisted order of video IDs, populated after playlist metadata is fetched. */
    public volatile String trackOrderJson;

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
