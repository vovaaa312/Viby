package com.example.viby.library;

import androidx.annotation.Nullable;

import java.io.File;

/** Portable paths of library files relative to the Music root (also the mirror layout). */
public final class LibraryPaths {

    private LibraryPaths() {
    }

    /**
     * "Playlist/track.mp3" for a file inside {@code musicRoot}. Files outside it keep only
     * their parent folder and name so they still map onto the mirror layout.
     */
    @Nullable
    public static String relativePath(File musicRoot, @Nullable String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return null;
        }
        String path = normalize(filePath);
        String root = normalize(musicRoot.getAbsolutePath());
        if (!root.endsWith("/")) {
            root = root + "/";
        }
        if (path.startsWith(root)) {
            return path.substring(root.length());
        }
        int music = path.lastIndexOf("/Music/");
        if (music >= 0) {
            return path.substring(music + "/Music/".length());
        }
        String name = fileName(path);
        String parent = parentName(path);
        return parent != null ? parent + "/" + name : name;
    }

    public static File resolve(File musicRoot, String relativePath) {
        return new File(musicRoot, relativePath.replace('/', File.separatorChar));
    }

    public static String fileName(String path) {
        String normalized = normalize(path);
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    @Nullable
    public static String parentName(String path) {
        String normalized = normalize(path);
        int slash = normalized.lastIndexOf('/');
        if (slash <= 0) {
            return null;
        }
        String parent = normalized.substring(0, slash);
        int parentSlash = parent.lastIndexOf('/');
        String name = parentSlash >= 0 ? parent.substring(parentSlash + 1) : parent;
        return name.isEmpty() || name.endsWith(":") ? null : name;
    }

    static String normalize(String path) {
        return path.replace('\\', '/');
    }
}
