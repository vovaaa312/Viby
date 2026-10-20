package com.example.viby.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;

public class LibraryFileControllerTest {

    @Test
    public void stripExtensionKeepsNamesWithoutUsableExtension() {
        assertEquals("track", LibraryFileController.stripExtension("track.mp3"));
        assertEquals("archive.tar", LibraryFileController.stripExtension("archive.tar.m4a"));
        assertEquals("track", LibraryFileController.stripExtension("track"));
        assertEquals(".hidden", LibraryFileController.stripExtension(".hidden"));
    }

    @Test
    public void durationParsingFallsBackForMissingOrInvalidMetadata() {
        assertEquals(1234L, LibraryFileController.parseDuration("1234"));
        assertEquals(0L, LibraryFileController.parseDuration(null));
        assertEquals(0L, LibraryFileController.parseDuration("unknown"));
    }

    @Test
    public void sharedAndMissingPathsAreNeverDeleted() {
        assertTrue(LibraryFileController.shouldDeleteFile("C:\\music\\track.m4a", 0));
        assertFalse(LibraryFileController.shouldDeleteFile("C:\\music\\track.m4a", 1));
        assertFalse(LibraryFileController.shouldDeleteFile("", 0));
        assertFalse(LibraryFileController.shouldDeleteFile(null, 0));
    }

    @Test
    public void uniqueDestinationUsesSuffixOnlyWhenOriginalExists() throws Exception {
        File directory = java.nio.file.Files.createTempDirectory("viby-library").toFile();
        File original = new File(directory, "track.mp3");
        try {
            assertEquals(original, LibraryFileController.uniqueDestination(
                    directory, "track.mp3", 42L));
            assertTrue(original.createNewFile());
            assertEquals(new File(directory, "42 track.mp3"),
                    LibraryFileController.uniqueDestination(
                            directory, "track.mp3", 42L));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            original.delete();
            //noinspection ResultOfMethodCallIgnored
            directory.delete();
        }
    }
}
