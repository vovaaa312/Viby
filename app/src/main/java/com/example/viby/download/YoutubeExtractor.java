package com.example.viby.download;

import android.content.Context;

import com.example.viby.util.YtCookies;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;
import com.yausername.youtubedl_android.mapper.VideoInfo;

/** Builds and executes yt-dlp metadata requests. Download transfer stays in AudioDownloader. */
final class YoutubeExtractor {

    static final int RADIO_TRACK_LIMIT = 50;

    interface Authentication {
        void apply(YoutubeDLRequest request);
    }

    interface Backend {
        VideoInfo getInfo(YoutubeDLRequest request) throws Exception;

        YoutubeDLResponse execute(YoutubeDLRequest request, String processId)
                throws Exception;
    }

    private final Authentication authentication;
    private final Backend backend;

    static YoutubeExtractor create(Context context) {
        Context app = context.getApplicationContext();
        return new YoutubeExtractor(request -> {
            if (YtCookies.isLoggedIn(app)) {
                request.addOption("--cookies", YtCookies.file(app).getAbsolutePath());
            }
        }, new Backend() {
            @Override
            public VideoInfo getInfo(YoutubeDLRequest request) throws Exception {
                return YoutubeDL.getInstance().getInfo(request);
            }

            @Override
            public YoutubeDLResponse execute(YoutubeDLRequest request,
                                             String processId) throws Exception {
                return YoutubeDL.getInstance().execute(request, processId, null);
            }
        });
    }

    YoutubeExtractor(Authentication authentication, Backend backend) {
        this.authentication = authentication;
        this.backend = backend;
    }

    YoutubeDLRequest videoInfoRequest(String url) {
        YoutubeDLRequest request = new YoutubeDLRequest(url);
        applyAuthentication(request);
        return request;
    }

    YoutubeDLRequest playlistInfoRequest(String url, boolean radioMix) {
        YoutubeDLRequest request = new YoutubeDLRequest(url);
        request.addOption("--flat-playlist");
        request.addOption("--dump-single-json");
        request.addOption("--no-warnings");
        request.addOption("--extractor-args", "youtubetab:skip=authcheck");
        if (radioMix) {
            request.addOption("--playlist-end", String.valueOf(RADIO_TRACK_LIMIT));
        }
        applyAuthentication(request);
        return request;
    }

    void applyAuthentication(YoutubeDLRequest request) {
        authentication.apply(request);
    }

    VideoInfo getVideoInfo(YoutubeDLRequest request) throws Exception {
        return backend.getInfo(request);
    }

    YoutubeDLResponse getPlaylistInfo(YoutubeDLRequest request, String processId)
            throws Exception {
        return backend.execute(request, processId);
    }
}
