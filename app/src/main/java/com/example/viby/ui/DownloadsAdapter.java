package com.example.viby.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.viby.R;
import com.example.viby.download.DownloadJob;

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
        private final TextView status;
        private final ProgressBar progress;

        Holder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.downloadTitle);
            status = itemView.findViewById(R.id.downloadStatus);
            progress = itemView.findViewById(R.id.downloadProgress);
        }

        void bind(DownloadJob job) {
            title.setText(job.title);
            status.setText(statusText(job));
            boolean active = job.status == DownloadJob.Status.DOWNLOADING
                    || job.status == DownloadJob.Status.PREPARING;
            progress.setVisibility(active ? View.VISIBLE : View.GONE);
            progress.setIndeterminate(job.status == DownloadJob.Status.PREPARING);
            progress.setProgress(job.progress);
        }

        private String statusText(DownloadJob job) {
            switch (job.status) {
                case QUEUED:
                    return "В очереди";
                case PREPARING:
                    return "Получаю информацию…";
                case DOWNLOADING:
                    if (job.isPlaylist && job.totalCount > 0) {
                        return "Трек " + job.currentIndex + " из " + job.totalCount
                                + " · " + job.progress + "%";
                    }
                    return job.progress + "%";
                case DONE:
                    if (job.isPlaylist && job.failedCount > 0) {
                        return "Готово, ошибок: " + job.failedCount;
                    }
                    return "Готово · " + job.playlistName;
                case FAILED:
                    return job.error != null ? job.error : "Ошибка";
                case CANCELED:
                    return "Отменено";
                default:
                    return "";
            }
        }
    }
}
