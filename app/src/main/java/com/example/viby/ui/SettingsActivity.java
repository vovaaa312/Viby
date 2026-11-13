package com.example.viby.ui;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.viby.R;
import com.example.viby.update.AppUpdateManager;
import com.google.android.material.appbar.MaterialToolbar;

/**
 * All settings in one place: a short list of sections, each showing its current values,
 * that open their own page (Android settings guidelines: values visible at a glance,
 * on/off as switches, single choices in dialogs, few items per page). Every page is its
 * own activity, so it opens with the same system transition as the other screens.
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String EXTRA_SCREEN = "screen";

    @Nullable private AppUpdateManager appUpdateManager;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        SettingsFragment.Screen screen = SettingsFragment.Screen.ROOT;
        String requested = getIntent().getStringExtra(EXTRA_SCREEN);
        if (requested != null) {
            screen = SettingsFragment.Screen.valueOf(requested);
        }
        MaterialToolbar toolbar = findViewById(R.id.settingsToolbar);
        toolbar.setTitle(screen.title);
        toolbar.setNavigationOnClickListener(v -> finish());
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settingsContainer, SettingsFragment.create(screen))
                    .commit();
        }
        if (screen == SettingsFragment.Screen.ABOUT) {
            // a manual check only; MainActivity, still open below, finishes the download
            appUpdateManager = new AppUpdateManager(this);
        }
    }

    void openScreen(SettingsFragment.Screen screen) {
        startActivity(new Intent(this, SettingsActivity.class)
                .putExtra(EXTRA_SCREEN, screen.name()));
    }

    void checkForAppUpdate() {
        if (appUpdateManager != null) {
            appUpdateManager.checkManually();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (appUpdateManager != null) {
            appUpdateManager.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (appUpdateManager != null) {
            appUpdateManager.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (appUpdateManager != null) {
            appUpdateManager.close();
            appUpdateManager = null;
        }
        super.onDestroy();
    }
}
