package com.example.viby.playback;

import android.app.PendingIntent;
import android.content.Intent;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import com.example.viby.ui.MainActivity;

/**
 * Фоновое воспроизведение через Media3: MediaSession даёт системное
 * уведомление с управлением, аудиофокус и паузу при отключении наушников.
 */
public class PlaybackService extends MediaSessionService {

    private MediaSession mediaSession;

    @Override
    public void onCreate() {
        super.onCreate();
        ExoPlayer player = new ExoPlayer.Builder(this)
                .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true)
                .setHandleAudioBecomingNoisy(true)
                .build();

        // своя аудиосессия, чтобы повесить на неё системный эквалайзер
        int audioSessionId = getSystemService(android.media.AudioManager.class)
                .generateAudioSessionId();
        player.setAudioSessionId(audioSessionId);
        EqFx.init(this, audioSessionId);

        PendingIntent sessionActivity = PendingIntent.getActivity(
                this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        mediaSession = new MediaSession.Builder(this, player)
                .setSessionActivity(sessionActivity)
                .build();
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
        Player player = mediaSession.getPlayer();
        if (!player.getPlayWhenReady() || player.getMediaItemCount() == 0) {
            // смахнули приложение и ничего не играет — не держим сервис
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        EqFx.release();
        if (mediaSession != null) {
            mediaSession.getPlayer().release();
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }
}
