package com.example.viby.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;
import com.yausername.youtubedl_android.mapper.VideoInfo;

import org.junit.Test;

public class YoutubeExtractorTest {

    @Test
    public void videoRequestUsesAuthenticationWithoutPlaylistFlags() {
        YoutubeDLRequest request = extractor().videoInfoRequest("https://youtu.be/id");

        assertEquals("cookies.txt", request.getOption("--cookies"));
        assertFalse(request.hasOption("--flat-playlist"));
        assertTrue(request.buildCommand().contains("https://youtu.be/id"));
    }

    @Test
    public void playlistRequestKeepsClassificationAndRadioOptions() {
        YoutubeExtractor extractor = extractor();
        YoutubeDLRequest normal = extractor.playlistInfoRequest("playlist", false);
        YoutubeDLRequest radio = extractor.playlistInfoRequest("radio", true);

        assertTrue(normal.hasOption("--flat-playlist"));
        assertTrue(normal.hasOption("--dump-single-json"));
        assertTrue(normal.hasOption("--no-warnings"));
        assertEquals("youtubetab:skip=authcheck",
                normal.getOption("--extractor-args"));
        assertFalse(normal.hasOption("--playlist-end"));
        assertEquals(String.valueOf(YoutubeExtractor.RADIO_TRACK_LIMIT),
                radio.getOption("--playlist-end"));
        assertEquals("cookies.txt", radio.getOption("--cookies"));
    }

    @Test
    public void metadataExecutionIsDelegatedToBackend() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        YoutubeExtractor extractor = new YoutubeExtractor(request -> { }, backend);
        YoutubeDLRequest request = new YoutubeDLRequest("url");

        assertNull(extractor.getVideoInfo(request));
        assertNull(extractor.getPlaylistInfo(request, "process"));
        assertEquals(1, backend.infoCalls);
        assertEquals(1, backend.playlistCalls);
        assertEquals("process", backend.processId);
    }

    private static YoutubeExtractor extractor() {
        return new YoutubeExtractor(
                request -> request.addOption("--cookies", "cookies.txt"),
                new RecordingBackend());
    }

    private static final class RecordingBackend implements YoutubeExtractor.Backend {
        int infoCalls;
        int playlistCalls;
        String processId;

        @Override
        public VideoInfo getInfo(YoutubeDLRequest request) {
            infoCalls++;
            return null;
        }

        @Override
        public YoutubeDLResponse execute(YoutubeDLRequest request, String processId) {
            playlistCalls++;
            this.processId = processId;
            return null;
        }
    }
}
