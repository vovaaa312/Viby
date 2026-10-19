package com.example.viby.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.yausername.youtubedl_android.YoutubeDLRequest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

public class MediaConverterTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final MediaConverter converter = new MediaConverter();

    @Test
    public void configureKeepsExistingFfmpegPostProcessingContract() {
        YoutubeDLRequest request = new YoutubeDLRequest("url");

        converter.configure(request, "m4a");

        assertTrue(request.hasOption("-x"));
        assertEquals("m4a", request.getOption("--audio-format"));
        assertEquals("0", request.getOption("--audio-quality"));
        assertTrue(request.hasOption("--embed-thumbnail"));
        assertTrue(request.hasOption("--embed-metadata"));
    }

    @Test
    public void findOutputPrefersRequestedFormat() throws Exception {
        File directory = temporaryFolder.newFolder("expected");
        File expected = new File(directory, "song.mp3");
        assertTrue(expected.createNewFile());

        assertEquals(expected, converter.findOutput(directory, "song", "mp3"));
    }

    @Test
    public void findOutputAcceptsActualFallbackExtension() throws Exception {
        File directory = temporaryFolder.newFolder("fallback");
        File actual = new File(directory, "song.m4a");
        assertTrue(actual.createNewFile());

        assertEquals(actual, converter.findOutput(directory, "song", "mp3"));
    }

    @Test
    public void findOutputRejectsMissingConversionResult() throws Exception {
        File directory = temporaryFolder.newFolder("missing");

        try {
            converter.findOutput(directory, "song", "mp3");
            fail("Missing output must fail");
        } catch (IllegalStateException error) {
            assertTrue(error.getMessage().contains("song"));
        }
    }
}
