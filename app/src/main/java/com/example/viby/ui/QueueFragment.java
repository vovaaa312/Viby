package com.example.viby.ui;

import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.viby.R;
import com.example.viby.data.PlaylistInfo;
import com.example.viby.data.Track;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Collections;
import java.util.List;

/**
 * Страница справа: список треков активного плейлиста + мини-плеер снизу.
 * Долгий тап — контекстное меню как в AIMP; из него доступен режим
 * множественного выбора с групповыми действиями.
 */
public class QueueFragment extends Fragment implements TracksAdapter.Listener {

    private PlayerViewModel viewModel;
    private MediaController controller;
    private TracksAdapter adapter;
    private TextView emptyText;

    private View selectionBar;
    private CheckBox selectAllCheck;
    private TextView selectionCount;
    private boolean updatingSelectAll;

    private ImageView miniThumb;
    private TextView miniTitle;
    private TextView miniArtist;
    private ImageButton miniPlay;

    private OnBackPressedCallback backCallback;

    /** Проводник для импорта аудиофайлов в текущий плейлист. */
    private final androidx.activity.result.ActivityResultLauncher<String[]> filePicker =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts
                            .OpenMultipleDocuments(),
                    this::importFiles);

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
            highlightCurrent();
            updateMiniPlayer();
        }

        @Override
        public void onMediaMetadataChanged(@NonNull MediaMetadata mediaMetadata) {
            updateMiniPlayer();
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            updateMiniPlayer();
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_queue, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        viewModel = new ViewModelProvider(requireActivity()).get(PlayerViewModel.class);

        emptyText = view.findViewById(R.id.emptyText);
        RecyclerView tracksList = view.findViewById(R.id.tracksList);
        adapter = new TracksAdapter(this);
        tracksList.setAdapter(adapter);

        selectionBar = view.findViewById(R.id.selectionBar);
        selectAllCheck = view.findViewById(R.id.selectAllCheck);
        selectionCount = view.findViewById(R.id.selectionCount);
        ImageButton selectionMove = view.findViewById(R.id.selectionMove);
        ImageButton selectionDelete = view.findViewById(R.id.selectionDelete);
        ImageButton selectionClose = view.findViewById(R.id.selectionClose);

        selectAllCheck.setOnCheckedChangeListener((btn, checked) -> {
            if (!updatingSelectAll) {
                adapter.selectAll(checked);
            }
        });
        selectionMove.setOnClickListener(v -> {
            List<Track> selected = adapter.getSelectedTracks();
            if (!selected.isEmpty()) {
                showMoveDialog(selected);
            }
        });
        selectionDelete.setOnClickListener(v -> {
            List<Track> selected = adapter.getSelectedTracks();
            if (!selected.isEmpty()) {
                confirmDeleteSelected(selected);
            }
        });
        selectionClose.setOnClickListener(v -> exitSelection());

        view.findViewById(R.id.refreshPlaylist).setOnClickListener(v -> refreshPlaylist());
        view.findViewById(R.id.addTracks).setOnClickListener(v -> showAddTracksDialog());

        android.widget.EditText searchInput = view.findViewById(R.id.searchInput);
        searchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                adapter.setQuery(s.toString());
            }
        });

        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                exitSelection();
            }
        };
        requireActivity().getOnBackPressedDispatcher()
                .addCallback(getViewLifecycleOwner(), backCallback);

        View miniPlayer = view.findViewById(R.id.miniPlayer);
        miniThumb = view.findViewById(R.id.miniThumb);
        miniTitle = view.findViewById(R.id.miniTitle);
        miniArtist = view.findViewById(R.id.miniArtist);
        miniPlay = view.findViewById(R.id.miniPlay);
        ImageButton miniPrev = view.findViewById(R.id.miniPrev);
        ImageButton miniNext = view.findViewById(R.id.miniNext);

        miniPlayer.setOnClickListener(v ->
                ((MainActivity) requireActivity()).showPlayerPage());
        miniPlay.setOnClickListener(v -> {
            if (controller == null) {
                return;
            }
            if (controller.isPlaying()) {
                controller.pause();
            } else {
                controller.play();
            }
        });
        miniPrev.setOnClickListener(v -> {
            if (controller != null) {
                controller.seekToPrevious();
            }
        });
        miniNext.setOnClickListener(v -> {
            if (controller != null) {
                controller.seekToNext();
            }
        });

        viewModel.tracks.observe(getViewLifecycleOwner(), tracks -> {
            adapter.submit(tracks);
            emptyText.setVisibility(tracks.isEmpty() ? View.VISIBLE : View.GONE);
        });

        viewModel.controller.observe(getViewLifecycleOwner(), newController -> {
            if (controller != null) {
                controller.removeListener(playerListener);
            }
            controller = newController;
            if (controller != null) {
                controller.addListener(playerListener);
            }
            highlightCurrent();
            updateMiniPlayer();
        });
    }

    @Override
    public void onDestroyView() {
        if (controller != null) {
            controller.removeListener(playerListener);
            controller = null;
        }
        super.onDestroyView();
    }

    // -------------------------------------------------------- context menu

    @Override
    public void onTrackLongClick(Track track) {
        String[] items = {
                getString(R.string.ctx_play),
                getString(R.string.ctx_add_queue),
                getString(R.string.ctx_insert_after_current),
                getString(R.string.ctx_move_to_playlist),
                getString(R.string.ctx_select),
                getString(R.string.ctx_remove_from_playlist),
                getString(R.string.ctx_delete_from_device),
        };
        MainActivity activity = (MainActivity) requireActivity();
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(track.title)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            playFromList(track);
                            break;
                        case 1:
                            activity.addToQueue(Collections.singletonList(track));
                            break;
                        case 2:
                            activity.insertAfterCurrent(Collections.singletonList(track));
                            break;
                        case 3:
                            showMoveDialog(Collections.singletonList(track));
                            break;
                        case 4:
                            enterSelection(track);
                            break;
                        case 5:
                            activity.removeTracksFromPlaylist(
                                    Collections.singletonList(track));
                            break;
                        case 6:
                            activity.confirmDeleteTrack(track);
                            break;
                        default:
                            break;
                    }
                })
                .show();
    }

    @Override
    public void onTrackClick(Track track, int position) {
        // position — индекс в отфильтрованном списке; реальный ищем по id
        playFromList(track);
    }

    @Override
    public void onSelectionChanged(int selectedCount) {
        selectionCount.setText(getString(R.string.selection_title, selectedCount));
        updatingSelectAll = true;
        selectAllCheck.setChecked(selectedCount > 0
                && selectedCount == adapter.getItemCount());
        updatingSelectAll = false;
    }

    /**
     * «Обновить плейлист»: заново читаем YouTube-плейлист, из которого он был
     * скачан, и докачиваем только новые треки (уже скачанные пропускаются).
     */
    private void refreshPlaylist() {
        String playlist = viewModel.getActivePlaylistName();
        if (playlist == null) {
            return;
        }
        android.content.Context appContext = requireContext().getApplicationContext();
        com.example.viby.data.VibyDatabase.dbExecutor.execute(() -> {
            com.example.viby.data.PlaylistSource source =
                    com.example.viby.data.VibyDatabase.get(appContext)
                            .playlistSourceDao().getSync(playlist);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (source == null) {
                    android.widget.Toast.makeText(appContext,
                            R.string.refresh_no_source,
                            android.widget.Toast.LENGTH_LONG).show();
                } else {
                    com.example.viby.download.DownloadService.enqueue(
                            appContext, source.sourceUrl, playlist, true);
                    android.widget.Toast.makeText(appContext,
                            R.string.refresh_started,
                            android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    // ---------------------------------------------------------- add tracks

    private void showAddTracksDialog() {
        String[] options = {
                getString(R.string.add_from_files),
                getString(R.string.add_from_url),
        };
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.add_tracks_title)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        filePicker.launch(new String[]{"audio/*"});
                    } else {
                        showAddByUrlDialog();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showAddByUrlDialog() {
        android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(R.string.url_hint);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.add_from_url)
                .setView(input)
                .setPositiveButton(R.string.btn_download, (dialog, which) -> {
                    String url = input.getText().toString().trim();
                    String playlist = viewModel.getActivePlaylistName();
                    if (url.contains("youtube.com/") || url.contains("youtu.be/")) {
                        com.example.viby.download.DownloadService.enqueue(
                                requireContext(), url, playlist, false);
                        android.widget.Toast.makeText(requireContext(),
                                R.string.download_queued,
                                android.widget.Toast.LENGTH_SHORT).show();
                    } else {
                        android.widget.Toast.makeText(requireContext(),
                                R.string.error_invalid_url,
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Копирует выбранные аудиофайлы в папку плейлиста и добавляет их в базу. */
    private void importFiles(java.util.List<android.net.Uri> uris) {
        if (uris == null || uris.isEmpty()) {
            return;
        }
        String playlist = viewModel.getActivePlaylistName();
        if (playlist == null) {
            return;
        }
        android.content.Context app = requireContext().getApplicationContext();
        android.widget.Toast.makeText(app, R.string.importing_tracks,
                android.widget.Toast.LENGTH_SHORT).show();
        com.example.viby.data.VibyDatabase.dbExecutor.execute(() -> {
            var dao = com.example.viby.data.VibyDatabase.get(app).trackDao();
            java.io.File dir = com.example.viby.util.StorageHelper.playlistDir(app, playlist);
            int added = 0;
            for (android.net.Uri uri : uris) {
                try {
                    String name = displayName(app, uri);
                    java.io.File dest = new java.io.File(dir,
                            com.example.viby.util.StorageHelper.sanitize(name));
                    if (dest.exists()) {
                        dest = new java.io.File(dir,
                                System.currentTimeMillis() + " " + dest.getName());
                    }
                    try (java.io.InputStream in =
                                 app.getContentResolver().openInputStream(uri);
                         java.io.OutputStream out = new java.io.FileOutputStream(dest)) {
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = in.read(buffer)) > 0) {
                            out.write(buffer, 0, read);
                        }
                    }

                    String title = null;
                    String artist = null;
                    long durationMs = 0;
                    android.media.MediaMetadataRetriever retriever =
                            new android.media.MediaMetadataRetriever();
                    try {
                        retriever.setDataSource(dest.getAbsolutePath());
                        title = retriever.extractMetadata(
                                android.media.MediaMetadataRetriever.METADATA_KEY_TITLE);
                        artist = retriever.extractMetadata(
                                android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST);
                        String duration = retriever.extractMetadata(
                                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                        if (duration != null) {
                            durationMs = Long.parseLong(duration);
                        }
                    } catch (Exception ignored) {
                    } finally {
                        try {
                            retriever.release();
                        } catch (Exception ignored) {
                        }
                    }

                    Track track = new Track();
                    track.title = title != null && !title.isEmpty()
                            ? title : stripExtension(dest.getName());
                    track.uploader = artist;
                    track.durationMs = durationMs;
                    track.filePath = dest.getAbsolutePath();
                    track.playlistName = playlist;
                    track.position = dao.nextPosition(playlist);
                    track.createdAt = System.currentTimeMillis();
                    dao.insert(track);
                    added++;
                } catch (Exception e) {
                    android.util.Log.w("QueueFragment", "import failed: " + uri, e);
                }
            }
            int total = added;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                    android.widget.Toast.makeText(app,
                            app.getString(R.string.tracks_added, total),
                            android.widget.Toast.LENGTH_SHORT).show());
        });
    }

    private static String displayName(android.content.Context context, android.net.Uri uri) {
        try (android.database.Cursor cursor = context.getContentResolver().query(
                uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (Exception ignored) {
        }
        return "track_" + System.currentTimeMillis() + ".mp3";
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private void playFromList(Track track) {
        List<Track> tracks = viewModel.tracks.getValue();
        if (tracks == null) {
            return;
        }
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i).id == track.id) {
                ((MainActivity) requireActivity()).playTrack(track, i);
                return;
            }
        }
    }

    // -------------------------------------------------------- multi-select

    private void enterSelection(Track initial) {
        adapter.enterSelectionMode(initial);
        selectionBar.setVisibility(View.VISIBLE);
        backCallback.setEnabled(true);
    }

    private void exitSelection() {
        adapter.exitSelectionMode();
        selectionBar.setVisibility(View.GONE);
        updatingSelectAll = true;
        selectAllCheck.setChecked(false);
        updatingSelectAll = false;
        backCallback.setEnabled(false);
    }

    private void confirmDeleteSelected(List<Track> selected) {
        MainActivity activity = (MainActivity) requireActivity();
        String[] options = {
                getString(R.string.ctx_remove_from_playlist),
                getString(R.string.ctx_delete_from_device),
        };
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.delete_selected_title)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        activity.removeTracksFromPlaylist(selected);
                    } else {
                        new MaterialAlertDialogBuilder(requireContext())
                                .setTitle(R.string.delete_selected_title)
                                .setMessage(getString(
                                        R.string.delete_selected_message, selected.size()))
                                .setPositiveButton(R.string.btn_delete,
                                        (d2, w2) -> {
                                            activity.deleteTracksFromDevice(selected);
                                            exitSelection();
                                        })
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                        return;
                    }
                    exitSelection();
                })
                .show();
    }

    private void showMoveDialog(List<Track> selected) {
        MainActivity activity = (MainActivity) requireActivity();
        List<PlaylistInfo> playlists = viewModel.playlists.getValue();
        int count = playlists != null ? playlists.size() : 0;
        String[] names = new String[count];
        for (int i = 0; i < count; i++) {
            names[i] = playlists.get(i).playlistName;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.move_dialog_title)
                .setItems(names, (dialog, which) -> {
                    activity.moveTracksToPlaylist(selected, names[which]);
                    exitSelection();
                })
                .setNeutralButton(R.string.new_playlist_hint, (dialog, which) -> {
                    EditText input = new EditText(requireContext());
                    input.setInputType(InputType.TYPE_CLASS_TEXT);
                    input.setHint(R.string.playlist_hint);
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.move_dialog_title)
                            .setView(input)
                            .setPositiveButton(android.R.string.ok, (d2, w2) -> {
                                String name = input.getText().toString().trim();
                                if (!name.isEmpty()) {
                                    activity.moveTracksToPlaylist(selected, name);
                                    exitSelection();
                                }
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------ helpers

    private void highlightCurrent() {
        if (controller == null || controller.getCurrentMediaItem() == null) {
            adapter.setCurrentTrackId(-1);
            return;
        }
        try {
            adapter.setCurrentTrackId(
                    Long.parseLong(controller.getCurrentMediaItem().mediaId));
        } catch (NumberFormatException ignored) {
            adapter.setCurrentTrackId(-1);
        }
    }

    private void updateMiniPlayer() {
        if (miniTitle == null) {
            return;
        }
        if (controller == null || controller.getCurrentMediaItem() == null) {
            miniTitle.setText(R.string.no_track);
            miniArtist.setText("");
            miniPlay.setImageResource(R.drawable.ic_play);
            miniThumb.setImageResource(R.drawable.ic_music_note);
            return;
        }
        MediaMetadata metadata = controller.getMediaMetadata();
        miniTitle.setText(metadata.title != null ? metadata.title
                : getString(R.string.no_track));
        miniArtist.setText(metadata.artist != null ? metadata.artist
                : getString(R.string.unknown_artist));
        miniPlay.setImageResource(controller.isPlaying()
                ? R.drawable.ic_pause : R.drawable.ic_play);

        if (metadata.artworkData != null) {
            Glide.with(this)
                    .load(metadata.artworkData)
                    .placeholder(R.drawable.ic_music_note)
                    .centerCrop()
                    .into(miniThumb);
        } else if (metadata.artworkUri != null) {
            Glide.with(this)
                    .load(metadata.artworkUri)
                    .placeholder(R.drawable.ic_music_note)
                    .error(R.drawable.ic_music_note)
                    .centerCrop()
                    .into(miniThumb);
        } else {
            miniThumb.setImageResource(R.drawable.ic_music_note);
        }
    }
}
