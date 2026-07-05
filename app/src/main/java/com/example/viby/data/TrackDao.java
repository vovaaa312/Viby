package com.example.viby.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface TrackDao {

    @Insert
    long insert(Track track);

    @Delete
    void delete(Track track);

    @Query("SELECT playlistName, COUNT(*) AS trackCount FROM tracks " +
            "GROUP BY playlistName ORDER BY playlistName")
    LiveData<List<PlaylistInfo>> observePlaylists();

    @Query("SELECT * FROM tracks WHERE playlistName = :playlist ORDER BY position, id")
    LiveData<List<Track>> observePlaylist(String playlist);

    @Query("SELECT * FROM tracks WHERE playlistName = :playlist ORDER BY position, id")
    List<Track> getPlaylistSync(String playlist);

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM tracks WHERE playlistName = :playlist")
    int nextPosition(String playlist);

    @Query("SELECT EXISTS(SELECT 1 FROM tracks WHERE playlistName = :playlist AND videoId = :videoId)")
    boolean exists(String playlist, String videoId);
}
