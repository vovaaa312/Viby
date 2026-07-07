package com.example.viby.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.example.viby.R;
import com.example.viby.download.DownloadService;
import com.example.viby.util.StorageHelper;
import com.example.viby.util.YtCookies;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Плейлисты YouTube-аккаунта (включая закрытые): yt-dlp с куками читает
 * youtube.com/feed/playlists. Тап по плейлисту — скачать его целиком.
 */
public class YoutubePlaylistsActivity extends AppCompatActivity {

    private static final String FEED_URL = "https://www.youtube.com/feed/playlists";

    /** Название + ссылка одного плейлиста аккаунта. */
    private static class YtPlaylist {
        final String title;
        final String url;

        YtPlaylist(String title, String url) {
            this.title = title;
            this.url = url;
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ProgressBar progress;
    private View errorBox;
    private Adapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_playlists);

        MaterialToolbar toolbar = findViewById(R.id.ytPlaylistsToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        progress = findViewById(R.id.ytPlaylistsProgress);
        errorBox = findViewById(R.id.ytPlaylistsError);
        findViewById(R.id.ytPlaylistsRelogin).setOnClickListener(v -> {
            startActivity(new Intent(this, YoutubeLoginActivity.class));
            finish();
        });

        RecyclerView list = findViewById(R.id.ytPlaylistsList);
        adapter = new Adapter();
        list.setAdapter(adapter);

        load();
    }

    private void load() {
        progress.setVisibility(View.VISIBLE);
        errorBox.setVisibility(View.GONE);
        executor.execute(() -> {
            List<YtPlaylist> playlists = new ArrayList<>();
            try {
                YoutubeDLRequest request = new YoutubeDLRequest(FEED_URL);
                request.addOption("--flat-playlist");
                request.addOption("--dump-single-json");
                request.addOption("--no-warnings");
                request.addOption("--cookies", YtCookies.file(this).getAbsolutePath());
                YoutubeDLResponse response =
                        YoutubeDL.getInstance().execute(request, "yt-playlists", null);
                JSONObject root = new JSONObject(response.getOut());
                JSONArray entries = root.optJSONArray("entries");
                if (entries != null) {
                    for (int i = 0; i < entries.length(); i++) {
                        JSONObject entry = entries.getJSONObject(i);
                        String title = entry.optString("title", "");
                        String url = entry.optString("url", "");
                        if (url.isEmpty()) {
                            String id = entry.optString("id", "");
                            if (!id.isEmpty()) {
                                url = "https://www.youtube.com/playlist?list=" + id;
                            }
                        }
                        if (!title.isEmpty() && !url.isEmpty()) {
                            playlists.add(new YtPlaylist(title, url));
                        }
                    }
                }
                mainHandler.post(() -> showPlaylists(playlists));
            } catch (Exception e) {
                mainHandler.post(this::showError);
            }
        });
    }

    private void showPlaylists(List<YtPlaylist> playlists) {
        progress.setVisibility(View.GONE);
        if (playlists.isEmpty()) {
            showError();
            return;
        }
        adapter.submit(playlists);
    }

    private void showError() {
        progress.setVisibility(View.GONE);
        errorBox.setVisibility(View.VISIBLE);
    }

    /** Подтверждение с именем папки, потом — в обычный конвейер загрузки. */
    private void confirmDownload(YtPlaylist playlist) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(StorageHelper.sanitize(playlist.title));
        new MaterialAlertDialogBuilder(this)
                .setTitle(playlist.title)
                .setMessage(R.string.playlist_hint)
                .setView(input)
                .setPositiveButton(R.string.btn_download, (dialog, which) -> {
                    String folder = input.getText().toString().trim();
                    DownloadService.enqueue(this, playlist.url,
                            folder.isEmpty() ? null : folder, true);
                    Toast.makeText(this, R.string.download_queued,
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.Holder> {

        private final List<YtPlaylist> items = new ArrayList<>();

        void submit(List<YtPlaylist> playlists) {
            items.clear();
            items.addAll(playlists);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_yt_playlist, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            YtPlaylist playlist = items.get(position);
            holder.name.setText(playlist.title);
            holder.itemView.setOnClickListener(v -> confirmDownload(playlist));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView name;

            Holder(@NonNull View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.ytPlaylistName);
            }
        }
    }
}
