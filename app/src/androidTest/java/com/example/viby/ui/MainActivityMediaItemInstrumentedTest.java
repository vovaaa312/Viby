package com.example.viby.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import androidx.media3.common.MediaItem;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.example.viby.data.Track;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class MainActivityMediaItemInstrumentedTest {

    @Test
    public void localMediaItemKeepsPlaybackAndYoutubeMetadata() {
        Track track = new Track();
        track.id = 42;
        track.title = "Title";
        track.uploader = "Artist";
        track.durationMs = 123_456;
        track.filePath = "/tmp/audio.m4a";
        track.thumbnailUrl = "https://example.test/cover.jpg";
        track.videoId = "abcdefghijk";
        track.sourceUrl = "https://youtube.com/watch?v=abcdefghijk";

        MediaItem item = PlaybackQueueController.toMediaItem(track);

        assertEquals("42", item.mediaId);
        assertNotNull(item.localConfiguration);
        assertEquals("file", item.localConfiguration.uri.getScheme());
        assertEquals("Title", item.mediaMetadata.title.toString());
        assertEquals("Artist", item.mediaMetadata.artist.toString());
        assertEquals(Long.valueOf(123_456), item.mediaMetadata.durationMs);
        assertEquals("abcdefghijk", item.mediaMetadata.extras.getString(
                PlaybackQueueController.METADATA_VIDEO_ID));
        assertEquals(track.sourceUrl, item.mediaMetadata.extras.getString(
                PlaybackQueueController.METADATA_SOURCE_URL));
    }

    @Test
    public void localOnlyMediaItemDoesNotInventYoutubeMetadata() {
        Track track = new Track();
        track.id = 7;
        track.title = "Local";
        track.filePath = "/tmp/local.mp3";

        MediaItem item = PlaybackQueueController.toMediaItem(track);

        assertNotNull(item.mediaMetadata.extras);
        assertFalse(item.mediaMetadata.extras.containsKey(
                PlaybackQueueController.METADATA_VIDEO_ID));
        assertFalse(item.mediaMetadata.extras.containsKey(
                PlaybackQueueController.METADATA_SOURCE_URL));
    }
}
