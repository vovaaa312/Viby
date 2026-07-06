package com.example.viby.ui;

import android.app.Application;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import com.example.viby.R;
import com.example.viby.data.PlaylistInfo;
import com.example.viby.data.Track;
import com.example.viby.data.VibyDatabase;

import java.util.List;

/** Общее состояние UI: активный плейлист и его треки. */
public class PlayerViewModel extends AndroidViewModel {

    private static final String PREFS = "viby_prefs";
    private static final String KEY_ACTIVE_PLAYLIST = "active_playlist";
    private static final String KEY_QUEUE_PLAYLIST = "queue_playlist";

    private final SharedPreferences prefs;
    private final MutableLiveData<String> activePlaylist = new MutableLiveData<>();

    /** MediaController появляется после подключения к PlaybackService (ставит MainActivity). */
    public final MutableLiveData<androidx.media3.session.MediaController> controller =
            new MutableLiveData<>();

    public final LiveData<List<PlaylistInfo>> playlists;
    public final LiveData<List<Track>> tracks;

    public PlayerViewModel(@NonNull Application application) {
        super(application);
        prefs = application.getSharedPreferences(PREFS, Application.MODE_PRIVATE);
        String initial = prefs.getString(KEY_ACTIVE_PLAYLIST,
                application.getString(R.string.default_playlist));
        activePlaylist.setValue(initial);

        VibyDatabase db = VibyDatabase.get(application);
        playlists = db.trackDao().observePlaylists();
        tracks = Transformations.switchMap(activePlaylist,
                playlist -> db.trackDao().observePlaylist(playlist));
    }

    public LiveData<String> getActivePlaylist() {
        return activePlaylist;
    }

    public String getActivePlaylistName() {
        return activePlaylist.getValue();
    }

    public void setActivePlaylist(String name) {
        if (name == null || name.equals(activePlaylist.getValue())) {
            return;
        }
        activePlaylist.setValue(name);
        prefs.edit().putString(KEY_ACTIVE_PLAYLIST, name).apply();
    }

    /** Какой плейлист сейчас загружен в очередь плеера (переживает пересоздание). */
    public String getQueuePlaylist() {
        return prefs.getString(KEY_QUEUE_PLAYLIST, null);
    }

    public void setQueuePlaylist(String name) {
        prefs.edit().putString(KEY_QUEUE_PLAYLIST, name).apply();
    }
}
