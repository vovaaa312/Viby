package com.example.viby.ui;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.example.viby.R;
import com.example.viby.VibyApp;
import com.example.viby.data.Track;
import com.example.viby.data.VibyDatabase;
import com.example.viby.playback.PlaybackService;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.util.concurrent.ListenableFuture;
import com.yausername.youtubedl_android.YoutubeDL;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private PlayerViewModel viewModel;
    private DrawerLayout drawerLayout;
    private MaterialToolbar toolbar;
    private ViewPager2 pager;

    private ListenableFuture<MediaController> controllerFuture;
    /** Плейлист, который сейчас загружен в очередь плеера. */
    private String loadedPlaylist;
    /** Пользователь только что выбрал плейлист в drawer — при загрузке очереди начать играть. */
    private boolean playOnNextSync;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewModel = new ViewModelProvider(this).get(PlayerViewModel.class);

        drawerLayout = findViewById(R.id.drawerLayout);
        toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));

        pager = findViewById(R.id.pager);
        pager.setAdapter(new FragmentStateAdapter(this) {
            @NonNull
            @Override
            public Fragment createFragment(int position) {
                return position == 0 ? new PlayerFragment() : new QueueFragment();
            }

            @Override
            public int getItemCount() {
                return 2;
            }
        });

        setUpDrawer();

        viewModel.getActivePlaylist().observe(this, name -> toolbar.setTitle(name));
        viewModel.tracks.observe(this, tracks -> syncQueue());

        requestNotificationPermissionIfNeeded();
    }

    private void setUpDrawer() {
        findViewById(R.id.drawerDownload).setOnClickListener(v -> {
            drawerLayout.closeDrawers();
            startActivity(new Intent(this, DownloadActivity.class));
        });

        RecyclerView playlistsList = findViewById(R.id.playlistsList);
        PlaylistsAdapter adapter = new PlaylistsAdapter(name -> {
            drawerLayout.closeDrawers();
            if (!name.equals(viewModel.getActivePlaylistName())) {
                playOnNextSync = true;
                viewModel.setActivePlaylist(name);
            }
        });
        playlistsList.setAdapter(adapter);
        viewModel.playlists.observe(this, adapter::submit);

        findViewById(R.id.drawerUpdateYtdlp).setOnClickListener(v -> {
            drawerLayout.closeDrawers();
            Toast.makeText(this, R.string.ytdlp_updating, Toast.LENGTH_SHORT).show();
            ((VibyApp) getApplication()).updateYtDlp((status, error) -> {
                if (error != null) {
                    Toast.makeText(this,
                            getString(R.string.ytdlp_update_error, error.getMessage()),
                            Toast.LENGTH_LONG).show();
                } else if (status == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) {
                    Toast.makeText(this, R.string.ytdlp_up_to_date, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, R.string.ytdlp_updated, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    // ------------------------------------------------------- media controller

    @Override
    protected void onStart() {
        super.onStart();
        SessionToken token = new SessionToken(this,
                new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                MediaController controller = controllerFuture.get();
                if (controller.getMediaItemCount() > 0) {
                    // сервис уже играл (например, после поворота) — очередь не трогаем
                    loadedPlaylist = viewModel.getActivePlaylistName();
                }
                viewModel.controller.setValue(controller);
                syncQueue();
            } catch (Exception e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    protected void onStop() {
        super.onStop();
        viewModel.controller.setValue(null);
        loadedPlaylist = null;
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
            controllerFuture = null;
        }
    }

    /** Приводит очередь плеера в соответствие с активным плейлистом. */
    private void syncQueue() {
        MediaController controller = viewModel.controller.getValue();
        List<Track> tracks = viewModel.tracks.getValue();
        String active = viewModel.getActivePlaylistName();
        if (controller == null || tracks == null || active == null) {
            return;
        }

        if (active.equals(loadedPlaylist)) {
            // тот же плейлист: докачались новые треки — дописываем в хвост очереди
            int inQueue = controller.getMediaItemCount();
            if (tracks.size() > inQueue) {
                List<MediaItem> tail = new ArrayList<>();
                for (int i = inQueue; i < tracks.size(); i++) {
                    tail.add(toMediaItem(tracks.get(i)));
                }
                controller.addMediaItems(tail);
                if (inQueue == 0) {
                    controller.prepare();
                }
            }
            return;
        }

        loadedPlaylist = active;
        List<MediaItem> items = new ArrayList<>();
        for (Track track : tracks) {
            items.add(toMediaItem(track));
        }
        controller.setMediaItems(items);
        controller.prepare();
        if (playOnNextSync && !items.isEmpty()) {
            controller.play();
        }
        playOnNextSync = false;
    }

    static MediaItem toMediaItem(Track track) {
        MediaMetadata metadata = new MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.uploader)
                .setArtworkUri(track.thumbnailUrl != null
                        ? Uri.parse(track.thumbnailUrl) : null)
                .build();
        return new MediaItem.Builder()
                .setUri(Uri.fromFile(new File(track.filePath)))
                .setMediaId(String.valueOf(track.id))
                .setMediaMetadata(metadata)
                .build();
    }

    // ------------------------------------------------------------- for fragments

    /** Переключиться на страницу плеера (тап по мини-плееру). */
    void showPlayerPage() {
        pager.setCurrentItem(0, true);
    }

    /** Играть трек из очереди (тап в списке). */
    void playTrack(Track track, int position) {
        MediaController controller = viewModel.controller.getValue();
        if (controller == null) {
            return;
        }
        // позиция в списке совпадает с позицией в очереди (очередь строится из того же списка)
        if (position < controller.getMediaItemCount()) {
            controller.seekTo(position, 0);
            controller.play();
        }
    }

    /** Удалить трек: файл + запись в БД + из очереди плеера. */
    void confirmDeleteTrack(Track track) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_track_title)
                .setMessage(getString(R.string.delete_track_message, track.title))
                .setPositiveButton(R.string.btn_delete, (d, w) -> deleteTrack(track))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteTrack(Track track) {
        MediaController controller = viewModel.controller.getValue();
        if (controller != null) {
            String mediaId = String.valueOf(track.id);
            for (int i = 0; i < controller.getMediaItemCount(); i++) {
                if (mediaId.equals(controller.getMediaItemAt(i).mediaId)) {
                    controller.removeMediaItem(i);
                    break;
                }
            }
        }
        VibyDatabase.dbExecutor.execute(() -> {
            //noinspection ResultOfMethodCallIgnored
            new File(track.filePath).delete();
            VibyDatabase.get(this).trackDao().delete(track);
        });
        Toast.makeText(this, R.string.track_deleted, Toast.LENGTH_SHORT).show();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    granted -> { /* без разрешения просто не будет уведомлений */ })
                    .launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }
}
