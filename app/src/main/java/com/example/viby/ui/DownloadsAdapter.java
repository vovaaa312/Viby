package com.example.viby.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
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

    private final List<DownloadJob> jobs = new ArrayList<>();

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

    static class Holder extends RecyclerView.ViewHolder {
        private final TextView title;
        private final TextView track;
        private final TextView status;
        private final ProgressBar progress;
        private final ImageButton pauseButton;
        private final ImageButton cancelButton;

        Holder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.downloadTitle);
            track = itemView.findViewById(R.id.downloadTrack);
            status = itemView.findViewById(R.id.downloadStatus);
            progress = itemView.findViewById(R.id.downloadProgress);
            pauseButton = itemView.findViewById(R.id.downloadPause);
            cancelButton = itemView.findViewById(R.id.downloadCancel);
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
            pauseButton.setOnClickListener(v -> DownloadService.sendAction(
                    v.getContext(), paused
                            ? DownloadService.ACTION_RESUME_ALL
                            : DownloadService.ACTION_PAUSE_ALL));

            cancelButton.setVisibility(job.isActive() ? View.VISIBLE : View.GONE);
            cancelButton.setOnClickListener(v -> DownloadService.sendAction(
                    v.getContext(), DownloadService.ACTION_CANCEL_ALL));
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
