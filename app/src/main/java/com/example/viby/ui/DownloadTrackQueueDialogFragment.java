package com.example.viby.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

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

        adapter = new DownloadTrackQueueAdapter(holder -> {
            if (itemTouchHelper != null) {
                itemTouchHelper.startDrag(holder);
            }
        });
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
    }
}
