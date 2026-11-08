package com.example.viby.library;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Paths, free-space estimation and Music folder scanning. */
public class StorageFilesTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void relativePathUsesMusicRootOrFallsBackToParentFolder() {
        File root = new File("/storage/emulated/0/Android/data/app/files/Music");
        assertEquals("Mix/song.mp3", LibraryPaths.relativePath(root,
                "/storage/emulated/0/Android/data/app/files/Music/Mix/song.mp3"));
        // another device / user id: still mapped through the Music segment
        assertEquals("Mix/song.mp3", LibraryPaths.relativePath(root,
                "/storage/emulated/10/Android/data/app/files/Music/Mix/song.mp3"));
        assertEquals("Downloads/song.mp3",
                LibraryPaths.relativePath(root, "/sdcard/Downloads/song.mp3"));
        assertEquals("Mix/song.mp3", LibraryPaths.relativePath(root, "C:\\Music\\Mix\\song.mp3"));
        assertNull(LibraryPaths.relativePath(root, ""));
        assertNull(LibraryPaths.relativePath(root, null));
    }

    @Test
    public void fileAndParentNamesHandleBothSeparators() {
        assertEquals("song.mp3", LibraryPaths.fileName("a\\b\\song.mp3"));
        assertEquals("b", LibraryPaths.parentName("/a/b/song.mp3"));
        assertNull(LibraryPaths.parentName("song.mp3"));
    }

    @Test
    public void spaceGuardRequiresEstimateAndSafetyMargin() {
        long fourMinutes = 4L * 60_000L;
        long required = StorageSpaceGuard.requiredBytes(fourMinutes);
        assertTrue(required > StorageSpaceGuard.SAFETY_MARGIN_BYTES);
        assertTrue(StorageSpaceGuard.hasEnoughSpace(required, fourMinutes));
        assertFalse(StorageSpaceGuard.hasEnoughSpace(required - 1, fourMinutes));
        // a 4 minute track needs roughly 20 MB besides the margin, not gigabytes
        long estimate = StorageSpaceGuard.estimateDownloadBytes(fourMinutes);
        assertTrue(estimate > 10L * 1024 * 1024 && estimate < 40L * 1024 * 1024);
    }

    @Test
    public void unknownDurationIsTreatedAsLongTrack() {
        assertTrue(StorageSpaceGuard.estimateDownloadBytes(0L)
                > StorageSpaceGuard.estimateDownloadBytes(10L * 60_000L));
    }

    @Test
    public void classifierRecognisesYtDlpLeftovers() {
        assertEquals(StorageScanner.Kind.TEMP, StorageScanner.classify("Song.webm.part"));
        assertEquals(StorageScanner.Kind.TEMP, StorageScanner.classify("Song.f251.webm"));
        assertEquals(StorageScanner.Kind.TEMP,
                StorageScanner.classify("Song.webm.part-Frag12"));
        assertEquals(StorageScanner.Kind.TEMP, StorageScanner.classify("Song.temp.mp3"));
        assertEquals(StorageScanner.Kind.TEMP, StorageScanner.classify("Song.ytdl"));
        assertEquals(StorageScanner.Kind.TEMP,
                StorageScanner.classify("Song.mp3.restore.tmp"));
        assertEquals(StorageScanner.Kind.AUDIO, StorageScanner.classify("Song.MP3"));
        assertEquals(StorageScanner.Kind.AUDIO, StorageScanner.classify("Song.m4a"));
        assertEquals(StorageScanner.Kind.IMAGE, StorageScanner.classify("Song.webp"));
        assertEquals(StorageScanner.Kind.OTHER, StorageScanner.classify("notes.txt"));
    }

    @Test
    public void scanSeparatesLibraryTempOrphansAndEmptyFolders() throws Exception {
        File root = temporaryFolder.newFolder("Music");
        File mix = new File(root, "Mix");
        File empty = new File(root, "Empty");
        assertTrue(mix.mkdirs());
        assertTrue(empty.mkdirs());
        File used = write(new File(mix, "used.mp3"), 100);
        write(new File(mix, "orphan.m4a"), 40);
        write(new File(mix, "half.webm.part"), 7);
        write(new File(mix, "cover.webp"), 3);
        write(new File(mix, "readme.txt"), 5);

        Set<String> referenced = new HashSet<>();
        referenced.add(used.getAbsolutePath());
        StorageScanner.Report report = StorageScanner.scan(root, referenced);

        assertEquals(100L, report.libraryBytes);
        assertEquals(1, report.libraryFiles);
        assertEquals(1, report.tempFiles.size());
        assertEquals(7L, report.tempBytes);
        assertEquals(2, report.orphanFiles.size());
        assertEquals(43L, report.orphanBytes);
        assertEquals(Collections.singletonList(empty), report.emptyDirectories);
        assertEquals(Long.valueOf(100L), report.folderBytes.get("Mix"));
    }

    @Test
    public void staleTempFilesIgnoreFreshPartialDownloads() throws Exception {
        File root = temporaryFolder.newFolder("Music");
        File old = write(new File(root, "old.webm.part"), 1);
        File fresh = write(new File(root, "fresh.webm.part"), 1);
        write(new File(root, "song.mp3"), 1);
        long now = System.currentTimeMillis();
        assertTrue(old.setLastModified(now - LibraryMaintenance.STALE_TEMP_AGE_MS - 1_000L));
        assertTrue(fresh.setLastModified(now));

        List<File> stale = StorageScanner.staleTempFiles(root,
                LibraryMaintenance.STALE_TEMP_AGE_MS, now);

        assertEquals(Collections.singletonList(old), stale);
    }

    private static File write(File file, int bytes) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(new byte[bytes]);
        }
        return file;
    }
}
