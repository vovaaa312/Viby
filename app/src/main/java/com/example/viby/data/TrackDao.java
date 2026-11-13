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

    @Query("SELECT * FROM tracks WHERE id = :id LIMIT 1")
    Track getByIdSync(long id);

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM tracks WHERE playlistName = :playlist")
    int nextPosition(String playlist);

    /** Makes room at the top of a playlist for {@code count} new rows. */
    @Query("UPDATE tracks SET position = position + :count WHERE playlistName = :playlist")
    void shiftPositions(String playlist, int count);

    @Query("SELECT EXISTS(SELECT 1 FROM tracks WHERE playlistName = :playlist "
            + "AND videoId = :videoId AND downloaded = 1)")
    boolean isDownloaded(String playlist, String videoId);

    @Query("SELECT * FROM tracks WHERE playlistName = :playlist AND videoId = :videoId LIMIT 1")
    Track getByVideoIdSync(String playlist, String videoId);

    @Query("SELECT * FROM tracks WHERE playlistName = :playlist AND filePath = :filePath " +
            "LIMIT 1")
    Track getByFilePathSync(String playlist, String filePath);

    @Query("SELECT * FROM tracks WHERE playlistName = :playlist AND videoId = :videoId "
            + "AND downloaded = 1 LIMIT 1")
    Track getDownloadedByVideoIdSync(String playlist, String videoId);

    @Query("SELECT * FROM tracks WHERE videoId = :videoId AND downloaded = 1 ORDER BY id")
    List<Track> getDownloadedByVideoIdGloballySync(String videoId);

    @Query("SELECT COUNT(*) FROM tracks WHERE filePath = :filePath")
    int countFileReferences(String filePath);

    @Query("DELETE FROM tracks WHERE playlistName = :playlist AND videoId = :videoId "
            + "AND downloaded = 0")
    void deletePending(String playlist, String videoId);

    @Query("SELECT * FROM tracks ORDER BY playlistName, position, id")
    LiveData<List<Track>> observeAll();

    @Query("SELECT * FROM tracks ORDER BY playlistName, position, id")
    List<Track> getAllSync();

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    List<Track> getByIdsSync(List<Long> ids);

    /** Updates every row that shares the media file or the YouTube video with the given row. */
    @Query("UPDATE tracks SET favorite = :favorite WHERE id = :id "
            + "OR (:filePath IS NOT NULL AND :filePath != '' AND filePath = :filePath) "
            + "OR (:videoId IS NOT NULL AND :videoId != '' AND videoId = :videoId)")
    void setFavorite(long id, String filePath, String videoId, boolean favorite);

    @Query("UPDATE tracks SET playCount = playCount + 1, lastPlayedAt = :playedAt "
            + "WHERE id = :id OR (filePath != '' AND filePath = "
            + "(SELECT filePath FROM tracks WHERE id = :id))")
    void markPlayed(long id, long playedAt);

    @Query("SELECT * FROM tracks WHERE downloaded = 1 AND metadataScanned = 0 "
            + "AND filePath != '' LIMIT :limit")
    List<Track> getUnscannedSync(int limit);

    @Query("UPDATE tracks SET album = :album, metadataScanned = 1 WHERE filePath = :filePath")
    void updateScannedMetadata(String filePath, String album);

    @Query("SELECT * FROM tracks WHERE downloaded = 1 AND loudnessScanned = 0 "
            + "AND filePath != '' ORDER BY lastPlayedAt DESC, id DESC LIMIT :limit")
    List<Track> getLoudnessPendingSync(int limit);

    /** Stores the measurement for every playlist row that shares the file. */
    @Query("UPDATE tracks SET loudnessLufs = :lufs, loudnessPeak = :peak, "
            + "loudnessScanned = 1 WHERE filePath = :filePath")
    void updateLoudness(String filePath, Float lufs, Float peak);

    @Query("SELECT COUNT(DISTINCT filePath) FROM tracks WHERE downloaded = 1 "
            + "AND filePath != ''")
    int countDownloadedFiles();

    @Query("SELECT COUNT(DISTINCT filePath) FROM tracks WHERE downloaded = 1 "
            + "AND filePath != '' AND loudnessScanned = 1")
    int countLoudnessScannedFiles();

    @Query("UPDATE tracks SET filePath = :filePath, downloaded = 1, "
            + "downloadState = 'DOWNLOADED', downloadErrorCode = NULL, "
            + "downloadErrorMessage = NULL, downloadProgress = 100 WHERE id = :id")
    void relinkFile(long id, String filePath);

    @Query("UPDATE tracks SET downloaded = 0, downloadState = :state, "
            + "downloadErrorCode = :errorCode, downloadErrorMessage = :errorMessage, "
            + "downloadProgress = 0, sourceUrl = COALESCE(sourceUrl, :sourceUrl) "
            + "WHERE id = :id")
    void markFileMissing(long id, String state, String errorCode, String errorMessage,
                         String sourceUrl);

    @Query("SELECT DISTINCT filePath FROM tracks WHERE filePath != ''")
    List<String> getReferencedFilePathsSync();
}
