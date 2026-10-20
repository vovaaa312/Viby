package com.example.viby.ui;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Toast;

import com.example.viby.R;
import com.example.viby.data.PlaylistTrackCopier;
import com.example.viby.data.Track;
import com.example.viby.data.TrackDao;
import com.example.viby.data.VibyDatabase;
import com.example.viby.util.StorageHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/** Owns local library rows and their shared audio files. */
final class LibraryFileController {

    private static final String TAG = "LibraryFileController";
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    LibraryFileController(Context context) {
        this.context = context.getApplicationContext();
    }

    String sanitizePlaylistName(String playlist) {
        return StorageHelper.sanitize(playlist);
    }

    void copyTracks(List<Track> tracks, String targetPlaylist, Runnable afterCopy) {
        VibyDatabase.dbExecutor.execute(() -> {
            TrackDao dao = VibyDatabase.get(context).trackDao();
            int total = PlaylistTrackCopier.create(dao).copy(tracks, targetPlaylist);
            post(() -> {
                toast(context.getString(R.string.tracks_copied, total));
                if (afterCopy != null) {
                    afterCopy.run();
                }
            });
        });
    }

    void deleteTracks(List<Track> tracks, boolean deleteFiles, Runnable beforeDelete) {
        if (beforeDelete != null) {
            beforeDelete.run();
        }
        VibyDatabase.dbExecutor.execute(() -> {
            TrackDao dao = VibyDatabase.get(context).trackDao();
            for (Track track : tracks) {
                dao.delete(track);
                if (deleteFiles) {
                    deleteFileIfUnreferenced(dao, track.filePath);
                }
            }
            post(() -> toast(context.getString(
                    R.string.tracks_deleted, tracks.size())));
        });
    }

    void deletePlaylist(String playlist) {
        VibyDatabase.dbExecutor.execute(() -> {
            VibyDatabase database = VibyDatabase.get(context);
            TrackDao dao = database.trackDao();
            List<Track> tracks = dao.getPlaylistSync(playlist);
            for (Track track : tracks) {
                dao.delete(track);
                deleteFileIfUnreferenced(dao, track.filePath);
            }
            File directory = StorageHelper.playlistDir(context, playlist);
            File[] leftovers = directory.listFiles();
            if (leftovers != null) {
                for (File file : leftovers) {
                    if (dao.countFileReferences(file.getAbsolutePath()) == 0) {
                        //noinspection ResultOfMethodCallIgnored
                        file.delete();
                    }
                }
            }
            //noinspection ResultOfMethodCallIgnored
            directory.delete();
            database.playlistSourceDao().delete(playlist);
            post(() -> toast(context.getString(R.string.playlist_deleted)));
        });
    }

    void importFiles(List<Uri> uris, String playlist) {
        if (uris == null || uris.isEmpty() || playlist == null) {
            return;
        }
        toast(context.getString(R.string.importing_tracks));
        VibyDatabase.dbExecutor.execute(() -> {
            TrackDao dao = VibyDatabase.get(context).trackDao();
            File directory = StorageHelper.playlistDir(context, playlist);
            int added = 0;
            for (Uri uri : uris) {
                if (importFile(dao, directory, uri, playlist)) {
                    added++;
                }
            }
            int total = added;
            post(() -> toast(context.getString(R.string.tracks_added, total)));
        });
    }

    private boolean importFile(TrackDao dao, File directory, Uri uri,
                               String playlist) {
        File destination = null;
        try {
            String name = displayName(uri);
            destination = uniqueDestination(directory, StorageHelper.sanitize(name),
                    System.currentTimeMillis());
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                 OutputStream output = new FileOutputStream(destination)) {
                if (input == null) {
                    throw new IllegalStateException("Cannot open " + uri);
                }
                byte[] buffer = new byte[COPY_BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    output.write(buffer, 0, read);
                }
            }

            MediaInfo metadata = readMetadata(destination);
            Track track = new Track();
            track.title = hasText(metadata.title)
                    ? metadata.title : stripExtension(destination.getName());
            track.uploader = metadata.artist;
            track.durationMs = metadata.durationMs;
            track.filePath = destination.getAbsolutePath();
            track.playlistName = playlist;
            track.position = dao.nextPosition(playlist);
            track.createdAt = System.currentTimeMillis();
            dao.insert(track);
            return true;
        } catch (Exception error) {
            Log.w(TAG, "Import failed: " + uri, error);
            if (destination != null) {
                //noinspection ResultOfMethodCallIgnored
                destination.delete();
            }
            return false;
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (hasText(name)) {
                    return name;
                }
            }
        } catch (Exception ignored) {
        }
        return "track_" + System.currentTimeMillis() + ".mp3";
    }

    private static MediaInfo readMetadata(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String duration = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            return new MediaInfo(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    parseDuration(duration));
        } catch (Exception ignored) {
            return new MediaInfo(null, null, 0L);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    static File uniqueDestination(File directory, String name, long suffix) {
        File destination = new File(directory, name);
        return destination.exists()
                ? new File(directory, suffix + " " + destination.getName())
                : destination;
    }

    static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static long parseDuration(String duration) {
        if (duration == null) {
            return 0L;
        }
        try {
            return Long.parseLong(duration);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    static boolean shouldDeleteFile(String filePath, int references) {
        return filePath != null && !filePath.isEmpty() && references == 0;
    }

    private static void deleteFileIfUnreferenced(TrackDao dao, String filePath) {
        if (!shouldDeleteFile(filePath,
                filePath == null || filePath.isEmpty()
                        ? 1 : dao.countFileReferences(filePath))) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        new File(filePath).delete();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isEmpty();
    }

    private void post(Runnable action) {
        mainHandler.post(action);
    }

    private void toast(String message) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    private static final class MediaInfo {
        final String title;
        final String artist;
        final long durationMs;

        MediaInfo(String title, String artist, long durationMs) {
            this.title = title;
            this.artist = artist;
            this.durationMs = durationMs;
        }
    }
}
