package com.example.viby.playback;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ShuffleOrder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Persists the Media3 queue independently from the activity and the service process. */
final class PlaybackStateStore {

    private static final String TAG = "PlaybackStateStore";
    private static final String PREFS = "viby_playback_state";
    private static final String KEY_QUEUE = "queue";
    private static final String KEY_CURRENT_ID = "current_id";
    private static final String KEY_CURRENT_INDEX = "current_index";
    private static final String KEY_POSITION = "position_ms";
    private static final String KEY_SHUFFLE = "shuffle";
    private static final String KEY_SHUFFLE_ORDER = "shuffle_order";
    private static final String KEY_REPEAT = "repeat";

    private PlaybackStateStore() {
    }

    static void saveQueueAndSettings(Context context, Player player) {
        JSONArray queue = new JSONArray();
        try {
            for (int i = 0; i < player.getMediaItemCount(); i++) {
                MediaItem item = player.getMediaItemAt(i);
                if (item.localConfiguration == null) {
                    continue;
                }
                JSONObject json = new JSONObject();
                json.put("id", item.mediaId);
                json.put("uri", item.localConfiguration.uri.toString());
                put(json, "title", item.mediaMetadata.title);
                put(json, "artist", item.mediaMetadata.artist);
                if (item.mediaMetadata.artworkUri != null) {
                    json.put("artwork", item.mediaMetadata.artworkUri.toString());
                }
                if (item.mediaMetadata.durationMs != null) {
                    json.put("duration", item.mediaMetadata.durationMs);
                }
                queue.put(json);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not serialize playback queue", e);
            return;
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_QUEUE, queue.toString())
                .putString(KEY_SHUFFLE_ORDER, serializeShuffleOrder(player).toString())
                .putBoolean(KEY_SHUFFLE, player.getShuffleModeEnabled())
                .putInt(KEY_REPEAT, player.getRepeatMode())
                .apply();
        savePosition(context, player);
    }

    static void savePosition(Context context, Player player) {
        MediaItem current = player.getCurrentMediaItem();
        SharedPreferences.Editor editor = context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_CURRENT_INDEX, player.getCurrentMediaItemIndex())
                .putLong(KEY_POSITION, Math.max(0L, player.getCurrentPosition()));
        if (current != null) {
            editor.putString(KEY_CURRENT_ID, current.mediaId);
        } else {
            editor.remove(KEY_CURRENT_ID);
        }
        editor.apply();
    }

    static void saveShuffleDisabled(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SHUFFLE, false)
                .commit();
    }

    static boolean restore(Context context, Player player) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String savedQueue = prefs.getString(KEY_QUEUE, null);
        if (savedQueue == null || savedQueue.isEmpty()) {
            return false;
        }

        try {
            JSONArray queue = new JSONArray(savedQueue);
            List<MediaItem> items = new ArrayList<>();
            int[] oldToNew = new int[queue.length()];
            java.util.Arrays.fill(oldToNew, -1);
            for (int i = 0; i < queue.length(); i++) {
                JSONObject json = queue.getJSONObject(i);
                Uri uri = Uri.parse(json.getString("uri"));
                if ("file".equals(uri.getScheme()) && !new File(uri.getPath()).isFile()) {
                    continue;
                }

                oldToNew[i] = items.size();

                MediaMetadata.Builder metadata = new MediaMetadata.Builder();
                if (json.has("title")) {
                    metadata.setTitle(json.getString("title"));
                }
                if (json.has("artist")) {
                    metadata.setArtist(json.getString("artist"));
                }
                if (json.has("artwork")) {
                    metadata.setArtworkUri(Uri.parse(json.getString("artwork")));
                }
                if (json.has("duration")) {
                    metadata.setDurationMs(json.getLong("duration"));
                }
                items.add(new MediaItem.Builder()
                        .setMediaId(json.optString("id", ""))
                        .setUri(uri)
                        .setMediaMetadata(metadata.build())
                        .build());
            }
            if (items.isEmpty()) {
                clear(context);
                return false;
            }

            String currentId = prefs.getString(KEY_CURRENT_ID, null);
            int currentIndex = Math.max(0, Math.min(
                    prefs.getInt(KEY_CURRENT_INDEX, 0), items.size() - 1));
            if (currentId != null) {
                for (int i = 0; i < items.size(); i++) {
                    if (currentId.equals(items.get(i).mediaId)) {
                        currentIndex = i;
                        break;
                    }
                }
            }
            long position = Math.max(0L, prefs.getLong(KEY_POSITION, 0L));

            player.setPlayWhenReady(false);
            player.setMediaItems(items, currentIndex, position);
            restoreShuffleOrder(prefs, oldToNew, items.size(), player);
            player.setRepeatMode(prefs.getInt(KEY_REPEAT, Player.REPEAT_MODE_OFF));
            player.setShuffleModeEnabled(prefs.getBoolean(KEY_SHUFFLE, false));
            player.prepare();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Could not restore playback queue", e);
            clear(context);
            return false;
        }
    }

    private static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    private static void put(JSONObject json, String key, CharSequence value) throws Exception {
        if (value != null) {
            json.put(key, value.toString());
        }
    }

    /** Saves Media3's actual random traversal, not just the shuffle on/off flag. */
    private static JSONArray serializeShuffleOrder(Player player) {
        JSONArray order = new JSONArray();
        Timeline timeline = player.getCurrentTimeline();
        if (timeline.getWindowCount() != player.getMediaItemCount()) {
            for (int i = 0; i < player.getMediaItemCount(); i++) {
                order.put(i);
            }
            return order;
        }
        int index = timeline.getFirstWindowIndex(/* shuffleModeEnabled= */ true);
        while (index != androidx.media3.common.C.INDEX_UNSET) {
            order.put(index);
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF,
                    /* shuffleModeEnabled= */ true);
        }
        return order;
    }

    private static void restoreShuffleOrder(SharedPreferences prefs, int[] oldToNew,
                                            int itemCount, Player player) {
        if (!(player instanceof ExoPlayer)) {
            return;
        }
        try {
            JSONArray saved = new JSONArray(prefs.getString(KEY_SHUFFLE_ORDER, "[]"));
            int[] order = new int[itemCount];
            boolean[] included = new boolean[itemCount];
            int next = 0;
            for (int i = 0; i < saved.length(); i++) {
                int oldIndex = saved.getInt(i);
                if (oldIndex >= 0 && oldIndex < oldToNew.length) {
                    int newIndex = oldToNew[oldIndex];
                    if (newIndex >= 0 && !included[newIndex]) {
                        order[next++] = newIndex;
                        included[newIndex] = true;
                    }
                }
            }
            for (int i = 0; i < itemCount; i++) {
                if (!included[i]) {
                    order[next++] = i;
                }
            }
            ((ExoPlayer) player).setShuffleOrder(
                    new ShuffleOrder.DefaultShuffleOrder(order, System.nanoTime()));
        } catch (Exception e) {
            Log.w(TAG, "Could not restore shuffle order", e);
        }
    }
}
