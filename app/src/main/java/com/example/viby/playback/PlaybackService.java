package com.example.viby.playback;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionResult;
import androidx.media3.exoplayer.source.ShuffleOrder;

import com.example.viby.ui.MainActivity;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;

/**
 * Фоновое воспроизведение через Media3: MediaSession даёт системное
 * уведомление с управлением, аудиофокус и паузу при отключении наушников.
 */
public class PlaybackService extends MediaSessionService {

    public static final String ACTION_SET_SHUFFLE_ORDER =
            "com.example.viby.action.SET_SHUFFLE_ORDER";
    public static final String ACTION_INSERT_AFTER_CURRENT =
            "com.example.viby.action.INSERT_AFTER_CURRENT";
    public static final String EXTRA_SHUFFLE_ORDER = "shuffle_order";
    public static final String EXTRA_MEDIA_ITEMS = "media_items";
    private static final SessionCommand SET_SHUFFLE_ORDER_COMMAND =
            new SessionCommand(ACTION_SET_SHUFFLE_ORDER, Bundle.EMPTY);
    private static final SessionCommand INSERT_AFTER_CURRENT_COMMAND =
            new SessionCommand(ACTION_INSERT_AFTER_CURRENT, Bundle.EMPTY);

    private MediaSession mediaSession;
    private ExoPlayer player;
    private final Handler stateHandler = new Handler(Looper.getMainLooper());
    private final Runnable queueSaver = () -> {
        if (player != null) {
            PlaybackStateStore.saveQueueAndSettings(PlaybackService.this, player);
        }
    };
    private final Runnable positionSaver = new Runnable() {
        @Override
        public void run() {
            if (player != null && player.getMediaItemCount() > 0) {
                PlaybackStateStore.savePosition(PlaybackService.this, player);
            }
            stateHandler.postDelayed(this, 5_000L);
        }
    };
    private final Player.Listener stateListener = new Player.Listener() {
        @Override
        public void onTimelineChanged(androidx.media3.common.Timeline timeline, int reason) {
            scheduleQueueSave();
        }

        @Override
        public void onMediaItemTransition(@Nullable androidx.media3.common.MediaItem mediaItem,
                                          int reason) {
            scheduleQueueSave();
        }

        @Override
        public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
            if (shuffleModeEnabled) {
                moveCurrentItemToStartOfShuffleOrder();
            }
            scheduleQueueSave();
        }

        @Override
        public void onRepeatModeChanged(int repeatMode) {
            scheduleQueueSave();
        }

        @Override
        public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition,
                                            @NonNull Player.PositionInfo newPosition,
                                            int reason) {
            if (player != null) {
                PlaybackStateStore.savePosition(PlaybackService.this, player);
            }
        }
    };
    private final MediaSession.Callback sessionCallback = new MediaSession.Callback() {
        @NonNull
        @Override
        public MediaSession.ConnectionResult onConnect(
                @NonNull MediaSession session,
                @NonNull MediaSession.ControllerInfo controllerInfo) {
            SessionCommands commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                    .buildUpon()
                    .add(SET_SHUFFLE_ORDER_COMMAND)
                    .add(INSERT_AFTER_CURRENT_COMMAND)
                    .build();
            return new MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(commands)
                    .build();
        }

        @NonNull
        @Override
        @UnstableApi
        public ListenableFuture<SessionResult> onCustomCommand(
                @NonNull MediaSession session,
                @NonNull MediaSession.ControllerInfo controllerInfo,
                @NonNull SessionCommand customCommand,
                @NonNull Bundle args) {
            if (player == null) {
                return Futures.immediateFuture(
                        new SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED));
            }

            if (ACTION_INSERT_AFTER_CURRENT.equals(customCommand.customAction)) {
                ArrayList<Bundle> itemBundles = args.getParcelableArrayList(EXTRA_MEDIA_ITEMS);
                if (itemBundles == null || itemBundles.isEmpty()) {
                    return Futures.immediateFuture(
                            new SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE));
                }
                List<MediaItem> items = new ArrayList<>(itemBundles.size());
                try {
                    for (Bundle itemBundle : itemBundles) {
                        if (itemBundle == null) {
                            throw new IllegalArgumentException("Null media item");
                        }
                        items.add(MediaItem.fromBundle(itemBundle));
                    }
                } catch (RuntimeException invalidItem) {
                    return Futures.immediateFuture(
                            new SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE));
                }
                insertAfterCurrent(items);
                return Futures.immediateFuture(
                        new SessionResult(SessionResult.RESULT_SUCCESS));
            }

            if (!ACTION_SET_SHUFFLE_ORDER.equals(customCommand.customAction)) {
                return Futures.immediateFuture(
                        new SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED));
            }
            int[] order = args.getIntArray(EXTRA_SHUFFLE_ORDER);
            if (!isValidShuffleOrder(order, player.getMediaItemCount())) {
                return Futures.immediateFuture(
                        new SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE));
            }
            player.setShuffleOrder(new ShuffleOrder.DefaultShuffleOrder(
                    order, System.nanoTime()));
            scheduleQueueSave();
            return Futures.immediateFuture(
                    new SessionResult(SessionResult.RESULT_SUCCESS));
        }
    };

    private void insertAfterCurrent(List<MediaItem> items) {
        if (player == null || items.isEmpty()) {
            return;
        }
        int oldItemCount = player.getMediaItemCount();
        int currentIndex = player.getCurrentMediaItemIndex();
        int insertionIndex = oldItemCount == 0
                ? 0 : Math.min(currentIndex + 1, oldItemCount);
        player.addMediaItems(insertionIndex, items);

        if (player.getShuffleModeEnabled() && oldItemCount > 0) {
            int[] playbackOrder = readPlaybackOrder(player);
            if (isValidShuffleOrder(playbackOrder, player.getMediaItemCount())) {
                int[] adjustedOrder = PlaybackQueueOrder.moveInsertedRangeAfterCurrent(
                        playbackOrder, currentIndex, insertionIndex, items.size());
                player.setShuffleOrder(new ShuffleOrder.DefaultShuffleOrder(
                        adjustedOrder, System.nanoTime()));
            }
        }
        scheduleQueueSave();
    }

    private static int[] readPlaybackOrder(ExoPlayer player) {
        int itemCount = player.getMediaItemCount();
        Timeline timeline = player.getCurrentTimeline();
        if (timeline.getWindowCount() != itemCount) {
            return new int[0];
        }
        int[] order = new int[itemCount];
        int size = 0;
        int index = timeline.getFirstWindowIndex(/* shuffleModeEnabled= */ true);
        while (index != androidx.media3.common.C.INDEX_UNSET && size < itemCount) {
            order[size++] = index;
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF,
                    /* shuffleModeEnabled= */ true);
        }
        return size == itemCount ? order : new int[0];
    }

    /** Makes the explicit UI reset durable even if shuffle was already off. */
    public static void rememberShuffleDisabled(Context context) {
        PlaybackStateStore.saveShuffleDisabled(context.getApplicationContext());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        player = new ExoPlayer.Builder(this)
                .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true)
                .setHandleAudioBecomingNoisy(true)
                .build();

        // своя аудиосессия, чтобы повесить на неё системный эквалайзер
        int audioSessionId = getSystemService(android.media.AudioManager.class)
                .generateAudioSessionId();
        player.setAudioSessionId(audioSessionId);
        EqFx.init(this, audioSessionId);

        PlaybackStateStore.restore(this, player);
        player.addListener(stateListener);
        stateHandler.postDelayed(positionSaver, 5_000L);

        PendingIntent sessionActivity = PendingIntent.getActivity(
                this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        mediaSession = new MediaSession.Builder(this, player)
                .setSessionActivity(sessionActivity)
                .setCallback(sessionCallback)
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
        PlaybackStateStore.saveQueueAndSettings(this, player);
        if (!player.getPlayWhenReady() || player.getMediaItemCount() == 0) {
            // смахнули приложение и ничего не играет — не держим сервис
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        stateHandler.removeCallbacks(positionSaver);
        stateHandler.removeCallbacks(queueSaver);
        if (player != null) {
            PlaybackStateStore.saveQueueAndSettings(this, player);
            player.removeListener(stateListener);
        }
        EqFx.release();
        if (mediaSession != null) {
            mediaSession.getPlayer().release();
            mediaSession.release();
            mediaSession = null;
        }
        player = null;
        super.onDestroy();
    }

    private void scheduleQueueSave() {
        stateHandler.removeCallbacks(queueSaver);
        stateHandler.postDelayed(queueSaver, 300L);
    }

    private void moveCurrentItemToStartOfShuffleOrder() {
        if (player == null || player.getMediaItemCount() < 2) {
            return;
        }

        int currentIndex = player.getCurrentMediaItemIndex();
        androidx.media3.common.Timeline timeline = player.getCurrentTimeline();
        int itemCount = player.getMediaItemCount();
        if (currentIndex == androidx.media3.common.C.INDEX_UNSET
                || timeline.getWindowCount() != itemCount) {
            return;
        }

        int[] order = new int[itemCount];
        int orderSize = 0;
        int index = timeline.getFirstWindowIndex(true);
        while (index != androidx.media3.common.C.INDEX_UNSET && orderSize < itemCount) {
            order[orderSize++] = index;
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true);
        }
        if (orderSize != itemCount) {
            return;
        }

        int currentOrderPosition = -1;
        for (int i = 0; i < order.length; i++) {
            if (order[i] == currentIndex) {
                currentOrderPosition = i;
                break;
            }
        }
        if (currentOrderPosition <= 0) {
            return;
        }

        int[] rotatedOrder = new int[itemCount];
        int tailLength = itemCount - currentOrderPosition;
        System.arraycopy(order, currentOrderPosition, rotatedOrder, 0, tailLength);
        System.arraycopy(order, 0, rotatedOrder, tailLength, currentOrderPosition);
        player.setShuffleOrder(new ShuffleOrder.DefaultShuffleOrder(
                rotatedOrder, System.nanoTime()));
    }

    private static boolean isValidShuffleOrder(@Nullable int[] order, int itemCount) {
        if (order == null || order.length != itemCount) {
            return false;
        }
        boolean[] seen = new boolean[itemCount];
        for (int index : order) {
            if (index < 0 || index >= itemCount || seen[index]) {
                return false;
            }
            seen[index] = true;
        }
        return true;
    }
}
