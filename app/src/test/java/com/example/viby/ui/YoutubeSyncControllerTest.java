package com.example.viby.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.viby.data.PlaylistSource;
import com.example.viby.data.Track;

import org.junit.Test;

import java.util.Collections;

public class YoutubeSyncControllerTest {

    @Test
    public void unsavedRadioShowsOnlySaveAction() {
        PlaylistSource source = source(
                "https://www.youtube.com/watch?v=seed&list=RDseed");
        Track copied = youtubeTrack(null);

        YoutubeSyncController.ActionState state =
                YoutubeSyncController.actionState(
                        source, Collections.singletonList(copied));

        assertTrue(state.showSaveRadio);
        assertFalse(state.showSyncMissing);
    }

    @Test
    public void originalPlaylistShowsSyncAfterLocalYoutubeCopy() {
        PlaylistSource source = source(
                "https://www.youtube.com/playlist?list=PLowned");

        YoutubeSyncController.ActionState importedState =
                YoutubeSyncController.actionState(source,
                        Collections.singletonList(youtubeTrack(0)));
        YoutubeSyncController.ActionState copiedState =
                YoutubeSyncController.actionState(source,
                        Collections.singletonList(youtubeTrack(null)));

        assertFalse(importedState.showSyncMissing);
        assertTrue(copiedState.showSyncMissing);
    }

    @Test
    public void deviceFileNeverEnablesYoutubeSyncAction() {
        PlaylistSource source = source(
                "https://www.youtube.com/playlist?list=PLowned");
        Track deviceTrack = youtubeTrack(null);
        deviceTrack.videoId = null;

        YoutubeSyncController.ActionState state =
                YoutubeSyncController.actionState(
                        source, Collections.singletonList(deviceTrack));

        assertFalse(state.showSaveRadio);
        assertFalse(state.showSyncMissing);
    }

    @Test
    public void enabledPlaylistShowsUnlinkedRemoteCandidate() {
        PlaylistSource source = source(
                "https://www.youtube.com/playlist?list=PLowned");
        source.youtubeSyncEnabled = true;
        Track track = youtubeTrack(4);

        YoutubeSyncController.ActionState state =
                YoutubeSyncController.actionState(
                        source, Collections.singletonList(track));

        assertTrue(state.showSyncMissing);
    }

    private static PlaylistSource source(String url) {
        PlaylistSource source = new PlaylistSource();
        source.playlistName = "Playlist";
        source.sourceUrl = url;
        return source;
    }

    private static Track youtubeTrack(Integer youtubePosition) {
        Track track = new Track();
        track.videoId = "video";
        track.youtubePosition = youtubePosition;
        return track;
    }
}
