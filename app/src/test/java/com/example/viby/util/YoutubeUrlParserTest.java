package com.example.viby.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class YoutubeUrlParserTest {

    @Test
    public void extractsCommonYoutubeVideoUrls() {
        assertEquals("abcdefghijk", YoutubeUrlParser.videoId(
                "https://www.youtube.com/watch?v=abcdefghijk&list=PL123"));
        assertEquals("abcdefghijk", YoutubeUrlParser.videoId(
                "https://youtu.be/abcdefghijk?si=share"));
        assertEquals("abcdefghijk", YoutubeUrlParser.videoId(
                "https://music.youtube.com/watch?v=abcdefghijk"));
        assertEquals("abcdefghijk", YoutubeUrlParser.videoId(
                "https://youtube.com/shorts/abcdefghijk"));
    }

    @Test
    public void rejectsNonYoutubeAndPlaylistOnlyUrls() {
        assertNull(YoutubeUrlParser.videoId("https://example.com/watch?v=abcdefghijk"));
        assertNull(YoutubeUrlParser.videoId(
                "https://www.youtube.com/playlist?list=PL123"));
    }

    @Test
    public void extractsPlaylistIdsFromYoutubeUrls() {
        assertEquals("PL123", YoutubeUrlParser.playlistId(
                "https://www.youtube.com/playlist?list=PL123"));
        assertEquals("PL_MUSIC", YoutubeUrlParser.playlistId(
                "https://music.youtube.com/watch?v=abcdefghijk&list=PL_MUSIC"));
        assertNull(YoutubeUrlParser.playlistId(
                "https://example.com/playlist?list=PL123"));
        assertNull(YoutubeUrlParser.playlistId(
                "https://www.youtube.com/watch?v=abcdefghijk"));
    }

    @Test
    public void buildsAndRecognizesTrackRadioUrls() {
        String radio = YoutubeUrlParser.radioMixUrl("abcdefghijk");

        assertEquals("https://www.youtube.com/watch?v=abcdefghijk"
                + "&list=RDabcdefghijk", radio);
        assertTrue(YoutubeUrlParser.isRadioMixUrl(radio));
        assertTrue(YoutubeUrlParser.isRadioPlaylistId("RDabcdefghijk"));
        assertFalse(YoutubeUrlParser.isRadioMixUrl(
                "https://www.youtube.com/playlist?list=PL123"));
    }
}
