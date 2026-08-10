package com.example.viby.ui;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionCommand;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.viby.R;
import com.example.viby.playback.PlaybackService;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.ArrayList;
import java.util.List;

/** Popup editor for the queue that currently lives inside PlaybackService. */
public class PlaybackQueueDialogFragment extends BottomSheetDialogFragment {

    private PlayerViewModel viewModel;
    private MediaController controller;
    private PlaybackQueueAdapter adapter;
    private TextView empty;
    private RecyclerView list;
    private ItemTouchHelper itemTouchHelper;
    private boolean dragging;
    private boolean refreshPending;
    private boolean initialCurrentPositionApplied;

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onTimelineChanged(@NonNull Timeline timeline, int reason) {
            requestRefresh();
        }

        @Override
        public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
            requestRefresh();
        }

        @Override
        public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
            requestRefresh();
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_playback_queue, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        viewModel = new ViewModelProvider(requireActivity()).get(PlayerViewModel.class);
        empty = view.findViewById(R.id.playbackQueueEmpty);
        list = view.findViewById(R.id.playbackQueueList);
        initialCurrentPositionApplied = false;
        adapter = new PlaybackQueueAdapter(new PlaybackQueueAdapter.Listener() {
            @Override
            public void onPlay(int position) {
                if (controller != null && position < adapter.getItemCount()) {
                    controller.seekTo(adapter.getPlayerIndex(position), 0L);
                    controller.play();
                    refresh();
                }
            }

            @Override
            public void onRemove(int position) {
                if (controller != null && position < adapter.getItemCount()) {
                    controller.removeMediaItem(adapter.getPlayerIndex(position));
                    ((MainActivity) requireActivity()).markQueueCustomized();
                    list.post(PlaybackQueueDialogFragment.this::refresh);
                }
            }

            @Override
            public void onStartDrag(RecyclerView.ViewHolder holder) {
                if (itemTouchHelper != null) {
                    itemTouchHelper.startDrag(holder);
                }
            }
        });
        list.setAdapter(adapter);
        view.findViewById(R.id.resetPlaybackQueue).setOnClickListener(v -> {
            ((MainActivity) requireActivity()).resetPlaybackQueue();
            list.post(this::refresh);
        });

        itemTouchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean isLongPressDragEnabled() {
                return false;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder source,
                                  @NonNull RecyclerView.ViewHolder target) {
                int from = source.getBindingAdapterPosition();
                int to = target.getBindingAdapterPosition();
                if (controller == null || from == RecyclerView.NO_POSITION
                        || to == RecyclerView.NO_POSITION
                        || from >= controller.getMediaItemCount()
                        || to >= controller.getMediaItemCount()) {
                    return false;
                }
                dragging = true;
                adapter.move(from, to);
                if (controller.getShuffleModeEnabled()) {
                    Bundle args = new Bundle();
                    args.putIntArray(PlaybackService.EXTRA_SHUFFLE_ORDER,
                            adapter.getPlayerOrder());
                    controller.sendCustomCommand(new SessionCommand(
                            PlaybackService.ACTION_SET_SHUFFLE_ORDER, Bundle.EMPTY), args);
                } else {
                    controller.moveMediaItem(from, to);
                }
                ((MainActivity) requireActivity()).markQueueCustomized();
                return true;
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                dragging = false;
                if (refreshPending) {
                    refreshPending = false;
                    recyclerView.post(PlaybackQueueDialogFragment.this::refresh);
                }
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                // Swipe is intentionally disabled; deletion has an explicit button.
            }
        });
        itemTouchHelper.attachToRecyclerView(list);

        viewModel.controller.observe(getViewLifecycleOwner(), newController -> {
            if (controller != null) {
                controller.removeListener(playerListener);
            }
            controller = newController;
            if (controller != null) {
                controller.addListener(playerListener);
            }
            refresh();
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog instanceof BottomSheetDialog) {
            View sheet = ((BottomSheetDialog) dialog)
                    .findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) {
                int height = (int) (getResources().getDisplayMetrics().heightPixels * 0.78f);
                sheet.getLayoutParams().height = height;
                sheet.requestLayout();
                BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(sheet);
                behavior.setSkipCollapsed(true);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        }
    }

    @Override
    public void onDestroyView() {
        if (controller != null) {
            controller.removeListener(playerListener);
            controller = null;
        }
        super.onDestroyView();
    }

    private void refresh() {
        if (adapter == null || empty == null) {
            return;
        }
        List<PlaybackQueueAdapter.QueueItem> items = new ArrayList<>();
        String currentId = null;
        if (controller != null) {
            appendItemsInPlaybackOrder(items);
            if (controller.getCurrentMediaItem() != null) {
                currentId = controller.getCurrentMediaItem().mediaId;
            }
        }
        adapter.submit(items, currentId);
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        scrollCurrentItemToTopOnce(items);
    }

    /**
     * Keeps the full editable queue, but opens it at the item that is playing now.
     * The position is resolved through the player's index rather than mediaId so
     * duplicate tracks in the queue still scroll to the correct occurrence.
     */
    private void scrollCurrentItemToTopOnce(
            List<PlaybackQueueAdapter.QueueItem> items) {
        if (initialCurrentPositionApplied || controller == null || list == null) {
            return;
        }
        int currentPlayerIndex = controller.getCurrentMediaItemIndex();
        if (currentPlayerIndex == androidx.media3.common.C.INDEX_UNSET) {
            return;
        }
        int currentDisplayPosition = RecyclerView.NO_POSITION;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).playerIndex == currentPlayerIndex) {
                currentDisplayPosition = i;
                break;
            }
        }
        if (currentDisplayPosition == RecyclerView.NO_POSITION) {
            return;
        }

        initialCurrentPositionApplied = true;
        int position = currentDisplayPosition;
        list.post(() -> {
            if (list == null) {
                return;
            }
            RecyclerView.LayoutManager manager = list.getLayoutManager();
            if (manager instanceof LinearLayoutManager) {
                ((LinearLayoutManager) manager).scrollToPositionWithOffset(position, 0);
            } else {
                list.scrollToPosition(position);
            }
        });
    }

    private void appendItemsInPlaybackOrder(
            List<PlaybackQueueAdapter.QueueItem> items) {
        int itemCount = controller.getMediaItemCount();
        Timeline timeline = controller.getCurrentTimeline();
        if (controller.getShuffleModeEnabled()
                && timeline.getWindowCount() == itemCount) {
            int index = timeline.getFirstWindowIndex(/* shuffleModeEnabled= */ true);
            while (index != androidx.media3.common.C.INDEX_UNSET) {
                items.add(new PlaybackQueueAdapter.QueueItem(
                        controller.getMediaItemAt(index), index));
                index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF,
                        /* shuffleModeEnabled= */ true);
            }
            if (items.size() == itemCount) {
                return;
            }
            items.clear();
        }
        for (int i = 0; i < itemCount; i++) {
            items.add(new PlaybackQueueAdapter.QueueItem(
                    controller.getMediaItemAt(i), i));
        }
    }

    private void requestRefresh() {
        if (dragging) {
            refreshPending = true;
        } else if (list != null) {
            list.post(this::refresh);
        }
    }
}
