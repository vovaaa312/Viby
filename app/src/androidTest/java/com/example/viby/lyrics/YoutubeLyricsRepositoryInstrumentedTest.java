package com.example.viby.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@RunWith(AndroidJUnit4.class)
public class YoutubeLyricsRepositoryInstrumentedTest {

    @Test
    public void extractsBalancedPlayerResponseWithoutEatingPageJavascript() throws Exception {
        String page = "<script>var ignored={\"brace\":\"} inside string\"};</script>"
                + "<script>ytInitialPlayerResponse = {\"videoDetails\":{\"title\":\"Song\"},"
                + "\"nested\":{\"value\":\"escaped \\\" quote\"}};</script>tail";

        JSONObject response = YoutubeLyricsRepository.extractPlayerResponse(page);

        assertEquals("Song", response.getJSONObject("videoDetails").getString("title"));
        assertEquals("escaped \" quote",
                response.getJSONObject("nested").getString("value"));
    }

    @Test
    public void json3ParserJoinsSegmentsNormalizesWhitespaceAndDropsAdjacentDuplicates()
            throws Exception {
        JSONObject payload = new JSONObject().put("events", new JSONArray()
                .put(event(" First ", " line\nSecond"))
                .put(event("Second"))
                .put(new JSONObject().put("other", true))
                .put(event("Third   line")));

        assertEquals("First line\nSecond\nThird line",
                YoutubeLyricsRepository.parseJson3(payload.toString()));
        assertEquals("", YoutubeLyricsRepository.parseJson3("{}"));
    }

    @Test
    public void findsNestedYoutubeMusicLyricsTabAndTimedLyrics() throws Exception {
        JSONObject endpoint = new JSONObject()
                .put("browseId", "MPLYt_lyrics")
                .put("browseEndpointContextSupportedConfigs", new JSONObject()
                        .put("browseEndpointContextMusicConfig", new JSONObject()
                                .put("pageType", "MUSIC_PAGE_TYPE_TRACK_LYRICS")));
        JSONObject next = new JSONObject().put("deep", new JSONArray()
                .put(new JSONObject().put("browseEndpoint", endpoint)));
        assertEquals("MPLYt_lyrics",
                YoutubeLyricsRepository.findLyricsBrowseId(next));

        JSONObject timed = new JSONObject()
                .put("timedLyricsData", new JSONArray()
                        .put(new JSONObject().put("lyricLine", "One"))
                        .put(new JSONObject().put("lyricLine", "One"))
                        .put(new JSONObject().put("lyricLine", "Two\nThree")))
                .put("sourceMessage", new JSONObject().put("runs", new JSONArray()
                        .put(new JSONObject().put("text", "Source: "))
                        .put(new JSONObject().put("text", "Provider"))));

        YoutubeLyricsRepository.MusicLyrics lyrics =
                YoutubeLyricsRepository.findMusicLyrics(timed);
        assertNotNull(lyrics);
        assertEquals("One\nTwo\nThree", lyrics.text);
        assertEquals("Source: Provider", lyrics.attribution);
    }

    @Test
    public void manualCaptionWinsAndLocaleThenEnglishThenFirstIsPreferred() throws Exception {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("cs-CZ"));
            List<YoutubeLyricsRepository.Caption> captions = Arrays.asList(
                    new YoutubeLyricsRepository.Caption("de", "de", false),
                    new YoutubeLyricsRepository.Caption("en", "en-US", false),
                    new YoutubeLyricsRepository.Caption("cs", "cs-CZ", false));
            assertEquals("cs", YoutubeLyricsRepository.selectLanguage(captions).url);

            Locale.setDefault(Locale.JAPANESE);
            assertEquals("en", YoutubeLyricsRepository.selectLanguage(captions).url);
            assertNull(YoutubeLyricsRepository.selectLanguage(new ArrayList<>()));

            JSONObject response = playerResponseWithCaptions(
                    caption("automatic", "cs", true),
                    caption("manual", "de", false));
            YoutubeLyricsRepository.Caption selected =
                    YoutubeLyricsRepository.chooseCaption(response);
            assertEquals("manual", selected.url);
            assertFalse(selected.automatic);
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    public void urlAndConsentCookieNormalizationHandlesAllForms() {
        assertEquals("https://host/path",
                YoutubeLyricsRepository.absoluteYoutubeUrl("//host/path"));
        assertEquals("https://www.youtube.com/api/timedtext",
                YoutubeLyricsRepository.absoluteYoutubeUrl("/api/timedtext"));
        assertEquals("https://other/path",
                YoutubeLyricsRepository.absoluteYoutubeUrl("https://other/path"));
        assertEquals("SOCS=CAI", YoutubeLyricsRepository.appendConsentCookie(null));
        assertEquals("SID=x; SOCS=CAI",
                YoutubeLyricsRepository.appendConsentCookie("SID=x"));
        assertEquals("SID=x; SOCS=ok",
                YoutubeLyricsRepository.appendConsentCookie("SID=x; SOCS=ok"));
    }

    private static JSONObject event(String... pieces) throws Exception {
        JSONArray segments = new JSONArray();
        for (String piece : pieces) {
            segments.put(new JSONObject().put("utf8", piece));
        }
        return new JSONObject().put("segs", segments);
    }

    private static JSONObject caption(String url, String language, boolean automatic)
            throws Exception {
        JSONObject result = new JSONObject()
                .put("baseUrl", url)
                .put("languageCode", language);
        if (automatic) {
            result.put("kind", "asr");
        }
        return result;
    }

    private static JSONObject playerResponseWithCaptions(JSONObject... tracks)
            throws Exception {
        JSONArray array = new JSONArray();
        for (JSONObject track : tracks) {
            array.put(track);
        }
        return new JSONObject().put("captions", new JSONObject()
                .put("playerCaptionsTracklistRenderer", new JSONObject()
                        .put("captionTracks", array)));
    }
}
