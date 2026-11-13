package com.example.viby.ui;

import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.recyclerview.widget.RecyclerView;

import com.example.viby.R;
import com.example.viby.data.PlaylistInfo;
import com.example.viby.data.Track;
import com.example.viby.download.DownloadService;
import com.example.viby.ui.widget.PlaylistFastScroller;
import com.example.viby.util.YoutubeUrlParser;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Страница справа: список треков активного плейлиста + мини-плеер снизу.
 * Долгий тап — контекстное меню как в AIMP; из него доступен режим
 * множественного выбора с групповыми действиями.
 */
@OptIn(markerClass = UnstableApi.class)
public class QueueFragment extends Fragment implements TracksAdapter.Listener {

    private static final int ACTION_PLAY = 0;
    private static final int ACTION_ADD_TO_QUEUE = 1;
    private static final int ACTION_INSERT_AFTER_CURRENT = 2;
    private static final int ACTION_TRACK_RADIO = 3;
    private static final int ACTION_MOVE_TO_PLAYLIST = 4;
    private static final int ACTION_SELECT = 5;
    private static final int ACTION_REMOVE_FROM_PLAYLIST = 6;
    private static final int ACTION_DELETE_FROM_DEVICE = 7;
    private static final int ACTION_TOGGLE_FAVORITE = 8;

    private static final int BULK_PLAY = 0;
    private static final int BULK_ADD_TO_QUEUE = 1;
    private static final int BULK_PLAY_NEXT = 2;
    private static final int BULK_FAVORITE = 3;
    private static final int BULK_UNFAVORITE = 4;
    private static final int BULK_RETRY_DOWNLOADS = 5;

    private PlayerViewModel viewModel;
    private MediaController controller;
    private TracksAdapter adapter;
    private PlaylistFastScroller fastScroller;
    private TextView emptyText;

    private View selectionBar;
    private CheckBox selectAllCheck;
    private TextView selectionCount;
    private boolean updatingSelectAll;

    private MiniPlayerBinder miniPlayer;

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
        fastScroller = view.findViewById(R.id.playlistFastScroller);
        fastScroller.attachTo(tracksList);

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
        view.findViewById(R.id.selectionMore).setOnClickListener(v -> {
            List<Track> selected = adapter.getSelectedTracks();
            if (!selected.isEmpty()) {
                showBulkActions(selected);
            }
        });

        view.findViewById(R.id.refreshPlaylist).setOnClickListener(v -> refreshPlaylist());
        view.findViewById(R.id.addTracks).setOnClickListener(v -> showAddTracksDialog());

        android.widget.EditText searchInput = view.findViewById(R.id.searchInput);
        android.view.View searchClear = view.findViewById(R.id.searchClear);
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
                updateSearchClear(searchInput, searchClear);
            }
        });
        // the cross shows while typing or while there is a query: one tap clears the
        // query and puts the keyboard away
        searchInput.setOnFocusChangeListener((v, hasFocus) ->
                updateSearchClear(searchInput, searchClear));
        searchClear.setOnClickListener(v -> {
            searchInput.setText("");
            closeSearchKeyboard(searchInput);
        });
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                closeSearchKeyboard(searchInput); // results are already filtered
                return true;
            }
            return false;
        });

        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                exitSelection();
            }
        };
        requireActivity().getOnBackPressedDispatcher()
                .addCallback(getViewLifecycleOwner(), backCallback);

        miniPlayer = new MiniPlayerBinder(view.findViewById(R.id.miniPlayer),
                () -> ((MainActivity) requireActivity()).showPlayerPage());

        viewModel.tracks.observe(getViewLifecycleOwner(), tracks -> {
            adapter.submit(tracks);
            emptyText.setVisibility(tracks.isEmpty() ? View.VISIBLE : View.GONE);
        });

        DownloadService.getJobs().observe(getViewLifecycleOwner(),
                adapter::setDownloadJobs);

        viewModel.controller.observe(getViewLifecycleOwner(), newController -> {
            if (controller != null) {
                controller.removeListener(playerListener);
            }
            controller = newController;
            if (controller != null) {
                controller.addListener(playerListener);
            }
            highlightCurrent();
            miniPlayer.setPlayer(controller);
        });
    }

    @Override
    public void onDestroyView() {
        if (fastScroller != null) {
            fastScroller.detachFromRecyclerView();
            fastScroller = null;
        }
        if (controller != null) {
            controller.removeListener(playerListener);
            controller = null;
        }
        if (miniPlayer != null) {
            miniPlayer.setPlayer(null);
            miniPlayer = null;
        }
        super.onDestroyView();
    }

    // -------------------------------------------------------- context menu

    @Override
    public void onTrackLongClick(Track track) {
        if (!track.downloaded) {
            if (isFailedDownload(track)) {
                showFailedDownloadDialog(track);
                return;
            }
            Toast.makeText(requireContext(), R.string.pending_track_actions_disabled,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> items = new ArrayList<>();
        List<Integer> actions = new ArrayList<>();
        addContextAction(items, actions, R.string.ctx_play, ACTION_PLAY);
        addContextAction(items, actions, R.string.ctx_add_queue, ACTION_ADD_TO_QUEUE);
        addContextAction(items, actions, R.string.ctx_insert_after_current,
                ACTION_INSERT_AFTER_CURRENT);
        addContextAction(items, actions, track.favorite
                ? R.string.ctx_unfavorite : R.string.ctx_favorite, ACTION_TOGGLE_FAVORITE);
        if (hasYoutubeSource(track)) {
            addContextAction(items, actions, R.string.ctx_track_radio, ACTION_TRACK_RADIO);
        }
        addContextAction(items, actions, R.string.ctx_move_to_playlist,
                ACTION_MOVE_TO_PLAYLIST);
        addContextAction(items, actions, R.string.ctx_select, ACTION_SELECT);
        addContextAction(items, actions, R.string.ctx_remove_from_playlist,
                ACTION_REMOVE_FROM_PLAYLIST);
        addContextAction(items, actions, R.string.ctx_delete_from_device,
                ACTION_DELETE_FROM_DEVICE);
        MainActivity activity = (MainActivity) requireActivity();
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(track.title)
                .setItems(items.toArray(new String[0]), (dialog, which) -> {
                    switch (actions.get(which)) {
                        case ACTION_PLAY:
                            playFromList(track);
                            break;
                        case ACTION_ADD_TO_QUEUE:
                            activity.addToQueue(Collections.singletonList(track));
                            break;
                        case ACTION_INSERT_AFTER_CURRENT:
                            activity.insertAfterCurrent(Collections.singletonList(track));
                            break;
                        case ACTION_TRACK_RADIO:
                            activity.startTrackRadio(track);
                            break;
                        case ACTION_TOGGLE_FAVORITE:
                            activity.setFavorite(Collections.singletonList(track),
                                    !track.favorite);
                            break;
                        case ACTION_MOVE_TO_PLAYLIST:
                            showMoveDialog(Collections.singletonList(track));
                            break;
                        case ACTION_SELECT:
                            enterSelection(track);
                            break;
                        case ACTION_REMOVE_FROM_PLAYLIST:
                            activity.removeTracksFromPlaylist(
                                    Collections.singletonList(track));
                            break;
                        case ACTION_DELETE_FROM_DEVICE:
                            activity.confirmDeleteTrack(track);
                            break;
                        default:
                            break;
                    }
                })
                .show();
    }

    private void addContextAction(List<String> items, List<Integer> actions,
                                  int labelRes, int action) {
        items.add(getString(labelRes));
        actions.add(action);
    }

    private static boolean hasYoutubeSource(Track track) {
        return (track.videoId != null && !track.videoId.isEmpty())
                || YoutubeUrlParser.videoId(track.sourceUrl) != null;
    }

    @Override
    public void onTrackClick(Track track, int position) {
        if (!track.downloaded && isFailedDownload(track)) {
            showFailedDownloadDialog(track);
            return;
        }
        // position — индекс в отфильтрованном списке; реальный ищем по id
        playFromList(track);
    }

    private static boolean isFailedDownload(Track track) {
        return Track.DOWNLOAD_FAILED_TEMPORARY.equals(track.downloadState)
                || Track.DOWNLOAD_FAILED_PERMANENT.equals(track.downloadState);
    }

    private void showFailedDownloadDialog(Track track) {
        MainActivity activity = (MainActivity) requireActivity();
        DownloadFailureDialog.show(requireContext(), track,
                () -> activity.confirmDeleteTrack(track));
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
    private static void updateSearchClear(android.widget.EditText input, View clear) {
        boolean show = input.hasFocus() || input.length() > 0;
        clear.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private static void closeSearchKeyboard(android.widget.EditText input) {
        input.clearFocus();
        android.view.inputmethod.InputMethodManager keyboard =
                input.getContext().getSystemService(
                        android.view.inputmethod.InputMethodManager.class);
        if (keyboard != null) {
            keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
    }

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
                .setView(DialogInputs.padded(input))
                .setPositiveButton(R.string.btn_download, (dialog, which) -> {
                    String url = input.getText().toString().trim();
                    String playlist = viewModel.getActivePlaylistName();
                    if (url.contains("youtube.com/") || url.contains("youtu.be/")) {
                        ((MainActivity) requireActivity()).addTrackByUrl(
                                url, playlist);
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
        ((MainActivity) requireActivity()).importFiles(uris, playlist);
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

    /** Bulk actions for the multi-selection; playback actions use downloaded tracks only. */
    private void showBulkActions(List<Track> selected) {
        MainActivity activity = (MainActivity) requireActivity();
        List<Track> playable = new ArrayList<>();
        List<Track> failed = new ArrayList<>();
        for (Track track : selected) {
            if (PlaybackQueueController.isPlayableLocal(track)) {
                playable.add(track);
            } else if (isFailedDownload(track)) {
                failed.add(track);
            }
        }
        List<String> items = new ArrayList<>();
        List<Integer> actions = new ArrayList<>();
        if (!playable.isEmpty()) {
            addContextAction(items, actions, R.string.bulk_play, BULK_PLAY);
            addContextAction(items, actions, R.string.ctx_add_queue, BULK_ADD_TO_QUEUE);
            addContextAction(items, actions, R.string.ctx_insert_after_current,
                    BULK_PLAY_NEXT);
        }
        addContextAction(items, actions, R.string.ctx_favorite, BULK_FAVORITE);
        addContextAction(items, actions, R.string.ctx_unfavorite, BULK_UNFAVORITE);
        if (!failed.isEmpty()) {
            addContextAction(items, actions, R.string.bulk_retry_downloads,
                    BULK_RETRY_DOWNLOADS);
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.selection_title, selected.size()))
                .setItems(items.toArray(new String[0]), (dialog, which) -> {
                    switch (actions.get(which)) {
                        case BULK_PLAY:
                            activity.playTrackList(playable, playable.get(0).id);
                            break;
                        case BULK_ADD_TO_QUEUE:
                            activity.addToQueue(playable);
                            break;
                        case BULK_PLAY_NEXT:
                            activity.insertAfterCurrent(playable);
                            break;
                        case BULK_FAVORITE:
                            activity.setFavorite(selected, true);
                            break;
                        case BULK_UNFAVORITE:
                            activity.setFavorite(selected, false);
                            break;
                        case BULK_RETRY_DOWNLOADS:
                            for (Track track : failed) {
                                DownloadService.retryPersistedTrack(requireContext(), track);
                            }
                            Toast.makeText(requireContext(),
                                    getString(R.string.bulk_retry_started, failed.size()),
                                    Toast.LENGTH_SHORT).show();
                            break;
                        default:
                            break;
                    }
                    exitSelection();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
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
                    activity.copyTracksToPlaylist(selected, names[which]);
                    exitSelection();
                })
                .setNeutralButton(R.string.new_playlist_hint, (dialog, which) -> {
                    EditText input = new EditText(requireContext());
                    input.setInputType(InputType.TYPE_CLASS_TEXT);
                    input.setHint(R.string.playlist_hint);
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.move_dialog_title)
                            .setView(DialogInputs.padded(input))
                            .setPositiveButton(android.R.string.ok, (d2, w2) -> {
                                String name = input.getText().toString().trim();
                                if (!name.isEmpty()) {
                                    activity.copyTracksToPlaylist(selected, name);
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

}
