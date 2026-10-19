package com.example.viby.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.yausername.youtubedl_android.YoutubeDLRequest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

public class AudioDownloaderTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void requestKeepsAudioMetadataAuthenticationAndEscapedTemplate()
            throws Exception {
        AudioDownloader downloader = downloader((request, processId, listener) -> { });
        File directory = temporaryFolder.newFolder("audio");

        YoutubeDLRequest request = downloader.buildRequest(
                "https://youtu.be/id", directory, "100% song", "m4a", null);

        assertTrue(request.hasOption("--no-playlist"));
        assertTrue(request.hasOption("-x"));
        assertEquals("m4a", request.getOption("--audio-format"));
        assertEquals("0", request.getOption("--audio-quality"));
        assertTrue(request.hasOption("--embed-thumbnail"));
        assertTrue(request.hasOption("--embed-metadata"));
        assertEquals("cookie.txt", request.getOption("--cookies"));
        assertTrue(request.getOption("-o").contains("100%% song.%(ext)s"));
        assertFalse(request.hasOption("--extractor-args"));
    }

    @Test
    public void alternatePlayerClientsAreAppliedOnlyWhenRequested() throws Exception {
        AudioDownloader downloader = downloader((request, processId, listener) -> { });

        YoutubeDLRequest request = downloader.buildRequest(
                "url", temporaryFolder.newFolder("alternate"),
                "song", "mp3", "default,tv,mweb");

        assertEquals("youtube:player_client=default,tv,mweb",
                request.getOption("--extractor-args"));
    }

    @Test
    public void downloadDelegatesProgressAndReturnsCreatedFile() throws Exception {
        File directory = temporaryFolder.newFolder("result");
        File expected = new File(directory, "song.mp3");
        final int[] callbackProgress = {-1};
        AudioDownloader downloader = downloader((request, processId, listener) -> {
            assertEquals("job-7", processId);
            assertTrue(expected.createNewFile());
            listener.onProgress(45f, 3L, "[download] 45%");
        });

        File actual = downloader.download("url", directory, "song", "mp3",
                null, "job-7", (progress, eta, line) ->
                        callbackProgress[0] = Math.round(progress));

        assertEquals(expected, actual);
        assertEquals(45, callbackProgress[0]);
    }

    private static AudioDownloader downloader(AudioDownloader.Backend backend) {
        YoutubeExtractor extractor = new YoutubeExtractor(
                request -> request.addOption("--cookies", "cookie.txt"),
                new YoutubeExtractor.Backend() {
                    @Override
                    public com.yausername.youtubedl_android.mapper.VideoInfo getInfo(
                            YoutubeDLRequest request) {
                        return null;
                    }

                    @Override
                    public com.yausername.youtubedl_android.YoutubeDLResponse execute(
                            YoutubeDLRequest request, String processId) {
                        return null;
                    }
                });
        return new AudioDownloader(extractor, new MediaConverter(), backend);
    }
}
