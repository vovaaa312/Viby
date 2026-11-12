package com.example.viby.ui;

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.viby.R;
import com.example.viby.data.Track;
import com.example.viby.data.VibyDatabase;
import com.example.viby.download.DownloadService;
import com.example.viby.library.LibraryBrowser;
import com.example.viby.library.LibraryMaintenance;
import com.example.viby.playback.PlaybackService;
import com.example.viby.ui.widget.PlaylistFastScroller;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Whole-library browser: all tracks, artists, albums and folders with global search,
 * filters, sorting, saved views and bulk actions. Plays through its own MediaController so
 * the user can keep browsing while music starts.
 */
@OptIn(markerClass = UnstableApi.class)
public class LibraryActivity extends AppCompatActivity implements LibraryAdapter.Listener {

    private static final String PREFS = "viby_library";
    private static final String KEY_TAB = "tab";
    private static final String KEY_FILTER = "filter";
    private static final String KEY_SORT = "sort";
    private static final String KEY_REVERSE = "reverse";
    private static final String KEY_VIEWS = "saved_views"; // legacy, cleared on start
    private static final String STATE_GROUP = "open_group";
    private static final String STATE_QUERY = "query";
    private static final String STATE_GROUPS_POSITION = "groups_position";
    private static final String STATE_GROUPS_OFFSET = "groups_offset";
    private static final long RECOMPUTE_DELAY_MS = 150L;

    private static final LibraryBrowser.Filter[] FILTERS = LibraryBrowser.Filter.values();
    private static final int[] FILTER_LABELS = {
            R.string.library_filter_all,
            R.string.library_filter_favorites,
            R.string.library_filter_recently_added,
            R.string.library_filter_recently_played,
            R.string.library_filter_not_downloaded,
            R.string.library_filter_failed,
    };
    private static final LibraryBrowser.Sort[] SORTS = LibraryBrowser.Sort.values();
    private static final int[] SORT_LABELS = {
            R.string.library_sort_default,
            R.string.sort_by_title,
            R.string.sort_by_artist,
            R.string.library_sort_album,
            R.string.sort_by_date_added,
            R.string.library_sort_last_played,
            R.string.library_sort_play_count,
            R.string.sort_by_duration,
    };
    private static final int[] TAB_LABELS = {
            R.string.library_tab_tracks,
            R.string.library_tab_artists,
            R.string.library_tab_albums,
            R.string.library_tab_folders,
    };

    private SharedPreferences prefs;
    private LibraryAdapter adapter;
    private MaterialToolbar toolbar;
    private RecyclerView list;
    private PlaylistFastScroller fastScroller;
    private MiniPlayerBinder miniPlayer;
    /** Scroll position of the artist/album/folder list when a group was opened. */
    private int groupsPosition = RecyclerView.NO_POSITION;
    private int groupsOffset;
    private boolean restoreGroupsScroll;
    private boolean scrollTracksToTop;
    private EditText search;
    private TabLayout tabs;
    private ChipGroup filterChips;
    private View groupHeader;
    private TextView groupTitle;
    private View selectionBar;
    private CheckBox selectAll;
    private TextView selectionCount;
    private TextView emptyText;
    private OnBackPressedCallback backCallback;
    private boolean updatingSelectAll;

    private LibraryBrowser.Tab tab = LibraryBrowser.Tab.TRACKS;
    private LibraryBrowser.Filter filter = LibraryBrowser.Filter.ALL;
    private LibraryBrowser.Sort sort = LibraryBrowser.Sort.DEFAULT;
    private boolean reverse;
    private String query = "";
    @Nullable private String openGroupKey;
    @Nullable private LibraryBrowser.Group openGroup;
    private List<Track> allTracks = Collections.emptyList();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable recompute = this::recompute;

    private PlayerViewModel viewModel;
    private PlaybackQueueController queueController;
    private LibraryFileController fileController;
    private TrackStreamResolver streamResolver;
    private YoutubeSyncController youtubeSyncController;
    @Nullable private ListenableFuture<MediaController> controllerFuture;
    @Nullable private MediaController controller;
    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
            highlightCurrent();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_library);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        viewModel = new ViewModelProvider(this).get(PlayerViewModel.class);
        queueController = new PlaybackQueueController(this, viewModel);
        fileController = new LibraryFileController(this);
        streamResolver = new TrackStreamResolver(this);
        // registers an activity-result launcher, so it must be created in onCreate
        youtubeSyncController = new YoutubeSyncController(this);

        tab = LibraryBrowser.parseEnum(LibraryBrowser.Tab.class,
                prefs.getString(KEY_TAB, null), LibraryBrowser.Tab.TRACKS);
        filter = LibraryBrowser.parseEnum(LibraryBrowser.Filter.class,
                prefs.getString(KEY_FILTER, null), LibraryBrowser.Filter.ALL);
        sort = LibraryBrowser.parseEnum(LibraryBrowser.Sort.class,
                prefs.getString(KEY_SORT, null), LibraryBrowser.Sort.DEFAULT);
        reverse = prefs.getBoolean(KEY_REVERSE, false);
        // saved views were removed in 1.2.9; drop what earlier test builds stored
        prefs.edit().remove(KEY_VIEWS).apply();
        if (savedInstanceState != null) {
            openGroupKey = savedInstanceState.getString(STATE_GROUP);
            query = savedInstanceState.getString(STATE_QUERY, "");
            groupsPosition = savedInstanceState.getInt(STATE_GROUPS_POSITION,
                    RecyclerView.NO_POSITION);
            groupsOffset = savedInstanceState.getInt(STATE_GROUPS_OFFSET, 0);
        }

        toolbar = findViewById(R.id.libraryToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_library);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_library_sort) {
                showSortDialog();
                return true;
            }
            if (item.getItemId() == R.id.action_library_select) {
                if (!adapter.isShowingGroups()) {
                    enterSelection(null);
                }
                return true;
            }
            return false;
        });

        list = findViewById(R.id.libraryList);
        adapter = new LibraryAdapter(this);
        list.setAdapter(adapter);
        fastScroller = findViewById(R.id.libraryFastScroller);
        fastScroller.attachTo(list);
        miniPlayer = new MiniPlayerBinder(findViewById(R.id.libraryMiniPlayer),
                this::openPlayer);
        emptyText = findViewById(R.id.libraryEmpty);

        search = findViewById(R.id.librarySearch);
        search.setText(query);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString();
                scheduleRecompute();
            }
        });

        setUpTabs();
        setUpFilters();

        groupHeader = findViewById(R.id.libraryGroupHeader);
        groupTitle = findViewById(R.id.libraryGroupTitle);
        findViewById(R.id.libraryGroupBack).setOnClickListener(v -> closeGroup());
        findViewById(R.id.libraryGroupPlay).setOnClickListener(v -> {
            if (openGroup != null) {
                playList(adapter.getTracks(), -1L);
            }
        });

        setUpSelectionBar();
        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                if (adapter.isSelectionMode()) {
                    exitSelection();
                } else {
                    closeGroup();
                }
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backCallback);

        VibyDatabase.get(this).trackDao().observeAll().observe(this, tracks -> {
            allTracks = tracks != null ? tracks : Collections.emptyList();
            scheduleRecompute();
        });
        LibraryMaintenance.scanMetadataAsync(this);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_GROUP, openGroupKey);
        outState.putString(STATE_QUERY, query);
        outState.putInt(STATE_GROUPS_POSITION, groupsPosition);
        outState.putInt(STATE_GROUPS_OFFSET, groupsOffset);
    }

    // ------------------------------------------------------------------ setup

    private void setUpTabs() {
        tabs = findViewById(R.id.libraryTabs);
        for (int label : TAB_LABELS) {
            tabs.addTab(tabs.newTab().setText(label), false);
        }
        TabLayout.Tab selected = tabs.getTabAt(tab.ordinal());
        if (selected != null) {
            selected.select();
        }
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab selectedTab) {
                tab = LibraryBrowser.Tab.values()[selectedTab.getPosition()];
                openGroupKey = null;
                groupsPosition = RecyclerView.NO_POSITION;
                restoreGroupsScroll = false;
                list.scrollToPosition(0);
                exitSelection();
                persistState();
                recompute();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab unselected) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab reselected) {
                closeGroup();
            }
        });
    }

    private void setUpFilters() {
        filterChips = findViewById(R.id.libraryFilters);
        for (int i = 0; i < FILTERS.length; i++) {
            Chip chip = (Chip) getLayoutInflater().inflate(
                    R.layout.chip_filter, filterChips, false);
            chip.setId(View.generateViewId());
            chip.setText(FILTER_LABELS[i]);
            chip.setCheckable(true);
            chip.setTag(FILTERS[i]);
            filterChips.addView(chip);
        }
        checkFilterChip();
        filterChips.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) {
                return;
            }
            View chip = group.findViewById(checkedIds.get(0));
            if (chip != null && chip.getTag() instanceof LibraryBrowser.Filter) {
                filter = (LibraryBrowser.Filter) chip.getTag();
                persistState();
                recompute();
            }
        });
    }

    private void checkFilterChip() {
        for (int i = 0; i < filterChips.getChildCount(); i++) {
            View child = filterChips.getChildAt(i);
            if (child.getTag() == filter) {
                filterChips.check(child.getId());
            }
        }
    }

    private void setUpSelectionBar() {
        selectionBar = findViewById(R.id.librarySelectionBar);
        selectAll = findViewById(R.id.librarySelectAll);
        selectionCount = findViewById(R.id.librarySelectionCount);
        selectAll.setOnCheckedChangeListener((button, checked) -> {
            if (!updatingSelectAll) {
                adapter.selectAll(checked);
            }
        });
        findViewById(R.id.librarySelectionClose).setOnClickListener(v -> exitSelection());
        findViewById(R.id.librarySelectionPlay).setOnClickListener(v ->
                withSelection(selected -> playList(selected, -1L)));
        findViewById(R.id.librarySelectionQueue).setOnClickListener(v ->
                withSelection(this::addToQueue));
        findViewById(R.id.librarySelectionFavorite).setOnClickListener(v ->
                withSelection(selected -> {
                    fileController.setFavorite(selected, !allFavorite(selected));
                    exitSelection();
                }));
        findViewById(R.id.librarySelectionMore).setOnClickListener(v ->
                withSelection(this::showBulkActions));
    }

    private interface SelectionAction {
        void run(List<Track> selected);
    }

    private void withSelection(SelectionAction action) {
        List<Track> selected = adapter.getSelectedTracks();
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.library_select_first, Toast.LENGTH_SHORT).show();
        } else {
            action.run(selected);
        }
    }

    // --------------------------------------------------------------- compute

    private void scheduleRecompute() {
        handler.removeCallbacks(recompute);
        handler.postDelayed(recompute, RECOMPUTE_DELAY_MS);
    }

    private void recompute() {
        handler.removeCallbacks(recompute);
        List<Track> filtered = LibraryBrowser.filter(LibraryBrowser.distinct(allTracks),
                filter, query, System.currentTimeMillis());
        openGroup = null;
        if (tab == LibraryBrowser.Tab.TRACKS) {
            openGroupKey = null;
            adapter.showTracks(LibraryBrowser.sort(filtered, sort, filter, reverse));
        } else {
            List<LibraryBrowser.Group> groups = LibraryBrowser.group(
                    filtered, tab, unknownGroupLabel());
            if (openGroupKey != null) {
                for (LibraryBrowser.Group group : groups) {
                    if (group.key.equals(openGroupKey)) {
                        openGroup = group;
                        break;
                    }
                }
            }
            if (openGroup != null) {
                adapter.showTracks(LibraryBrowser.sort(openGroup.tracks, sort, filter,
                        reverse));
            } else {
                openGroupKey = null;
                adapter.showGroups(groups, tab);
            }
        }
        applyPendingScroll();
        groupHeader.setVisibility(openGroup != null ? View.VISIBLE : View.GONE);
        if (openGroup != null) {
            groupTitle.setText(openGroup.title);
        }
        emptyText.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
        // selection works on tracks only, not on the artist/album/folder list
        toolbar.getMenu().findItem(R.id.action_library_select)
                .setVisible(!adapter.isShowingGroups());
        updateBackCallback();
        highlightCurrent();
    }

    private String unknownGroupLabel() {
        switch (tab) {
            case ALBUMS:
                return getString(R.string.library_unknown_album);
            case FOLDERS:
                return getString(R.string.library_unknown_folder);
            default:
                return getString(R.string.unknown_artist);
        }
    }

    private void closeGroup() {
        if (openGroupKey == null) {
            return;
        }
        exitSelection();
        openGroupKey = null;
        restoreGroupsScroll = true;
        recompute();
    }

    /** Remembers where the artist/album/folder list was before a group is opened. */
    private void rememberGroupsScroll() {
        LinearLayoutManager manager = (LinearLayoutManager) list.getLayoutManager();
        if (manager == null) {
            return;
        }
        int first = manager.findFirstVisibleItemPosition();
        View firstView = first == RecyclerView.NO_POSITION
                ? null : manager.findViewByPosition(first);
        groupsPosition = first;
        groupsOffset = firstView == null ? 0 : firstView.getTop() - list.getPaddingTop();
    }

    private void applyPendingScroll() {
        LinearLayoutManager manager = (LinearLayoutManager) list.getLayoutManager();
        if (manager == null) {
            return;
        }
        if (scrollTracksToTop && openGroup != null) {
            scrollTracksToTop = false;
            manager.scrollToPositionWithOffset(0, 0);
        } else if (restoreGroupsScroll && adapter.isShowingGroups()) {
            restoreGroupsScroll = false;
            if (groupsPosition != RecyclerView.NO_POSITION
                    && groupsPosition < adapter.getItemCount()) {
                manager.scrollToPositionWithOffset(groupsPosition, groupsOffset);
            }
        }
    }

    private void updateBackCallback() {
        backCallback.setEnabled(adapter.isSelectionMode() || openGroupKey != null);
    }

    private void persistState() {
        prefs.edit()
                .putString(KEY_TAB, tab.name())
                .putString(KEY_FILTER, filter.name())
                .putString(KEY_SORT, sort.name())
                .putBoolean(KEY_REVERSE, reverse)
                .apply();
    }

    // ------------------------------------------------------- adapter callbacks

    @Override
    public void onGroupClick(LibraryBrowser.Group group) {
        rememberGroupsScroll();
        openGroupKey = group.key;
        scrollTracksToTop = true;
        recompute();
    }

    @Override
    public void onGroupLongClick(LibraryBrowser.Group group) {
        String[] actions = {
                getString(R.string.bulk_play),
                getString(R.string.ctx_add_queue),
                getString(R.string.ctx_insert_after_current),
        };
        List<Track> tracks = LibraryBrowser.sort(group.tracks, sort, filter, reverse);
        new MaterialAlertDialogBuilder(this)
                .setTitle(group.title)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        playList(tracks, -1L);
                    } else if (which == 1) {
                        addToQueue(tracks);
                    } else {
                        playNext(tracks);
                    }
                })
                .show();
    }

    @Override
    public void onTrackClick(Track track) {
        if (LibraryBrowser.isFailed(track)) {
            DownloadFailureDialog.show(this, track,
                    () -> confirmDelete(Collections.singletonList(track)));
        } else if (!PlaybackQueueController.isPlayableLocal(track)) {
            showNotDownloadedDialog(track);
        } else {
            playList(adapter.getTracks(), track.id);
        }
    }

    @Override
    public void onTrackLongClick(Track track) {
        boolean playable = PlaybackQueueController.isPlayableLocal(track);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        List<Track> single = Collections.singletonList(track);
        if (playable) {
            labels.add(getString(R.string.ctx_play));
            actions.add(() -> playList(adapter.getTracks(), track.id));
            labels.add(getString(R.string.ctx_insert_after_current));
            actions.add(() -> playNext(single));
            labels.add(getString(R.string.ctx_add_queue));
            actions.add(() -> addToQueue(single));
        }
        labels.add(getString(track.favorite ? R.string.ctx_unfavorite : R.string.ctx_favorite));
        actions.add(() -> fileController.setFavorite(single, !track.favorite));
        labels.add(getString(R.string.ctx_move_to_playlist));
        actions.add(() -> showCopyDialog(single));
        labels.add(getString(R.string.library_open_playlist, track.playlistName));
        actions.add(() -> openPlaylist(track.playlistName));
        labels.add(getString(R.string.ctx_select));
        actions.add(() -> enterSelection(track));
        labels.add(getString(R.string.ctx_delete_from_device));
        actions.add(() -> confirmDelete(single));
        new MaterialAlertDialogBuilder(this)
                .setTitle(track.title)
                .setItems(labels.toArray(new String[0]),
                        (dialog, which) -> actions.get(which).run())
                .show();
    }

    @Override
    public void onSelectionChanged(int selectedCount) {
        selectionCount.setText(getString(R.string.selection_title, selectedCount));
        updatingSelectAll = true;
        selectAll.setChecked(selectedCount > 0 && selectedCount == adapter.getItemCount());
        updatingSelectAll = false;
    }

    // --------------------------------------------------------------- actions

    private void showNotDownloadedDialog(Track track) {
        MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(track.title)
                .setMessage(R.string.library_not_downloaded_message)
                .setNeutralButton(R.string.library_open_playlist_short,
                        (d, w) -> openPlaylist(track.playlistName))
                .setNegativeButton(R.string.library_download_now, (d, w) -> {
                    DownloadService.retryPersistedTrack(this, track);
                    Toast.makeText(this, R.string.refresh_started, Toast.LENGTH_SHORT).show();
                });
        if (TrackStreamResolver.canResolve(track)) {
            dialog.setPositiveButton(R.string.library_listen_online,
                    (d, w) -> streamTrack(track));
        }
        dialog.show();
    }

    private void streamTrack(Track track) {
        Toast.makeText(this, R.string.smart_stream_preparing, Toast.LENGTH_SHORT).show();
        streamResolver.resolve(track, new TrackStreamResolver.Callback() {
            @Override
            public void onResolved(String streamUrl) {
                if (!isDestroyed()) {
                    queueController.playResolvedStream(track, track.playlistName,
                            Collections.singletonList(track), streamUrl);
                }
            }

            @Override
            public void onFailure(Exception error) {
                if (!isDestroyed()) {
                    Toast.makeText(LibraryActivity.this, R.string.smart_stream_failed,
                            Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void playList(List<Track> tracks, long startTrackId) {
        List<Track> playable = playableOnly(tracks);
        if (playable.isEmpty()) {
            Toast.makeText(this, R.string.library_nothing_playable, Toast.LENGTH_SHORT).show();
            return;
        }
        long start = startTrackId >= 0 ? startTrackId : playable.get(0).id;
        if (!queueController.playTrackList(playable, start)) {
            Toast.makeText(this, R.string.library_player_not_ready, Toast.LENGTH_SHORT).show();
            return;
        }
        exitSelection();
    }

    private void addToQueue(List<Track> tracks) {
        List<Track> playable = playableOnly(tracks);
        if (playable.isEmpty() || controller == null) {
            Toast.makeText(this, R.string.library_nothing_playable, Toast.LENGTH_SHORT).show();
            return;
        }
        queueController.addToQueue(playable);
        exitSelection();
    }

    private void playNext(List<Track> tracks) {
        List<Track> playable = playableOnly(tracks);
        if (playable.isEmpty() || controller == null) {
            Toast.makeText(this, R.string.library_nothing_playable, Toast.LENGTH_SHORT).show();
            return;
        }
        queueController.insertAfterCurrent(playable);
        exitSelection();
    }

    private static List<Track> playableOnly(List<Track> tracks) {
        List<Track> playable = new ArrayList<>();
        for (Track track : tracks) {
            if (PlaybackQueueController.isPlayableLocal(track)) {
                playable.add(track);
            }
        }
        return playable;
    }

    private static boolean allFavorite(List<Track> tracks) {
        for (Track track : tracks) {
            if (!track.favorite) {
                return false;
            }
        }
        return true;
    }

    private void showBulkActions(List<Track> selected) {
        List<Track> failed = new ArrayList<>();
        for (Track track : selected) {
            if (LibraryBrowser.isFailed(track)) {
                failed.add(track);
            }
        }
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(getString(R.string.ctx_insert_after_current));
        actions.add(() -> playNext(selected));
        labels.add(getString(R.string.ctx_favorite));
        actions.add(() -> {
            fileController.setFavorite(selected, true);
            exitSelection();
        });
        labels.add(getString(R.string.ctx_unfavorite));
        actions.add(() -> {
            fileController.setFavorite(selected, false);
            exitSelection();
        });
        labels.add(getString(R.string.ctx_move_to_playlist));
        actions.add(() -> showCopyDialog(selected));
        if (!failed.isEmpty()) {
            labels.add(getString(R.string.bulk_retry_downloads));
            actions.add(() -> {
                for (Track track : failed) {
                    DownloadService.retryPersistedTrack(this, track);
                }
                Toast.makeText(this, getString(R.string.bulk_retry_started, failed.size()),
                        Toast.LENGTH_SHORT).show();
                exitSelection();
            });
        }
        labels.add(getString(R.string.ctx_delete_from_device));
        actions.add(() -> confirmDelete(selected));
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.selection_title, selected.size()))
                .setItems(labels.toArray(new String[0]),
                        (dialog, which) -> actions.get(which).run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showCopyDialog(List<Track> tracks) {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Track track : allTracks) {
            names.add(track.playlistName);
        }
        String[] playlists = names.toArray(new String[0]);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.move_dialog_title)
                .setItems(playlists, (dialog, which) -> copyTo(tracks, playlists[which]))
                .setNeutralButton(R.string.new_playlist_hint, (dialog, which) -> {
                    EditText input = new EditText(this);
                    input.setInputType(InputType.TYPE_CLASS_TEXT);
                    input.setHint(R.string.playlist_hint);
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.move_dialog_title)
                            .setView(DialogInputs.padded(input))
                            .setPositiveButton(android.R.string.ok, (d2, w2) -> {
                                String name = input.getText().toString().trim();
                                if (!name.isEmpty()) {
                                    copyTo(tracks, name);
                                }
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void copyTo(List<Track> tracks, String playlist) {
        String target = fileController.sanitizePlaylistName(playlist);
        // same choice as on the playlist screen: Viby only, or Viby and the YouTube playlist
        youtubeSyncController.offerTrackCopy(tracks, target,
                afterCopy -> fileController.copyTracks(tracks, target, afterCopy));
        exitSelection();
    }

    /**
     * Library rows stand for songs, so deleting removes every playlist copy of the file.
     * Linked YouTube playlists are not changed from here.
     */
    private void confirmDelete(List<Track> tracks) {
        List<Track> expanded = new ArrayList<>();
        Set<String> identities = new TreeSet<>();
        for (Track track : tracks) {
            identities.add(LibraryBrowser.identity(track));
        }
        for (Track track : allTracks) {
            if (identities.contains(LibraryBrowser.identity(track))) {
                expanded.add(track);
            }
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_selected_title)
                .setMessage(getString(R.string.library_delete_message, tracks.size(),
                        expanded.size()))
                .setPositiveButton(R.string.btn_delete, (d, w) -> {
                    fileController.deleteTracks(expanded, true,
                            () -> queueController.removeFromQueue(expanded));
                    exitSelection();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openPlayer() {
        startActivity(new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    private void openPlaylist(String playlist) {
        startActivity(new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_PLAYLIST, playlist)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    // ------------------------------------------------------------- selection

    private void enterSelection(@Nullable Track initial) {
        adapter.enterSelectionMode(initial);
        selectionBar.setVisibility(View.VISIBLE);
        updateBackCallback();
    }

    private void exitSelection() {
        if (!adapter.isSelectionMode()) {
            return;
        }
        adapter.exitSelectionMode();
        selectionBar.setVisibility(View.GONE);
        updatingSelectAll = true;
        selectAll.setChecked(false);
        updatingSelectAll = false;
        updateBackCallback();
    }

    // ------------------------------------------------------------------ sort

    private void showSortDialog() {
        android.widget.RadioGroup group = new android.widget.RadioGroup(this);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        group.setPadding(pad, pad / 2, pad, 0);
        for (int i = 0; i < SORTS.length; i++) {
            android.widget.RadioButton button = new android.widget.RadioButton(this);
            button.setId(i + 1);
            button.setText(SORT_LABELS[i]);
            button.setMinHeight((int) (44 * getResources().getDisplayMetrics().density));
            group.addView(button);
        }
        group.check(sort.ordinal() + 1);
        CheckBox reverseBox = new CheckBox(this);
        reverseBox.setText(R.string.sort_reverse);
        reverseBox.setChecked(reverse);
        android.widget.LinearLayout content = new android.widget.LinearLayout(this);
        content.setOrientation(android.widget.LinearLayout.VERTICAL);
        content.addView(group);
        android.widget.LinearLayout.LayoutParams params =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(pad, pad / 2, pad, 0);
        content.addView(reverseBox, params);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(content);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sort_title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    int checked = group.getCheckedRadioButtonId();
                    if (checked > 0) {
                        sort = SORTS[checked - 1];
                    }
                    reverse = reverseBox.isChecked();
                    persistState();
                    recompute();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // -------------------------------------------------------------- playback

    @Override
    protected void onStart() {
        super.onStart();
        SessionToken token = new SessionToken(this,
                new ComponentName(this, PlaybackService.class));
        ListenableFuture<MediaController> future =
                new MediaController.Builder(this, token).buildAsync();
        controllerFuture = future;
        future.addListener(() -> {
            if (controllerFuture != future) {
                return;
            }
            try {
                controller = future.get();
                controller.addListener(playerListener);
                queueController.connect(controller);
                miniPlayer.setPlayer(controller);
                highlightCurrent();
            } catch (Exception error) {
                Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    protected void onStop() {
        miniPlayer.setPlayer(null);
        if (controller != null) {
            controller.removeListener(playerListener);
            controller = null;
        }
        queueController.disconnect();
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
            controllerFuture = null;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(recompute);
        fastScroller.detachFromRecyclerView();
        streamResolver.destroy();
        youtubeSyncController.destroy();
        super.onDestroy();
    }

    private void highlightCurrent() {
        if (controller == null || controller.getCurrentMediaItem() == null) {
            adapter.setCurrentTrackId(-1);
            return;
        }
        try {
            adapter.setCurrentTrackId(Long.parseLong(controller.getCurrentMediaItem().mediaId));
        } catch (NumberFormatException ignored) {
            adapter.setCurrentTrackId(-1);
        }
    }
}
