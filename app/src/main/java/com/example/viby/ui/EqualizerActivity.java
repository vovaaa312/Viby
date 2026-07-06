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
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.List;
import java.util.Locale;

/**
 * Звуковые эффекты: 10-полосный эквалайзер как в AIMP с горизонтальными
 * ползунками, встроенными и пользовательскими пресетами.
 */
public class EqualizerActivity extends AppCompatActivity {

    /** Ползунок: 0..300 → -15.0..+15.0 дБ (шаг 0.1). */
    private static final int SLIDER_MAX = (int) (EqFx.MAX_GAIN_DB * 2 * 10);

    private Button presetButton;
    private TextView[] valueLabels;
    private SeekBar[] sliders;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_equalizer);

        MaterialToolbar toolbar = findViewById(R.id.eqToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        LinearLayout bandsContainer = findViewById(R.id.bandsContainer);
        presetButton = findViewById(R.id.presetButton);
        Button savePresetButton = findViewById(R.id.savePresetButton);
        MaterialSwitch eqSwitch = findViewById(R.id.eqSwitch);
        TextView unavailable = findViewById(R.id.eqUnavailable);

        if (!EqFx.isAvailable()) {
            unavailable.setVisibility(View.VISIBLE);
            eqSwitch.setEnabled(false);
            presetButton.setEnabled(false);
            savePresetButton.setEnabled(false);
            return;
        }

        eqSwitch.setChecked(EqFx.isEnabled());
        eqSwitch.setOnCheckedChangeListener((btn, checked) -> EqFx.setEnabled(checked));

        presetButton.setOnClickListener(v -> showPresetDialog());
        savePresetButton.setOnClickListener(v -> showSavePresetDialog());
        updatePresetButton();

        buildBands(bandsContainer);
    }

    private void buildBands(LinearLayout container) {
        int bands = EqFx.getBandCount();
        valueLabels = new TextView[bands];
        sliders = new SeekBar[bands];
        float density = getResources().getDisplayMetrics().density;

        for (int band = 0; band < bands; band++) {
            final int b = band;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, (int) (6 * density), 0, (int) (6 * density));

            TextView freq = new TextView(this);
            freq.setText(formatFreq(EqFx.getCenterFreqHz(b)));
            freq.setGravity(Gravity.END);
            freq.setTextSize(13);
            row.addView(freq, new LinearLayout.LayoutParams(
                    (int) (52 * density), LinearLayout.LayoutParams.WRAP_CONTENT));

            SeekBar slider = new SeekBar(this);
            slider.setMax(SLIDER_MAX);
            slider.setProgress(gainToProgress(EqFx.getBandGainDb(b)));
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    if (fromUser) {
                        EqFx.setBandGainDb(b, progressToGain(progress));
                        updatePresetButton();
                    }
                    updateValueLabel(b);
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar bar) {
                }
            });
            sliders[band] = slider;
            LinearLayout.LayoutParams sliderParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            sliderParams.setMarginStart((int) (8 * density));
            row.addView(slider, sliderParams);

            TextView value = new TextView(this);
            value.setGravity(Gravity.END);
            value.setTextSize(13);
            valueLabels[band] = value;
            row.addView(value, new LinearLayout.LayoutParams(
                    (int) (64 * density), LinearLayout.LayoutParams.WRAP_CONTENT));

            container.addView(row);
            updateValueLabel(b);
        }
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
                .setView(input)
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
        for (int band = 0; band < sliders.length; band++) {
            sliders[band].setProgress(gainToProgress(EqFx.getBandGainDb(band)));
            updateValueLabel(band);
        }
    }

    private void updateValueLabel(int band) {
        valueLabels[band].setText(String.format(Locale.US, "%+.1f dB",
                EqFx.getBandGainDb(band)));
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

    private static String formatFreq(int hz) {
        if (hz >= 1000) {
            return (hz % 1000 == 0 ? String.valueOf(hz / 1000)
                    : String.format(Locale.US, "%.1f", hz / 1000f)) + "k";
        }
        return String.valueOf(hz);
    }
}
