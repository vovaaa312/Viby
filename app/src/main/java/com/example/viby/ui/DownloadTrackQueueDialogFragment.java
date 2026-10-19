package com.example.viby.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.example.viby.R;
import com.example.viby.download.DownloadJob;
import com.example.viby.download.DownloadService;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.ArrayList;
import java.util.List;

public final class DownloadTrackQueueDialogFragment extends BottomSheetDialogFragment {

    private static final String ARG_JOB_ID = "job_id";

    private long jobId;
    private DownloadTrackQueueAdapter adapter;
    private ItemTouchHelper itemTouchHelper;
    private TextView title;
    private TextView empty;
    private Button retryAll;

    static DownloadTrackQueueDialogFragment newInstance(long jobId) {
        DownloadTrackQueueDialogFragment fragment =
                new DownloadTrackQueueDialogFragment();
        Bundle args = new Bundle();
        args.putLong(ARG_JOB_ID, jobId);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_download_track_queue,
                container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        jobId = requireArguments().getLong(ARG_JOB_ID);
        title = view.findViewById(R.id.downloadTrackQueueTitle);
        empty = view.findViewById(R.id.downloadTrackQueueEmpty);
        RecyclerView list = view.findViewById(R.id.downloadTrackQueueList);

        adapter = new DownloadTrackQueueAdapter(new DownloadTrackQueueAdapter.Listener() {
            @Override
            public void onStartDrag(RecyclerView.ViewHolder holder) {
                if (itemTouchHelper != null) {
                    itemTouchHelper.startDrag(holder);
                }
            }

            @Override
            public void onFailedTrack(DownloadJob.TrackItem item) {
                showFailure(item);
            }
        });
        retryAll = view.findViewById(R.id.downloadRetryAll);
        retryAll.setOnClickListener(v -> DownloadService.retryAllFailed(
                requireContext(), jobId));
        list.setAdapter(adapter);
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
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION
                        || !adapter.canMove(from) || !adapter.canMove(to)) {
                    return false;
                }
                String movedId = adapter.getVideoId(from);
                String targetId = adapter.getVideoId(to);
                adapter.move(from, to);
                DownloadService.moveTrack(requireContext(), jobId, movedId, targetId);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder,
                                  int direction) {
                // Swipe is intentionally disabled.
            }
        });
        itemTouchHelper.attachToRecyclerView(list);
        DownloadService.getJobs().observe(getViewLifecycleOwner(), this::refresh);
    }

    private void refresh(List<DownloadJob> jobs) {
        DownloadJob selected = null;
        for (DownloadJob job : jobs) {
            if (job.id == jobId) {
                selected = job;
                break;
            }
        }
        if (selected == null) {
            dismissAllowingStateLoss();
            return;
        }
        title.setText(selected.title);
        List<DownloadJob.TrackItem> snapshot;
        synchronized (selected.tracks) {
            snapshot = new ArrayList<>(selected.tracks);
        }
        adapter.submit(snapshot);
        empty.setVisibility(snapshot.isEmpty() ? View.VISIBLE : View.GONE);
        retryAll.setVisibility(selected.failedCount > 0 ? View.VISIBLE : View.GONE);
    }

    private void showFailure(DownloadJob.TrackItem item) {
        String reason = item.failureMessage != null ? item.failureMessage
                : getString(R.string.download_error_unknown);
        String diagnostic = getString(R.string.download_diagnostic_template,
                item.title, item.videoId, reason,
                item.failureCode != null ? item.failureCode : "UNKNOWN",
                item.attemptCount,
                item.selectedFormat != null ? item.selectedFormat : "—",
                item.requiresAuthorization ? "yes" : "no");
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.download_failure_reason_title)
                .setMessage(reason)
                .setPositiveButton(R.string.download_retry_one, (dialog, which) ->
                        DownloadService.retryTrack(requireContext(), jobId, item.videoId))
                .setNegativeButton(R.string.download_remove_failed, (dialog, which) ->
                        DownloadService.removeFailedTrack(
                                requireContext(), jobId, item.videoId))
                .setNeutralButton(R.string.download_copy_diagnostic, (dialog, which) -> {
                    android.content.ClipboardManager clipboard = requireContext()
                            .getSystemService(android.content.ClipboardManager.class);
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                            "Viby download diagnostic", diagnostic));
                    android.widget.Toast.makeText(requireContext(),
                            R.string.download_diagnostic_copied,
                            android.widget.Toast.LENGTH_SHORT).show();
                })
                .show();
    }
}
