package com.example.viby.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface TrackDao {

    @Insert
    long insert(Track track);

    @Update
    void update(Track track);

    @Update
    void updateAll(List<Track> tracks);

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

    @Query("SELECT EXISTS(SELECT 1 FROM tracks WHERE playlistName = :playlist "
            + "AND videoId = :videoId AND downloaded = 1)")
    boolean isDownloaded(String playlist, String videoId);

    @Query("SELECT * FROM tracks WHERE playlistName = :playlist AND videoId = :videoId LIMIT 1")
    Track getByVideoIdSync(String playlist, String videoId);

    @Query("SELECT * FROM tracks WHERE playlistName = :playlist AND videoId = :videoId "
            + "AND downloaded = 1 LIMIT 1")
    Track getDownloadedByVideoIdSync(String playlist, String videoId);

    @Query("SELECT COUNT(*) FROM tracks WHERE filePath = :filePath")
    int countFileReferences(String filePath);

    @Query("DELETE FROM tracks WHERE playlistName = :playlist AND videoId = :videoId "
            + "AND downloaded = 0")
    void deletePending(String playlist, String videoId);
}
