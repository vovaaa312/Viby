package com.example.viby.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.example.viby.R;
import com.example.viby.VibyApp;
import com.example.viby.download.DownloadService;
import com.google.android.material.textfield.TextInputLayout;

public class DownloadActivity extends AppCompatActivity {

    private TextInputLayout urlInputLayout;
    private EditText urlInput;
    private EditText playlistInput;
    private CheckBox wholePlaylistCheck;
    private ItemTouchHelper itemTouchHelper;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_download);
        com.google.android.material.appbar.MaterialToolbar toolbar =
                findViewById(R.id.downloadToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        urlInputLayout = findViewById(R.id.urlInputLayout);
        urlInput = findViewById(R.id.urlInput);
        playlistInput = findViewById(R.id.playlistInput);
        wholePlaylistCheck = findViewById(R.id.wholePlaylistCheck);
        Button downloadButton = findViewById(R.id.downloadButton);
        RecyclerView downloadsList = findViewById(R.id.downloadsList);

        DownloadsAdapter adapter = new DownloadsAdapter(new DownloadsAdapter.Listener() {
            @Override
            public void onStartDrag(RecyclerView.ViewHolder holder) {
                if (itemTouchHelper != null) {
                    itemTouchHelper.startDrag(holder);
                }
            }

            @Override
            public void onOpenTracks(long jobId) {
                DownloadTrackQueueDialogFragment.newInstance(jobId)
                        .show(getSupportFragmentManager(), "download-track-queue");
            }
        });
        downloadsList.setAdapter(adapter);
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
                long movedId = adapter.getJobId(from);
                long targetId = adapter.getJobId(to);
                adapter.move(from, to);
                DownloadService.moveJob(DownloadActivity.this, movedId, targetId);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder,
                                  int direction) {
                // Swipe is disabled; cancellation has an explicit button.
            }
        });
        itemTouchHelper.attachToRecyclerView(downloadsList);
        DownloadService.getJobs().observe(this, adapter::submit);

        urlInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updatePlaylistCheckbox(s.toString());
            }
        });

        downloadButton.setOnClickListener(v -> startDownload());

        handleSendIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleSendIntent(intent);
    }

    /** Ссылка, расшаренная из приложения YouTube (ACTION_SEND). */
    private void handleSendIntent(Intent intent) {
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                String url = extractUrl(text);
                urlInput.setText(url);
                updatePlaylistCheckbox(url);
            }
        }
    }

    private void updatePlaylistCheckbox(String url) {
        boolean hasList = url.contains("list=") || url.contains("/playlist");
        wholePlaylistCheck.setVisibility(hasList ? View.VISIBLE : View.GONE);
        if (hasList) {
            // watch?v=..&list=.. — скорее всего хотят один трек; чистая ссылка на плейлист — весь
            wholePlaylistCheck.setChecked(!url.contains("v=") || url.contains("/playlist"));
        }
    }

    private void startDownload() {
        String url = urlInput.getText().toString().trim();
        if (url.isEmpty()) {
            urlInputLayout.setError(getString(R.string.error_empty_url));
            return;
        }
        if (!isYoutubeUrl(url)) {
            urlInputLayout.setError(getString(R.string.error_invalid_url));
            return;
        }
        urlInputLayout.setError(null);

        VibyApp app = (VibyApp) getApplication();
        if (app.getEngineState().getValue() == VibyApp.EngineState.FAILED) {
            Toast.makeText(this, R.string.ytdlp_not_ready, Toast.LENGTH_LONG).show();
            return;
        }

        String playlist = playlistInput.getText().toString().trim();
        boolean isPlaylist = wholePlaylistCheck.getVisibility() == View.VISIBLE
                && wholePlaylistCheck.isChecked();

        DuplicateTrackPrompt.enqueue(this, url,
                playlist.isEmpty() ? null : playlist, isPlaylist,
                () -> urlInput.setText(""));
    }

    private static boolean isYoutubeUrl(String url) {
        return url.contains("youtube.com/") || url.contains("youtu.be/");
    }

    /** Из текста шаринга ("Смотри! https://youtu.be/…") вытаскиваем саму ссылку. */
    private static String extractUrl(String text) {
        for (String token : text.split("\\s+")) {
            if (token.startsWith("http://") || token.startsWith("https://")) {
                return token;
            }
        }
        return text.trim();
    }
}
