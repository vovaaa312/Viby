package com.example.viby.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface PlaylistSourceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(PlaylistSource source);

    @Query("SELECT * FROM playlist_sources WHERE playlistName = :playlistName")
    PlaylistSource getSync(String playlistName);

    @Query("DELETE FROM playlist_sources WHERE playlistName = :playlistName")
    void delete(String playlistName);
}
