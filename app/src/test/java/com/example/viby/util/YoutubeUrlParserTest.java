package com.example.viby.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
}
