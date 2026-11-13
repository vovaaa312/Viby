package com.example.viby.ui;

import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.viby.R;
import com.example.viby.playback.EqFx;
import com.example.viby.playback.VolumeNormalization;
import com.example.viby.ui.widget.EqualizerCurveView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.List;
import java.util.Locale;

/**
 * Звуковые эффекты: компактный 20-полосный эквалайзер на частотной сетке AIMP
 * со встроенными и пользовательскими пресетами.
 */
public class EqualizerActivity extends AppCompatActivity {

    /** Ползунок: 0..300 → -15.0..+15.0 дБ (шаг 0.1). */
    private static final int SLIDER_MAX = (int) (EqFx.MAX_GAIN_DB * 2 * 10);
    private static final long AVAILABILITY_RETRY_MS = 100L;
    private static final int MAX_AVAILABILITY_RETRIES = 30;

    private Button presetButton;
    private Button savePresetButton;
    private TextView preampValueLabel;
    private TextView unavailable;
    private SeekBar preampSlider;
    private MaterialSwitch eqSwitch;
    private LinearLayout bandsContainer;
    private EqualizerCurveView curveView;
    private boolean controlsInitialized;
    private int availabilityRetries;
    private final Runnable availabilityCheck = this::checkAvailability;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_equalizer);

        MaterialToolbar toolbar = findViewById(R.id.eqToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        bandsContainer = findViewById(R.id.bandsContainer);
        presetButton = findViewById(R.id.presetButton);
        savePresetButton = findViewById(R.id.savePresetButton);
        eqSwitch = findViewById(R.id.eqSwitch);
        unavailable = findViewById(R.id.eqUnavailable);
        setUpNormalization();
    }

    @Override
    protected void onResume() {
        super.onResume();
        availabilityRetries = 0;
        checkAvailability();
        refreshNormalizationStatus();
    }

    // ---------------------------------------------------------- normalization

    private static final VolumeNormalization.Level[] LEVELS = VolumeNormalization.Level.values();
    private static final int[] LEVEL_LABELS = {
            R.string.normalization_level_quiet,
            R.string.normalization_level_normal,
            R.string.normalization_level_loud,
    };

    private MaterialSwitch normalizationSwitch;
    private Button normalizationLevel;
    private TextView normalizationStatus;

    private void setUpNormalization() {
        normalizationSwitch = findViewById(R.id.normalizationSwitch);
        normalizationLevel = findViewById(R.id.normalizationLevel);
        normalizationStatus = findViewById(R.id.normalizationStatus);
        normalizationSwitch.setChecked(VolumeNormalization.isEnabled(this));
        normalizationSwitch.setOnCheckedChangeListener((button, checked) -> {
            VolumeNormalization.setEnabled(this, checked);
            refreshNormalizationStatus();
        });
        normalizationLevel.setOnClickListener(v -> {
            String[] labels = new String[LEVELS.length];
            for (int i = 0; i < LEVELS.length; i++) {
                labels[i] = getString(LEVEL_LABELS[i]);
            }
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.normalization_level_title)
                    .setSingleChoiceItems(labels,
                            VolumeNormalization.level(this).ordinal(), (dialog, which) -> {
                                VolumeNormalization.setLevel(this, LEVELS[which]);
                                refreshNormalizationStatus();
                                dialog.dismiss();
                            })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
    }

    private void refreshNormalizationStatus() {
        boolean enabled = VolumeNormalization.isEnabled(this);
        normalizationLevel.setText(LEVEL_LABELS[VolumeNormalization.level(this).ordinal()]);
        normalizationLevel.setEnabled(enabled);
        if (!enabled) {
            normalizationStatus.setText(R.string.normalization_status_off);
            return;
        }
        com.example.viby.data.VibyDatabase.dbExecutor.execute(() -> {
            com.example.viby.data.TrackDao dao =
                    com.example.viby.data.VibyDatabase.get(this).trackDao();
            int total = dao.countDownloadedFiles();
            int scanned = dao.countLoudnessScannedFiles();
            boolean charging = com.example.viby.playback.LoudnessScanner.isCharging(this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                normalizationStatus.setText(scanned >= total
                        ? getString(R.string.normalization_status_done, total)
                        : getString(charging
                                ? R.string.normalization_status
                                : R.string.normalization_status_waiting, scanned, total));
            });
        });
        com.example.viby.playback.LoudnessScanner.requestScan(this);
    }

    @Override
    protected void onPause() {
        unavailable.removeCallbacks(availabilityCheck);
        super.onPause();
    }

    private void checkAvailability() {
        unavailable.removeCallbacks(availabilityCheck);
        if (EqFx.isAvailable()) {
            unavailable.setVisibility(View.GONE);
            setHeaderControlsEnabled(true);
            if (!controlsInitialized) {
                initializeControls();
            } else {
                eqSwitch.setChecked(EqFx.isEnabled());
                refreshSliders();
            }
            return;
        }

        setHeaderControlsEnabled(false);
        if (availabilityRetries++ < MAX_AVAILABILITY_RETRIES) {
            // A theme switch can recreate the activity just before PlaybackService
            // reconnects the audio effect. Do not show a false error during that window.
            unavailable.setVisibility(View.GONE);
            unavailable.postDelayed(availabilityCheck, AVAILABILITY_RETRY_MS);
        } else {
            unavailable.setVisibility(View.VISIBLE);
        }
    }

    private void initializeControls() {
        controlsInitialized = true;
        eqSwitch.setChecked(EqFx.isEnabled());
        eqSwitch.setOnCheckedChangeListener((btn, checked) -> EqFx.setEnabled(checked));

        presetButton.setOnClickListener(v -> showPresetDialog());
        savePresetButton.setOnClickListener(v -> showSavePresetDialog());
        updatePresetButton();

        buildPreamp(bandsContainer);
        buildCurve(bandsContainer);
    }

    private void setHeaderControlsEnabled(boolean enabled) {
        eqSwitch.setEnabled(enabled);
        presetButton.setEnabled(enabled);
        savePresetButton.setEnabled(enabled);
    }

    private void buildPreamp(LinearLayout container) {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (8 * density), 0, (int) (10 * density));

        TextView label = new TextView(this);
        label.setText(R.string.eq_preamp);
        label.setGravity(Gravity.END);
        label.setTextSize(13);
        row.addView(label, new LinearLayout.LayoutParams(
                (int) (96 * density), LinearLayout.LayoutParams.WRAP_CONTENT));

        preampSlider = new SeekBar(this);
        preampSlider.setMax(SLIDER_MAX);
        preampSlider.setProgress(gainToProgress(EqFx.getPreampGainDb()));
        preampSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    EqFx.setPreampGainDb(progressToGain(progress));
                }
                updatePreampValueLabel();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        LinearLayout.LayoutParams sliderParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        sliderParams.setMarginStart((int) (8 * density));
        row.addView(preampSlider, sliderParams);

        preampValueLabel = new TextView(this);
        preampValueLabel.setGravity(Gravity.END);
        preampValueLabel.setTextSize(13);
        row.addView(preampValueLabel, new LinearLayout.LayoutParams(
                (int) (64 * density), LinearLayout.LayoutParams.WRAP_CONTENT));

        container.addView(row);
        updatePreampValueLabel();
    }

    private void buildCurve(LinearLayout container) {
        int bands = EqFx.getBandCount();
        float density = getResources().getDisplayMetrics().density;
        float[] gains = new float[bands];
        for (int band = 0; band < bands; band++) {
            gains[band] = EqFx.getBandGainDb(band);
        }
        curveView = new EqualizerCurveView(this);
        curveView.setGains(gains);
        curveView.setOnBandGainChangeListener((band, gainDb) -> {
            EqFx.setBandGainDb(band, gainDb);
            updatePresetButton();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = (int) (4 * density);
        container.addView(curveView, params);
    }

    private void showPresetDialog() {
        List<String> namesList = EqFx.getPresetNames();
        String[] names = namesList.toArray(new String[0]);
        String current = EqFx.getPresetName();
        int checked = namesList.indexOf(current);
        final int[] selected = {checked};
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.eq_preset_button)
                .setSingleChoiceItems(names, checked,
                        (dialog, which) -> selected[0] = which)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (selected[0] >= 0) {
                        EqFx.applyPreset(names[selected[0]]);
                        refreshSliders();
                        updatePresetButton();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showSavePresetDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.eq_preset_name_hint);
        String current = EqFx.getPresetName();
        if (!current.isEmpty()) {
            input.setText(current);
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.eq_save_preset)
                .setView(DialogInputs.padded(input))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        EqFx.saveCurrentAsPreset(name);
                        updatePresetButton();
                        Toast.makeText(this, R.string.eq_preset_saved,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void refreshSliders() {
        preampSlider.setProgress(gainToProgress(EqFx.getPreampGainDb()));
        updatePreampValueLabel();
        float[] gains = new float[EqFx.getBandCount()];
        for (int band = 0; band < gains.length; band++) {
            gains[band] = EqFx.getBandGainDb(band);
        }
        curveView.setGains(gains);
    }

    private void updatePreampValueLabel() {
        preampValueLabel.setText(String.format(Locale.US, "%+.1f dB",
                EqFx.getPreampGainDb()));
    }

    private void updatePresetButton() {
        String preset = EqFx.getPresetName();
        presetButton.setText(preset.isEmpty()
                ? getString(R.string.eq_custom) : preset);
    }

    private static int gainToProgress(float db) {
        return Math.round((db + EqFx.MAX_GAIN_DB) * 10);
    }

    private static float progressToGain(int progress) {
        return progress / 10f - EqFx.MAX_GAIN_DB;
    }

}
