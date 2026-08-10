package com.example.viby.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface PendingDownloadDao {

    @Insert
    long insert(PendingDownload pending);

    @Query("DELETE FROM pending_downloads WHERE id = :id")
    void delete(long id);

    @Query("SELECT * FROM pending_downloads ORDER BY queuePosition, id")
    List<PendingDownload> getAllSync();

    @Query("UPDATE pending_downloads SET queuePosition = :position WHERE id = :id")
    void updateQueuePosition(long id, long position);

    @Query("UPDATE pending_downloads SET paused = :paused WHERE id = :id")
    void updatePaused(long id, boolean paused);

    @Query("UPDATE pending_downloads SET trackOrderJson = :trackOrderJson WHERE id = :id")
    void updateTrackOrder(long id, String trackOrderJson);
}
