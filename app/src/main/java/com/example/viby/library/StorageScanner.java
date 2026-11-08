package com.example.viby.library;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Walks the Music folder and splits it into library, temporary and orphaned files. */
public final class StorageScanner {

    public enum Kind { AUDIO, TEMP, IMAGE, OTHER }

    /** yt-dlp intermediate streams look like "name.f251.webm". */
    private static final Pattern FORMAT_FRAGMENT = Pattern.compile(".*\\.f\\d+\\.[a-z0-9]+$");
    private static final int MAX_DEPTH = 6;

    public static final class Report {
        public long libraryBytes;
        public int libraryFiles;
        public long tempBytes;
        public final List<File> tempFiles = new ArrayList<>();
        public long orphanBytes;
        public final List<File> orphanFiles = new ArrayList<>();
        public final List<File> emptyDirectories = new ArrayList<>();
        /** Folder name → bytes of referenced files, largest first. */
        public final Map<String, Long> folderBytes = new LinkedHashMap<>();
    }

    private StorageScanner() {
    }

    public static Kind classify(String fileName) {
        String name = fileName.toLowerCase(Locale.US);
        if (name.endsWith(".part") || name.contains(".part-frag") || name.endsWith(".ytdl")
                || name.endsWith(".temp") || name.endsWith(".tmp") || name.contains(".temp.")
                || FORMAT_FRAGMENT.matcher(name).matches()) {
            return Kind.TEMP;
        }
        if (name.endsWith(".webp") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".png")) {
            return Kind.IMAGE;
        }
        if (name.endsWith(".mp3") || name.endsWith(".m4a") || name.endsWith(".opus")
                || name.endsWith(".ogg") || name.endsWith(".oga") || name.endsWith(".flac")
                || name.endsWith(".wav") || name.endsWith(".aac") || name.endsWith(".webm")
                || name.endsWith(".mka") || name.endsWith(".wma") || name.endsWith(".mp4")) {
            return Kind.AUDIO;
        }
        return Kind.OTHER;
    }

    /** @param referencedPaths absolute paths that Room rows point to */
    public static Report scan(File root, Set<String> referencedPaths) {
        Report report = new Report();
        Map<String, Long> folders = new LinkedHashMap<>();
        walk(root, root, referencedPaths, report, folders, 0);
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(folders.entrySet());
        Collections.sort(sorted, (a, b) -> Long.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Long> entry : sorted) {
            report.folderBytes.put(entry.getKey(), entry.getValue());
        }
        return report;
    }

    private static boolean walk(File root, File directory, Set<String> referenced,
                                Report report, Map<String, Long> folders, int depth) {
        File[] children = directory.listFiles();
        if (children == null || children.length == 0) {
            if (!directory.equals(root)) {
                report.emptyDirectories.add(directory);
            }
            return true;
        }
        boolean onlyEmptyChildren = true;
        for (File child : children) {
            if (child.isDirectory()) {
                if (depth >= MAX_DEPTH || !walk(root, child, referenced, report, folders,
                        depth + 1)) {
                    onlyEmptyChildren = false;
                }
                continue;
            }
            onlyEmptyChildren = false;
            long length = child.length();
            if (referenced.contains(child.getAbsolutePath())) {
                report.libraryBytes += length;
                report.libraryFiles++;
                String folder = directory.equals(root) ? "" : directory.getName();
                Long current = folders.get(folder);
                folders.put(folder, (current != null ? current : 0L) + length);
                continue;
            }
            Kind kind = classify(child.getName());
            if (kind == Kind.TEMP) {
                report.tempFiles.add(child);
                report.tempBytes += length;
            } else if (kind == Kind.AUDIO || kind == Kind.IMAGE) {
                report.orphanFiles.add(child);
                report.orphanBytes += length;
            }
        }
        if (onlyEmptyChildren && !directory.equals(root)) {
            report.emptyDirectories.add(directory);
        }
        return onlyEmptyChildren;
    }

    /** Temporary files untouched for {@code maxAgeMs}: safe even while downloads run. */
    public static List<File> staleTempFiles(File root, long maxAgeMs, long now) {
        List<File> result = new ArrayList<>();
        collectStale(root, maxAgeMs, now, result, 0);
        return result;
    }

    private static void collectStale(File directory, long maxAgeMs, long now,
                                     List<File> result, int depth) {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                if (depth < MAX_DEPTH) {
                    collectStale(child, maxAgeMs, now, result, depth + 1);
                }
            } else if (classify(child.getName()) == Kind.TEMP
                    && now - child.lastModified() >= maxAgeMs) {
                result.add(child);
            }
        }
    }

    public static long totalBytes(List<File> files) {
        long total = 0L;
        for (File file : files) {
            total += file.length();
        }
        return total;
    }

    public static long directoryBytes(File directory) {
        File[] children = directory.listFiles();
        if (children == null) {
            return 0L;
        }
        long total = 0L;
        for (File child : children) {
            total += child.isDirectory() ? directoryBytes(child) : child.length();
        }
        return total;
    }
}
