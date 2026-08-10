package com.example.viby.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.viby.R;
import com.example.viby.download.DownloadJob;
import com.example.viby.download.DownloadService;
import com.example.viby.util.Formats;

import java.util.ArrayList;
import java.util.List;

public class DownloadsAdapter extends RecyclerView.Adapter<DownloadsAdapter.Holder> {

    interface Listener {
        void onStartDrag(RecyclerView.ViewHolder holder);

        void onOpenTracks(long jobId);
    }

    private final List<DownloadJob> jobs = new ArrayList<>();
    private final Listener listener;

    DownloadsAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<DownloadJob> newJobs) {
        jobs.clear();
        jobs.addAll(newJobs);
        //noinspection NotifyDataSetChanged — список маленький, живёт только пока идут загрузки
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_download, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(jobs.get(position));
    }

    @Override
    public int getItemCount() {
        return jobs.size();
    }

    boolean canMove(int position) {
        return position >= 0 && position < jobs.size() && jobs.get(position).isActive();
    }

    void move(int from, int to) {
        if (from == to || !canMove(from) || !canMove(to)) {
            return;
        }
        DownloadJob moved = jobs.remove(from);
        jobs.add(to, moved);
        notifyItemMoved(from, to);
    }

    long getJobId(int position) {
        return jobs.get(position).id;
    }

    final class Holder extends RecyclerView.ViewHolder {
        private final TextView title;
        private final TextView track;
        private final TextView status;
        private final ProgressBar progress;
        private final ImageButton pauseButton;
        private final ImageButton cancelButton;
        private final ImageButton tracksButton;
        private final ImageView dragHandle;

        Holder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.downloadTitle);
            track = itemView.findViewById(R.id.downloadTrack);
            status = itemView.findViewById(R.id.downloadStatus);
            progress = itemView.findViewById(R.id.downloadProgress);
            pauseButton = itemView.findViewById(R.id.downloadPause);
            cancelButton = itemView.findViewById(R.id.downloadCancel);
            tracksButton = itemView.findViewById(R.id.downloadTracks);
            dragHandle = itemView.findViewById(R.id.downloadDrag);
        }

        void bind(DownloadJob job) {
            title.setText(job.title);

            // какой трек качается прямо сейчас (для плейлиста отличается от названия задания)
            boolean showTrack = job.currentTrackTitle != null
                    && (job.status == DownloadJob.Status.DOWNLOADING
                            || job.status == DownloadJob.Status.PAUSED)
                    && job.isPlaylist;
            if (showTrack) {
                track.setVisibility(View.VISIBLE);
                track.setMaxLines(1);
                track.setText(job.currentTrackTitle);
            } else if (job.status == DownloadJob.Status.DONE
                    && !job.failedTitles.isEmpty()) {
                // после завершения показываем, какие именно треки не скачались
                StringBuilder failed = new StringBuilder(
                        itemView.getContext().getString(R.string.failed_tracks_header));
                synchronized (job.failedTitles) {
                    for (String title : job.failedTitles) {
                        failed.append("\n• ").append(title);
                    }
                }
                track.setVisibility(View.VISIBLE);
                track.setMaxLines(20);
                track.setText(failed.toString());
            } else {
                track.setVisibility(View.GONE);
            }

            status.setText(statusText(job));

            boolean active = job.status == DownloadJob.Status.DOWNLOADING
                    || job.status == DownloadJob.Status.PREPARING
                    || job.status == DownloadJob.Status.PAUSED;
            progress.setVisibility(active ? View.VISIBLE : View.GONE);
            progress.setIndeterminate(job.status == DownloadJob.Status.PREPARING);
            progress.setProgress(job.progress);

            boolean paused = job.status == DownloadJob.Status.PAUSED || job.pauseRequested;
            pauseButton.setVisibility(job.isActive() ? View.VISIBLE : View.GONE);
            pauseButton.setImageResource(paused ? R.drawable.ic_play : R.drawable.ic_pause);
            pauseButton.setOnClickListener(v -> DownloadService.sendJobAction(
                    v.getContext(), paused
                            ? DownloadService.ACTION_RESUME_JOB
                            : DownloadService.ACTION_PAUSE_JOB, job.id));

            cancelButton.setVisibility(job.isActive() ? View.VISIBLE : View.GONE);
            cancelButton.setOnClickListener(v -> DownloadService.sendJobAction(
                    v.getContext(), DownloadService.ACTION_CANCEL_JOB, job.id));
            tracksButton.setVisibility(job.isPlaylist ? View.VISIBLE : View.GONE);
            tracksButton.setOnClickListener(v -> listener.onOpenTracks(job.id));
            dragHandle.setVisibility(job.isActive() ? View.VISIBLE : View.INVISIBLE);
            dragHandle.setOnClickListener(v -> {
                // Accessibility click target; dragging starts from touch-down below.
            });
            dragHandle.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN
                        && job.isActive()) {
                    listener.onStartDrag(this);
                } else if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                    v.performClick();
                }
                return false;
            });
        }

        private String statusText(DownloadJob job) {
            android.content.Context context = itemView.getContext();
            switch (job.status) {
                case QUEUED:
                    return context.getString(R.string.status_queued);
                case PREPARING:
                    return context.getString(R.string.status_fetching_info);
                case PAUSED:
                case DOWNLOADING:
                    StringBuilder sb = new StringBuilder();
                    if (job.isPlaylist && job.totalCount > 0) {
                        sb.append(context.getString(
                                R.string.notif_download_playlist_progress,
                                job.currentIndex, job.totalCount));
                    }
                    if (job.totalBytes > 0) {
                        if (sb.length() > 0) {
                            sb.append(" · ");
                        }
                        sb.append(Formats.size(context, job.downloadedBytes))
                                .append(" / ")
                                .append(Formats.size(context, job.totalBytes));
                    }
                    if (sb.length() > 0) {
                        sb.append(" · ");
                    }
                    sb.append(job.progress).append("%");
                    if (job.status == DownloadJob.Status.PAUSED) {
                        sb.append(" · ").append(context.getString(R.string.status_paused));
                    }
                    return sb.toString();
                case DONE:
                    if (job.isPlaylist && job.failedCount > 0) {
                        return context.getString(R.string.status_done_errors,
                                job.failedCount);
                    }
                    return context.getString(R.string.status_done, job.playlistName);
                case FAILED:
                    return job.error != null ? job.error
                            : context.getString(R.string.status_failed);
                case CANCELED:
                    return context.getString(R.string.status_canceled);
                default:
                    return "";
            }
        }
    }
}
