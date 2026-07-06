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

    @Query("SELECT * FROM pending_downloads ORDER BY id")
    List<PendingDownload> getAllSync();
}
