package com.example.viby.ui;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.example.viby.R;
import com.example.viby.VibyApp;
import com.example.viby.data.Track;
import com.example.viby.data.VibyDatabase;
import com.example.viby.download.DownloadService;
import com.example.viby.playback.PlaybackService;
import com.example.viby.update.AppUpdateManager;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.util.concurrent.ListenableFuture;
import com.yausername.youtubedl_android.YoutubeDL;

import java.io.File;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private PlayerViewModel viewModel;
    private DrawerLayout drawerLayout;
    private MaterialToolbar toolbar;
    private ViewPager2 pager;
    private AppUpdateManager appUpdateManager;
    private YoutubeSyncController youtubeSyncController;
    private TrackStreamResolver trackStreamResolver;
    private PlaybackQueueController playbackQueueController;
    private LibraryFileController libraryFileController;

    private ListenableFuture<MediaController> controllerFuture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewModel = new ViewModelProvider(this).get(PlayerViewModel.class);
        youtubeSyncController = new YoutubeSyncController(this);
        trackStreamResolver = new TrackStreamResolver(this);
        playbackQueueController = new PlaybackQueueController(this, viewModel);
        libraryFileController = new LibraryFileController(this);

        drawerLayout = findViewById(R.id.drawerLayout);
        toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));
        toolbar.inflateMenu(R.menu.menu_main);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_save_radio) {
                youtubeSyncController.showPlaylistDialog(
                        viewModel.getActivePlaylistName());
                return true;
            }
            if (item.getItemId() == R.id.action_sync_missing_youtube) {
                youtubeSyncController.syncMissing(
                        viewModel.getActivePlaylistName());
                return true;
            }
            if (item.getItemId() == R.id.action_queue) {
                new PlaybackQueueDialogFragment().show(
                        getSupportFragmentManager(), "playback_queue");
                return true;
            }
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

        appUpdateManager = new AppUpdateManager(this);
        appUpdateManager.start();

        viewModel.getActivePlaylist().observe(this, name -> toolbar.setTitle(name));
        viewModel.playlistSource.observe(this, source -> updateYoutubeActions());
        viewModel.tracks.observe(this, tracks -> {
            playbackQueueController.syncWithActivePlaylist();
            updateYoutubeActions();
        });

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
                            com.example.viby.util.YtCookies.clear(this, () ->
                                    Toast.makeText(this, R.string.logged_out,
                                            Toast.LENGTH_SHORT).show());
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });

        findViewById(R.id.drawerPlaylistSync).setOnClickListener(v -> {
            drawerLayout.closeDrawers();
            youtubeSyncController.showPlaylistDialog(
                    viewModel.getActivePlaylistName());
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

        findViewById(R.id.drawerCheckUpdates).setOnClickListener(v -> {
            drawerLayout.closeDrawers();
            appUpdateManager.checkManually();
        });

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

    private void updateYoutubeActions() {
        youtubeSyncController.updateActions(toolbar.getMenu(),
                viewModel.playlistSource.getValue(), viewModel.tracks.getValue());
    }

    /** Creates a finite local YouTube Mix playlist seeded by the selected track. */
    void startTrackRadio(Track track) {
        String videoId = track.videoId != null && !track.videoId.isEmpty()
                ? track.videoId
                : com.example.viby.util.YoutubeUrlParser.videoId(track.sourceUrl);
        if (videoId == null) {
            Toast.makeText(this, R.string.track_radio_unavailable,
                    Toast.LENGTH_LONG).show();
            return;
        }
        String title = track.title != null && !track.title.trim().isEmpty()
                ? track.title.trim() : videoId;
        Toast.makeText(this, getString(R.string.track_radio_preparing, title),
                Toast.LENGTH_SHORT).show();
        String radioUrl = com.example.viby.util.YoutubeUrlParser.radioMixUrl(videoId);
        String requestedName = com.example.viby.util.StorageHelper.sanitize(
                getString(R.string.track_radio_playlist_name, title));
        VibyDatabase.dbExecutor.execute(() -> {
            String playlist = availablePlaylistName(requestedName);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                viewModel.setActivePlaylist(playlist);
                DownloadService.enqueue(this, radioUrl, playlist, true);
                Toast.makeText(this, R.string.track_radio_started,
                        Toast.LENGTH_SHORT).show();
            });
        });
    }

    private String availablePlaylistName(String requestedName) {
        String base = requestedName.length() <= 100
                ? requestedName : requestedName.substring(0, 100).trim();
        String candidate = base;
        int suffix = 2;
        while (!VibyDatabase.get(this).trackDao().getPlaylistSync(candidate).isEmpty()
                || VibyDatabase.get(this).playlistSourceDao().getSync(candidate) != null) {
            candidate = base + " (" + suffix++ + ")";
        }
        return candidate;
    }

    /** Entry point used by the playlist screen for a single YouTube URL. */
    void addTrackByUrl(String url, String playlist) {
        youtubeSyncController.addTrackByUrl(url, playlist);
    }

    // ------------------------------------------------------- media controller

    @Override
    protected void onResume() {
        super.onResume();
        if (appUpdateManager != null) {
            appUpdateManager.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (appUpdateManager != null) {
            appUpdateManager.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onStart() {
        super.onStart();
        SessionToken token = new SessionToken(this,
                new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                MediaController controller = controllerFuture.get();
                playbackQueueController.connect(controller);
            } catch (Exception e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    protected void onStop() {
        super.onStop();
        playbackQueueController.disconnect();
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
            controllerFuture = null;
        }
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
                getString(R.string.sort_as_on_youtube),
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
                    com.example.viby.data.YoutubePlaylistOrder.sort(tracks);
                    break;
                case 1:
                    tracks.sort(java.util.Comparator.comparing(
                            t -> t.title.toLowerCase(java.util.Locale.getDefault())));
                    break;
                case 2:
                    tracks.sort(java.util.Comparator.comparing(
                            t -> t.uploader != null
                                    ? t.uploader.toLowerCase(java.util.Locale.getDefault()) : ""));
                    break;
                case 3:
                    tracks.sort(java.util.Comparator.comparing(
                            t -> new File(t.filePath).getName()
                                    .toLowerCase(java.util.Locale.getDefault())));
                    break;
                case 4:
                    tracks.sort(java.util.Comparator.comparingLong(t -> t.durationMs));
                    break;
                case 5:
                    tracks.sort(java.util.Comparator.comparingLong(t -> t.createdAt));
                    break;
                default:
                    java.util.Collections.shuffle(tracks);
                    break;
            }
            if (reversed && option != 6) {
                java.util.Collections.reverse(tracks);
            }
            for (int i = 0; i < tracks.size(); i++) {
                tracks.get(i).position = i;
            }
            dao.updateAll(tracks);
        });
    }

    /** Играть трек из списка. Если открыт другой плейлист — он заменяет очередь. */
    void playTrack(Track track, int position) {
        List<Track> tracks = viewModel.tracks.getValue();
        String active = viewModel.getActivePlaylistName();
        if (tracks == null || active == null) {
            return;
        }
        if (!PlaybackQueueController.isPlayableLocal(track)) {
            playPendingTrack(track, active);
            return;
        }
        playbackQueueController.playLocalTrack(track, tracks, active);
    }

    private void playPendingTrack(Track track, String playlist) {
        if (!TrackStreamResolver.canResolve(track)) {
            Toast.makeText(this, R.string.smart_stream_unavailable,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        DownloadService.prioritizeTrack(this, playlist, track.videoId);
        Toast.makeText(this, R.string.smart_stream_preparing,
                Toast.LENGTH_SHORT).show();
        trackStreamResolver.resolve(track, new TrackStreamResolver.Callback() {
            @Override
            public void onResolved(String streamUrl) {
                playResolvedStream(track, playlist, streamUrl);
            }

            @Override
            public void onFailure(Exception error) {
                android.util.Log.w(TAG, "Could not resolve stream", error);
                if (!isDestroyed()) {
                    Toast.makeText(MainActivity.this, R.string.smart_stream_failed,
                            Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void playResolvedStream(Track track, String playlist,
                                    String streamUrl) {
        if (isDestroyed()) {
            return;
        }
        List<Track> latestTracks = viewModel.tracks.getValue();
        if (latestTracks == null) {
            return;
        }
        playbackQueueController.playResolvedStream(
                track, playlist, latestTracks, streamUrl);
    }

    /** Добавить треки в конец очереди воспроизведения (без изменения БД). */
    void addToQueue(List<Track> tracks) {
        playbackQueueController.addToQueue(tracks);
    }

    /** Вставить треки сразу после проигрываемого. */
    @UnstableApi
    void insertAfterCurrent(List<Track> tracks) {
        playbackQueueController.insertAfterCurrent(tracks);
    }

    /** Добавить треки в другой плейлист, сохранив исходные записи и общий медиафайл. */
    void copyTracksToPlaylist(List<Track> tracks, String targetPlaylist) {
        String target = libraryFileController.sanitizePlaylistName(targetPlaylist);
        youtubeSyncController.offerTrackCopy(tracks, target,
                afterCopy -> libraryFileController.copyTracks(
                        tracks, target, afterCopy));
    }

    /** Убрать записи из плейлиста (файлы остаются на диске). */
    void removeTracksFromPlaylist(List<Track> tracks) {
        deleteTracksWithSyncChoice(tracks, false);
    }

    /** Удалить треки с устройства: файлы + записи + из очереди. */
    void deleteTracksFromDevice(List<Track> tracks) {
        deleteTracksWithSyncChoice(tracks, true);
    }

    private void deleteTracksWithSyncChoice(List<Track> tracks, boolean deleteFiles) {
        youtubeSyncController.deleteTracksWithChoice(tracks, deleteFiles,
                () -> libraryFileController.deleteTracks(
                        tracks, deleteFiles, () -> removeFromQueue(tracks)),
                this::removeFromQueue);
    }

    private void removeFromQueue(List<Track> tracks) {
        playbackQueueController.removeFromQueue(tracks);
    }

    @Override
    protected void onDestroy() {
        trackStreamResolver.destroy();
        youtubeSyncController.destroy();
        if (appUpdateManager != null) {
            appUpdateManager.close();
            appUpdateManager = null;
        }
        super.onDestroy();
    }

    /** Called by queue editing UI so playlist synchronization does not undo manual changes. */
    void markQueueCustomized() {
        playbackQueueController.markCustomized();
    }

    /** Rebuilds playback from the currently open playlist and disables shuffle. */
    void resetPlaybackQueue() {
        playbackQueueController.resetToActivePlaylist();
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
        playbackQueueController.clearIfLoaded(name);
        String defaultPlaylist = getString(R.string.default_playlist);
        if (name.equals(viewModel.getActivePlaylistName())) {
            viewModel.setActivePlaylist(defaultPlaylist);
        }
        libraryFileController.deletePlaylist(name);
    }

    void importFiles(List<android.net.Uri> uris, String playlist) {
        libraryFileController.importFiles(uris, playlist);
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
