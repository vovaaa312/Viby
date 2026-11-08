package com.example.viby.library;

import androidx.annotation.Nullable;

import com.example.viby.data.Track;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Re-attaches Room rows to audio files after a reinstall, a restored backup or files moved
 * by hand. Lookup order: the stored path, the same relative path under the current Music
 * root, the playlist folder, then the user-selected mirror folder.
 */
public final class LibraryRelinker {

    public interface Mirror {
        /** Copies {@code relativePath} from the mirror into {@code destination}. */
        boolean restore(String relativePath, File destination);
    }

    public interface Store {
        void relink(Track track, String filePath);

        /** The file is gone: turn the row into a re-downloadable or failed placeholder. */
        void markMissing(Track track);
    }

    public static final class Result {
        public int checked;
        public int alreadyOk;
        public int relinked;
        public int restoredFromMirror;
        public int markedMissing;
        public int stillMissing;
    }

    private final Store store;

    public LibraryRelinker(Store store) {
        this.store = store;
    }

    public Result relink(List<Track> tracks, File musicRoot, @Nullable Mirror mirror) {
        Result result = new Result();
        Map<String, String> resolved = new HashMap<>();
        for (Track track : tracks) {
            if (track.filePath == null || track.filePath.isEmpty()) {
                continue; // placeholders of running downloads never had a file
            }
            result.checked++;
            if (track.downloaded && new File(track.filePath).isFile()) {
                result.alreadyOk++;
                continue;
            }
            String relative = LibraryPaths.relativePath(musicRoot, track.filePath);
            String found = relative != null ? resolved.get(relative) : null;
            boolean fromMirror = false;
            if (found == null) {
                found = findLocal(track, musicRoot, relative);
            }
            if (found == null && mirror != null && relative != null) {
                File destination = LibraryPaths.resolve(musicRoot, relative);
                File parent = destination.getParentFile();
                if (parent != null) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                if (mirror.restore(relative, destination) && destination.isFile()) {
                    found = destination.getAbsolutePath();
                    fromMirror = true;
                }
            }
            if (found != null) {
                if (relative != null) {
                    resolved.put(relative, found);
                }
                store.relink(track, found);
                if (fromMirror) {
                    result.restoredFromMirror++;
                } else {
                    result.relinked++;
                }
            } else if (track.downloaded) {
                store.markMissing(track);
                result.markedMissing++;
            } else {
                result.stillMissing++;
            }
        }
        return result;
    }

    @Nullable
    private static String findLocal(Track track, File musicRoot, @Nullable String relative) {
        if (new File(track.filePath).isFile()) {
            return track.filePath;
        }
        if (relative != null) {
            File candidate = LibraryPaths.resolve(musicRoot, relative);
            if (candidate.isFile()) {
                return candidate.getAbsolutePath();
            }
        }
        String name = LibraryPaths.fileName(track.filePath);
        File inPlaylist = new File(new File(musicRoot, track.playlistName), name);
        return inPlaylist.isFile() ? inPlaylist.getAbsolutePath() : null;
    }
}
