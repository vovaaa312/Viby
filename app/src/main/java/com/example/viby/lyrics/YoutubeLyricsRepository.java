package com.example.viby.lyrics;

import android.content.Context;

import androidx.annotation.Nullable;

import com.example.viby.data.Track;
import com.example.viby.data.VibyDatabase;
import com.example.viby.util.YoutubeUrlParser;
import com.example.viby.util.YtCookies;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

/** Loads the subtitle text exposed on a track's YouTube watch page. */
public final class YoutubeLyricsRepository {

    private static final int MAX_WATCH_PAGE_CHARS = 8_000_000;
    private static final int MAX_CAPTION_CHARS = 2_000_000;
    private static final int MAX_MUSIC_RESPONSE_CHARS = 8_000_000;
    private static final String YTM_API = "https://music.youtube.com/youtubei/v1/";
    // Public web-client key used by YouTube Music itself (not a project secret).
    private static final String YTM_API_KEY = "";
    private static final Map<String, Lyrics> memoryCache = new ConcurrentHashMap<>();

    public enum MissingReason {
        NO_YOUTUBE_SOURCE,
        NOT_FOUND
    }

    public static final class Lyrics {
        public final String text;
        public final Source source;
        public final String detail;

        Lyrics(String text, Source source, String detail) {
            this.text = text;
            this.source = source;
            this.detail = detail;
        }
    }

    public enum Source {
        YOUTUBE_MUSIC,
        MANUAL_SUBTITLES,
        AUTOMATIC_SUBTITLES
    }

    public static final class MissingLyricsException extends Exception {
        public final MissingReason reason;

        MissingLyricsException(MissingReason reason) {
            this.reason = reason;
        }
    }

    private static final class Caption {
        final String url;
        final String language;
        final boolean automatic;

        Caption(String url, String language, boolean automatic) {
            this.url = url;
            this.language = language;
            this.automatic = automatic;
        }
    }

    private static final class MusicLyrics {
        final String text;
        final String attribution;

        MusicLyrics(String text, String attribution) {
            this.text = text;
            this.attribution = attribution;
        }
    }

    private YoutubeLyricsRepository() {
    }

    public static Lyrics load(Context context, String mediaId,
                              @Nullable String metadataVideoId,
                              @Nullable String metadataSourceUrl) throws Exception {
        Context app = context.getApplicationContext();
        String videoId = clean(metadataVideoId);
        String sourceUrl = clean(metadataSourceUrl);

        if (videoId == null && sourceUrl != null) {
            videoId = YoutubeUrlParser.videoId(sourceUrl);
        }
        if (videoId == null) {
            Track track = findTrack(app, mediaId);
            if (track != null) {
                videoId = clean(track.videoId);
                sourceUrl = clean(track.sourceUrl);
                if (videoId == null) {
                    videoId = YoutubeUrlParser.videoId(sourceUrl);
                }
            }
        }
        if (videoId == null) {
            throw new MissingLyricsException(MissingReason.NO_YOUTUBE_SOURCE);
        }

        Lyrics cached = memoryCache.get(videoId);
        if (cached != null) {
            return cached;
        }

        String watchUrl = "https://www.youtube.com/watch?v=" + videoId
                + "&hl=" + Locale.getDefault().getLanguage()
                + "&bpctr=9999999999&has_verified=1";
        String cookies = cookieHeader(app);
        Exception musicError = null;
        try {
            MusicLyrics musicLyrics = loadYoutubeMusicLyrics(videoId, cookies);
            if (musicLyrics != null && !musicLyrics.text.isEmpty()) {
                Lyrics result = new Lyrics(musicLyrics.text, Source.YOUTUBE_MUSIC,
                        musicLyrics.attribution);
                memoryCache.put(videoId, result);
                return result;
            }
        } catch (Exception error) {
            // Ordinary video subtitles remain a useful fallback if YouTube Music
            // temporarily changes or rejects its internal lyrics endpoint.
            musicError = error;
        }

        String watchPage = download(watchUrl, cookies, MAX_WATCH_PAGE_CHARS);
        JSONObject playerResponse = extractPlayerResponse(watchPage);
        Caption caption = chooseCaption(playerResponse);
        if (caption == null) {
            if (musicError != null) {
                throw musicError;
            }
            throw new MissingLyricsException(MissingReason.NOT_FOUND);
        }

        String captionUrl = absoluteYoutubeUrl(caption.url);
        String separator = captionUrl.contains("?") ? "&" : "?";
        String payload = download(captionUrl + separator + "fmt=json3",
                cookies, MAX_CAPTION_CHARS);
        String text = parseJson3(payload);
        if (text.isEmpty()) {
            throw new MissingLyricsException(MissingReason.NOT_FOUND);
        }
        Lyrics result = new Lyrics(text,
                caption.automatic ? Source.AUTOMATIC_SUBTITLES : Source.MANUAL_SUBTITLES,
                caption.language);
        memoryCache.put(videoId, result);
        return result;
    }

    @Nullable
    private static MusicLyrics loadYoutubeMusicLyrics(String videoId,
                                                       @Nullable String cookies) throws Exception {
        JSONObject body = ytmContext();
        body.put("videoId", videoId);
        body.put("playlistId", "RDAMVM" + videoId);
        body.put("enablePersistentPlaylistPanel", true);
        body.put("isAudioOnly", true);
        body.put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL");
        body.put("watchEndpointMusicSupportedConfigs", new JSONObject()
                .put("watchEndpointMusicConfig", new JSONObject()
                        .put("hasPersistentPlaylistPanel", true)
                        .put("musicVideoType", "MUSIC_VIDEO_TYPE_ATV")));

        JSONObject next = postYtm("next", body, cookies);
        String browseId = findLyricsBrowseId(next);
        if (browseId == null) {
            return null;
        }

        JSONObject browseBody = ytmContext().put("browseId", browseId);
        return findMusicLyrics(postYtm("browse", browseBody, cookies));
    }

    private static JSONObject ytmContext() throws Exception {
        SimpleDateFormat date = new SimpleDateFormat("yyyyMMdd", Locale.US);
        date.setTimeZone(TimeZone.getTimeZone("UTC"));
        JSONObject client = new JSONObject()
                .put("clientName", "WEB_REMIX")
                .put("clientVersion", "1." + date.format(new Date()) + ".01.00")
                .put("hl", supportedMusicLanguage());
        return new JSONObject().put("context", new JSONObject()
                .put("client", client)
                .put("user", new JSONObject()));
    }

    private static String supportedMusicLanguage() {
        String language = Locale.getDefault().getLanguage();
        switch (language) {
            case "ar": case "cs": case "de": case "en": case "es":
            case "fr": case "hi": case "it": case "ja": case "ko":
            case "nl": case "pt": case "ru": case "tr": case "ur":
                return language;
            default:
                return "en";
        }
    }

    private static JSONObject postYtm(String endpoint, JSONObject body,
                                      @Nullable String cookies) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                YTM_API + endpoint + "?alt=json&key=" + YTM_API_KEY).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(20_000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0");
        connection.setRequestProperty("Accept", "*/*");
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setRequestProperty("Origin", "https://music.youtube.com");
        connection.setRequestProperty("Referer", "https://music.youtube.com/");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("Cookie", appendConsentCookie(cookies));
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(payload.length);
        try {
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("YouTube Music HTTP " + status);
            }
            return new JSONObject(readUtf8(connection.getInputStream(),
                    MAX_MUSIC_RESPONSE_CHARS));
        } finally {
            connection.disconnect();
        }
    }

    private static String appendConsentCookie(@Nullable String cookies) {
        if (cookies == null || cookies.isEmpty()) {
            return "SOCS=CAI";
        }
        return cookies.contains("SOCS=") ? cookies : cookies + "; SOCS=CAI";
    }

    @Nullable
    private static String findLyricsBrowseId(Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            JSONObject browse = object.optJSONObject("browseEndpoint");
            if (browse != null) {
                JSONObject supported = browse.optJSONObject(
                        "browseEndpointContextSupportedConfigs");
                JSONObject music = supported != null
                        ? supported.optJSONObject("browseEndpointContextMusicConfig") : null;
                if (music != null
                        && "MUSIC_PAGE_TYPE_TRACK_LYRICS".equals(music.optString("pageType"))) {
                    String browseId = clean(browse.optString("browseId", null));
                    if (browseId != null) {
                        return browseId;
                    }
                }
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String found = findLyricsBrowseId(object.opt(keys.next()));
                if (found != null) {
                    return found;
                }
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                String found = findLyricsBrowseId(array.opt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Nullable
    private static MusicLyrics findMusicLyrics(Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            JSONArray timed = object.optJSONArray("timedLyricsData");
            if (timed != null) {
                List<String> lines = new ArrayList<>();
                for (int i = 0; i < timed.length(); i++) {
                    JSONObject line = timed.optJSONObject(i);
                    appendLines(lines, line != null ? line.optString("lyricLine", "") : "");
                }
                String text = String.join("\n", lines).trim();
                if (!text.isEmpty()) {
                    return new MusicLyrics(text, textFromRuns(object.opt("sourceMessage")));
                }
            }

            JSONObject shelf = object.optJSONObject("musicDescriptionShelfRenderer");
            if (shelf != null) {
                String text = textFromRuns(shelf.opt("description")).trim();
                if (!text.isEmpty()) {
                    return new MusicLyrics(text, textFromRuns(shelf.opt("footer")).trim());
                }
            }

            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                MusicLyrics found = findMusicLyrics(object.opt(keys.next()));
                if (found != null) {
                    return found;
                }
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                MusicLyrics found = findMusicLyrics(array.opt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String textFromRuns(@Nullable Object value) {
        if (value instanceof String) {
            return (String) value;
        }
        if (!(value instanceof JSONObject)) {
            return "";
        }
        JSONObject object = (JSONObject) value;
        JSONArray runs = object.optJSONArray("runs");
        if (runs == null) {
            return object.optString("text", "");
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run != null) {
                result.append(run.optString("text", ""));
            }
        }
        return result.toString();
    }

    @Nullable
    private static Track findTrack(Context context, String mediaId) {
        try {
            return VibyDatabase.get(context).trackDao().getByIdSync(Long.parseLong(mediaId));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static JSONObject extractPlayerResponse(String watchPage) throws Exception {
        String[] markers = {
                "ytInitialPlayerResponse =",
                "\"ytInitialPlayerResponse\":"
        };
        for (String marker : markers) {
            int searchFrom = 0;
            while (searchFrom < watchPage.length()) {
                int markerIndex = watchPage.indexOf(marker, searchFrom);
                if (markerIndex < 0) {
                    break;
                }
                int objectStart = markerIndex + marker.length();
                while (objectStart < watchPage.length()
                        && Character.isWhitespace(watchPage.charAt(objectStart))) {
                    objectStart++;
                }
                // Do not jump to an unrelated object later in the script. The real
                // player response starts immediately after its assignment/property.
                if (objectStart < watchPage.length()
                        && watchPage.charAt(objectStart) == '{') {
                    String json = balancedObject(watchPage, objectStart);
                    if (json != null) {
                        JSONObject candidate = new JSONObject(json);
                        if (candidate.has("videoDetails")
                                || candidate.has("playabilityStatus")
                                || candidate.has("captions")) {
                            return candidate;
                        }
                    }
                }
                searchFrom = markerIndex + marker.length();
            }
        }
        throw new IllegalStateException("YouTube player metadata was not found");
    }

    @Nullable
    private static String balancedObject(String source, int start) {
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = start; i < source.length(); i++) {
            char value = source.charAt(i);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (value == '\\') {
                    escaped = true;
                } else if (value == '"') {
                    quoted = false;
                }
                continue;
            }
            if (value == '"') {
                quoted = true;
            } else if (value == '{') {
                depth++;
            } else if (value == '}' && --depth == 0) {
                return source.substring(start, i + 1);
            }
        }
        return null;
    }

    @Nullable
    private static Caption chooseCaption(JSONObject playerResponse) {
        JSONObject captions = playerResponse.optJSONObject("captions");
        JSONObject renderer = captions != null
                ? captions.optJSONObject("playerCaptionsTracklistRenderer") : null;
        JSONArray tracks = renderer != null
                ? renderer.optJSONArray("captionTracks") : null;
        if (tracks == null || tracks.length() == 0) {
            return null;
        }

        List<Caption> manual = new ArrayList<>();
        List<Caption> automatic = new ArrayList<>();
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject track = tracks.optJSONObject(i);
            if (track == null) {
                continue;
            }
            String url = track.optString("baseUrl", "");
            String language = track.optString("languageCode", "");
            if (url.isEmpty() || language.isEmpty()) {
                continue;
            }
            boolean generated = "asr".equals(track.optString("kind"));
            (generated ? automatic : manual).add(
                    new Caption(url, language, generated));
        }
        Caption selected = selectLanguage(manual);
        return selected != null ? selected : selectLanguage(automatic);
    }

    @Nullable
    private static Caption selectLanguage(List<Caption> captions) {
        if (captions.isEmpty()) {
            return null;
        }
        String preferred = Locale.getDefault().getLanguage();
        for (Caption caption : captions) {
            if (caption.language.equals(preferred)) {
                return caption;
            }
        }
        for (Caption caption : captions) {
            if (caption.language.startsWith(preferred + "-")) {
                return caption;
            }
        }
        for (Caption caption : captions) {
            if (caption.language.equals("en") || caption.language.startsWith("en-")) {
                return caption;
            }
        }
        return captions.get(0);
    }

    private static String download(String url, @Nullable String cookies,
                                   int maxChars) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(20_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 "
                        + "Chrome/130.0 Mobile Safari/537.36");
        connection.setRequestProperty("Accept-Language",
                Locale.getDefault().toLanguageTag() + ",en;q=0.8");
        connection.setRequestProperty("Accept-Encoding", "identity");
        if (cookies != null && !cookies.isEmpty()) {
            connection.setRequestProperty("Cookie", cookies);
        }
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("YouTube HTTP " + status);
            }
            return readUtf8(connection.getInputStream(), maxChars);
        } finally {
            connection.disconnect();
        }
    }

    private static String readUtf8(InputStream input, int maxChars) throws Exception {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                if (result.length() + read > maxChars) {
                    throw new IllegalStateException("YouTube response is too large");
                }
                result.append(buffer, 0, read);
            }
        }
        return result.toString();
    }

    private static String parseJson3(String payload) throws Exception {
        JSONArray events = new JSONObject(payload).optJSONArray("events");
        if (events == null) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            JSONArray segments = event != null ? event.optJSONArray("segs") : null;
            if (segments == null) {
                continue;
            }
            StringBuilder cue = new StringBuilder();
            for (int j = 0; j < segments.length(); j++) {
                JSONObject segment = segments.optJSONObject(j);
                if (segment != null) {
                    cue.append(segment.optString("utf8", ""));
                }
            }
            appendLines(lines, cue.toString());
        }
        return String.join("\n", lines).trim();
    }

    private static String absoluteYoutubeUrl(String url) {
        if (url.startsWith("//")) {
            return "https:" + url;
        }
        if (url.startsWith("/")) {
            return "https://www.youtube.com" + url;
        }
        return url;
    }

    private static void appendLines(List<String> lines, String cue) {
        for (String part : cue.split("\\r?\\n")) {
            String line = part.trim().replaceAll("\\s+", " ");
            if (!line.isEmpty()
                    && (lines.isEmpty() || !line.equals(lines.get(lines.size() - 1)))) {
                lines.add(line);
            }
        }
    }

    @Nullable
    private static String cookieHeader(Context context) {
        File file = YtCookies.file(context);
        if (!file.isFile()) {
            return null;
        }
        StringBuilder cookies = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                if (line.startsWith("#HttpOnly_")) {
                    line = line.substring("#HttpOnly_".length());
                } else if (line.startsWith("#")) {
                    continue;
                }
                String[] columns = line.split("\\t");
                if (columns.length < 7) {
                    continue;
                }
                if (cookies.length() > 0) {
                    cookies.append("; ");
                }
                cookies.append(columns[5]).append('=').append(columns[6]);
            }
        } catch (Exception ignored) {
            return null;
        }
        return cookies.length() > 0 ? cookies.toString() : null;
    }

    @Nullable
    private static String clean(@Nullable String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }
}
