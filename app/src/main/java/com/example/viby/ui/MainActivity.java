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
    /** Плейлист, который сейчас загружен в очередь плеера (может отличаться от просматриваемого). */
    private String loadedPlaylist;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewModel = new ViewModelProvider(this).get(PlayerViewModel.class);

        drawerLayout = findViewById(R.id.drawerLayout);
        toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));
        toolbar.inflateMenu(R.menu.menu_main);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_sort) {
                showSortDialog();
                return true;
            }
            return false;
        });

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

        findViewById(R.id.drawerEffects).setOnClickListener(v -> {
            drawerLayout.closeDrawers();
            startActivity(new Intent(this, EqualizerActivity.class));
        });

        findViewById(R.id.drawerAccount).setOnClickListener(v -> {
            drawerLayout.closeDrawers();
            if (!com.example.viby.util.YtCookies.isLoggedIn(this)) {
                startActivity(new Intent(this, YoutubeLoginActivity.class));
                return;
            }
            String[] options = {
                    getString(R.string.account_my_playlists),
                    getString(R.string.account_logout),
            };
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.menu_account)
                    .setItems(options, (dialog, which) -> {
                        if (which == 0) {
                            startActivity(new Intent(this, YoutubePlaylistsActivity.class));
                        } else {
                            com.example.viby.util.YtCookies.clear(this);
                            Toast.makeText(this, R.string.logged_out,
                                    Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });

        RecyclerView playlistsList = findViewById(R.id.playlistsList);
        PlaylistsAdapter adapter = new PlaylistsAdapter(new PlaylistsAdapter.Listener() {
            @Override
            public void onPlaylistClick(String name) {
                drawerLayout.closeDrawers();
                // просто открываем плейлист для просмотра — играющая очередь не трогается
                viewModel.setActivePlaylist(name);
            }

            @Override
            public void onPlaylistDelete(com.example.viby.data.PlaylistInfo playlist) {
                confirmDeletePlaylist(playlist);
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
                    loadedPlaylist = viewModel.getQueuePlaylist();
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

    /**
     * Дозаполняет очередь плеера. Открытие другого плейлиста очередь НЕ заменяет —
     * замена происходит только при тапе по треку (playTrack).
     */
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

        // очередь пуста (холодный старт) — тихо загружаем просматриваемый плейлист без плей
        if (controller.getMediaItemCount() == 0 && !tracks.isEmpty()) {
            loadQueue(controller, tracks, active, 0, false);
        }
    }

    /** Заменить очередь плеера треками плейлиста. */
    private void loadQueue(MediaController controller, List<Track> tracks,
                           String playlist, int startIndex, boolean play) {
        List<MediaItem> items = new ArrayList<>();
        for (Track track : tracks) {
            items.add(toMediaItem(track));
        }
        loadedPlaylist = playlist;
        viewModel.setQueuePlaylist(playlist);
        controller.setMediaItems(items, startIndex, 0);
        controller.prepare();
        if (play) {
            controller.play();
        }
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

    // -------------------------------------------------------------- sorting

    /** Диалог «Сортировать по…» в духе AIMP: радио-список + «В обратном порядке». */
    private void showSortDialog() {
        String[] labels = {
                getString(R.string.sort_by_title),
                getString(R.string.sort_by_artist),
                getString(R.string.sort_by_filename),
                getString(R.string.sort_by_duration),
                getString(R.string.sort_by_date_added),
                getString(R.string.sort_shuffle),
        };
        android.widget.RadioGroup group = new android.widget.RadioGroup(this);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        group.setPadding(pad, pad / 2, pad, 0);
        for (int i = 0; i < labels.length; i++) {
            android.widget.RadioButton button = new android.widget.RadioButton(this);
            button.setId(i + 1);
            button.setText(labels[i]);
            button.setMinHeight((int) (44 * getResources().getDisplayMetrics().density));
            group.addView(button);
        }
        android.widget.CheckBox reverse = new android.widget.CheckBox(this);
        reverse.setText(R.string.sort_reverse);

        android.widget.LinearLayout content = new android.widget.LinearLayout(this);
        content.setOrientation(android.widget.LinearLayout.VERTICAL);
        content.addView(group);
        android.widget.LinearLayout.LayoutParams reverseParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        reverseParams.setMargins(pad, pad / 2, pad, 0);
        content.addView(reverse, reverseParams);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(content);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sort_title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    int checked = group.getCheckedRadioButtonId();
                    if (checked > 0) {
                        applySort(checked - 1, reverse.isChecked());
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Пересортировать активный плейлист в БД и обновить очередь без остановки музыки. */
    private void applySort(int option, boolean reversed) {
        String playlist = viewModel.getActivePlaylistName();
        if (playlist == null) {
            return;
        }
        VibyDatabase.dbExecutor.execute(() -> {
            var dao = VibyDatabase.get(this).trackDao();
            List<Track> tracks = dao.getPlaylistSync(playlist);
            switch (option) {
                case 0:
                    tracks.sort(java.util.Comparator.comparing(
                            t -> t.title.toLowerCase(java.util.Locale.getDefault())));
                    break;
                case 1:
                    tracks.sort(java.util.Comparator.comparing(
                            t -> t.uploader != null
                                    ? t.uploader.toLowerCase(java.util.Locale.getDefault()) : ""));
                    break;
                case 2:
                    tracks.sort(java.util.Comparator.comparing(
                            t -> new File(t.filePath).getName()
                                    .toLowerCase(java.util.Locale.getDefault())));
                    break;
                case 3:
                    tracks.sort(java.util.Comparator.comparingLong(t -> t.durationMs));
                    break;
                case 4:
                    tracks.sort(java.util.Comparator.comparingLong(t -> t.createdAt));
                    break;
                default:
                    java.util.Collections.shuffle(tracks);
                    break;
            }
            if (reversed && option != 5) {
                java.util.Collections.reverse(tracks);
            }
            for (int i = 0; i < tracks.size(); i++) {
                tracks.get(i).position = i;
            }
            dao.updateAll(tracks);
            List<Track> sorted = new ArrayList<>(tracks);
            runOnUiThread(() -> resyncQueueAfterSort(playlist, sorted));
        });
    }

    /** После сортировки перestraивает очередь, сохраняя текущий трек и позицию. */
    private void resyncQueueAfterSort(String playlist, List<Track> sorted) {
        MediaController controller = viewModel.controller.getValue();
        if (controller == null || !playlist.equals(loadedPlaylist)) {
            return; // очередь держит другой плейлист — трогать нечего
        }
        String currentId = controller.getCurrentMediaItem() != null
                ? controller.getCurrentMediaItem().mediaId : null;
        long position = controller.getCurrentPosition();
        boolean playWhenReady = controller.getPlayWhenReady();

        List<MediaItem> items = new ArrayList<>();
        int startIndex = 0;
        for (int i = 0; i < sorted.size(); i++) {
            items.add(toMediaItem(sorted.get(i)));
            if (currentId != null && currentId.equals(String.valueOf(sorted.get(i).id))) {
                startIndex = i;
            }
        }
        controller.setMediaItems(items, startIndex, currentId != null ? position : 0);
        controller.prepare();
        controller.setPlayWhenReady(playWhenReady);
    }

    /** Играть трек из списка. Если открыт другой плейлист — он заменяет очередь. */
    void playTrack(Track track, int position) {
        MediaController controller = viewModel.controller.getValue();
        List<Track> tracks = viewModel.tracks.getValue();
        String active = viewModel.getActivePlaylistName();
        if (controller == null || tracks == null || active == null) {
            return;
        }
        if (active.equals(loadedPlaylist)) {
            if (position < controller.getMediaItemCount()) {
                controller.seekTo(position, 0);
                controller.play();
            }
        } else {
            loadQueue(controller, tracks, active,
                    Math.min(position, tracks.size() - 1), true);
        }
    }

    /** Добавить треки в конец очереди воспроизведения (без изменения БД). */
    void addToQueue(List<Track> tracks) {
        MediaController controller = viewModel.controller.getValue();
        if (controller == null || tracks.isEmpty()) {
            return;
        }
        List<MediaItem> items = new ArrayList<>();
        for (Track track : tracks) {
            items.add(toMediaItem(track));
        }
        controller.addMediaItems(items);
        Toast.makeText(this, R.string.added_to_queue, Toast.LENGTH_SHORT).show();
    }

    /** Вставить треки сразу после проигрываемого. */
    void insertAfterCurrent(List<Track> tracks) {
        MediaController controller = viewModel.controller.getValue();
        if (controller == null || tracks.isEmpty()) {
            return;
        }
        List<MediaItem> items = new ArrayList<>();
        for (Track track : tracks) {
            items.add(toMediaItem(track));
        }
        int index = controller.getMediaItemCount() == 0
                ? 0 : controller.getCurrentMediaItemIndex() + 1;
        controller.addMediaItems(index, items);
        Toast.makeText(this, R.string.added_to_queue, Toast.LENGTH_SHORT).show();
    }

    /** Переместить треки в другой плейлист: файл переезжает в папку плейлиста. */
    void moveTracksToPlaylist(List<Track> tracks, String targetPlaylist) {
        String target = com.example.viby.util.StorageHelper.sanitize(targetPlaylist);
        removeFromQueue(tracks);
        VibyDatabase.dbExecutor.execute(() -> {
            var dao = VibyDatabase.get(this).trackDao();
            int moved = 0;
            for (Track track : tracks) {
                if (target.equals(track.playlistName)) {
                    continue;
                }
                File src = new File(track.filePath);
                File destDir = com.example.viby.util.StorageHelper.playlistDir(this, target);
                File dest = new File(destDir, src.getName());
                if (dest.exists()) {
                    String name = src.getName();
                    int dot = name.lastIndexOf('.');
                    String base = dot > 0 ? name.substring(0, dot) : name;
                    String ext = dot > 0 ? name.substring(dot) : "";
                    dest = new File(destDir, base + " [" + track.id + "]" + ext);
                }
                if (src.exists() && !src.renameTo(dest)) {
                    continue; // файл не переехал — запись не трогаем
                }
                track.playlistName = target;
                if (src.exists() || dest.exists()) {
                    track.filePath = dest.getAbsolutePath();
                }
                track.position = dao.nextPosition(target);
                dao.update(track);
                moved++;
            }
            int total = moved;
            runOnUiThread(() -> Toast.makeText(this,
                    getString(R.string.tracks_moved, total), Toast.LENGTH_SHORT).show());
        });
    }

    /** Убрать записи из плейлиста (файлы остаются на диске). */
    void removeTracksFromPlaylist(List<Track> tracks) {
        removeFromQueue(tracks);
        VibyDatabase.dbExecutor.execute(() -> {
            var dao = VibyDatabase.get(this).trackDao();
            for (Track track : tracks) {
                dao.delete(track);
            }
            runOnUiThread(() -> Toast.makeText(this,
                    getString(R.string.tracks_deleted, tracks.size()),
                    Toast.LENGTH_SHORT).show());
        });
    }

    /** Удалить треки с устройства: файлы + записи + из очереди. */
    void deleteTracksFromDevice(List<Track> tracks) {
        removeFromQueue(tracks);
        VibyDatabase.dbExecutor.execute(() -> {
            var dao = VibyDatabase.get(this).trackDao();
            for (Track track : tracks) {
                //noinspection ResultOfMethodCallIgnored
                new File(track.filePath).delete();
                dao.delete(track);
            }
            runOnUiThread(() -> Toast.makeText(this,
                    getString(R.string.tracks_deleted, tracks.size()),
                    Toast.LENGTH_SHORT).show());
        });
    }

    private void removeFromQueue(List<Track> tracks) {
        MediaController controller = viewModel.controller.getValue();
        if (controller == null) {
            return;
        }
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (Track track : tracks) {
            ids.add(String.valueOf(track.id));
        }
        for (int i = controller.getMediaItemCount() - 1; i >= 0; i--) {
            if (ids.contains(controller.getMediaItemAt(i).mediaId)) {
                controller.removeMediaItem(i);
            }
        }
    }

    /** Удалить целый плейлист: все треки, файлы и папку. */
    private void confirmDeletePlaylist(com.example.viby.data.PlaylistInfo playlist) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_playlist_title)
                .setMessage(getString(R.string.delete_playlist_message,
                        playlist.playlistName, playlist.trackCount))
                .setPositiveButton(R.string.btn_delete,
                        (d, w) -> deletePlaylist(playlist.playlistName))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deletePlaylist(String name) {
        MediaController controller = viewModel.controller.getValue();
        if (name.equals(loadedPlaylist) && controller != null) {
            controller.stop();
            controller.clearMediaItems();
            loadedPlaylist = null;
        }
        String defaultPlaylist = getString(R.string.default_playlist);
        if (name.equals(viewModel.getActivePlaylistName())) {
            viewModel.setActivePlaylist(defaultPlaylist);
        }
        VibyDatabase.dbExecutor.execute(() -> {
            var dao = VibyDatabase.get(this).trackDao();
            List<Track> tracks = dao.getPlaylistSync(name);
            for (Track track : tracks) {
                //noinspection ResultOfMethodCallIgnored
                new File(track.filePath).delete();
                dao.delete(track);
            }
            File dir = com.example.viby.util.StorageHelper.playlistDir(this, name);
            File[] leftovers = dir.listFiles();
            if (leftovers != null) {
                for (File file : leftovers) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
            //noinspection ResultOfMethodCallIgnored
            dir.delete();
            VibyDatabase.get(this).playlistSourceDao().delete(name);
            runOnUiThread(() -> Toast.makeText(this,
                    R.string.playlist_deleted, Toast.LENGTH_SHORT).show());
        });
    }

    /** Удалить один трек: файл + запись в БД + из очереди плеера. */
    void confirmDeleteTrack(Track track) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_track_title)
                .setMessage(getString(R.string.delete_track_message, track.title))
                .setPositiveButton(R.string.btn_delete,
                        (d, w) -> deleteTracksFromDevice(java.util.Collections.singletonList(track)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
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
