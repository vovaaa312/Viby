package com.example.viby.ui;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.viby.R;
import com.example.viby.playback.EqFx;
import com.example.viby.playback.EqPresets;
import com.example.viby.ui.widget.VerticalSeekBar;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.Locale;

/** Звуковые эффекты: системный эквалайзер с пресетами в духе AIMP. */
public class EqualizerActivity extends AppCompatActivity {

    private LinearLayout bandsContainer;
    private Button presetButton;
    private TextView[] valueLabels;
    private VerticalSeekBar[] sliders;
    private short minLevel;
    private short maxLevel;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_equalizer);

        MaterialToolbar toolbar = findViewById(R.id.eqToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        bandsContainer = findViewById(R.id.bandsContainer);
        presetButton = findViewById(R.id.presetButton);
        MaterialSwitch eqSwitch = findViewById(R.id.eqSwitch);
        TextView unavailable = findViewById(R.id.eqUnavailable);

        if (!EqFx.isAvailable()) {
            unavailable.setVisibility(View.VISIBLE);
            bandsContainer.setVisibility(View.GONE);
            eqSwitch.setEnabled(false);
            presetButton.setEnabled(false);
            return;
        }

        eqSwitch.setChecked(EqFx.isEnabled());
        eqSwitch.setOnCheckedChangeListener((btn, checked) -> EqFx.setEnabled(checked));

        presetButton.setOnClickListener(v -> showPresetDialog());
        updatePresetButton();

        buildBands();
    }

    private void buildBands() {
        short bands = EqFx.getBandCount();
        short[] range = EqFx.getLevelRange();
        minLevel = range[0];
        maxLevel = range[1];
        valueLabels = new TextView[bands];
        sliders = new VerticalSeekBar[bands];

        for (short band = 0; band < bands; band++) {
            final short b = band;

            LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams columnParams =
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            column.setLayoutParams(columnParams);

            TextView value = new TextView(this);
            value.setGravity(Gravity.CENTER);
            value.setTextSize(12);
            valueLabels[band] = value;
            column.addView(value, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            VerticalSeekBar slider = new VerticalSeekBar(this);
            slider.setMax(maxLevel - minLevel);
            slider.setProgress(EqFx.getBandLevel(b) - minLevel);
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    if (fromUser) {
                        EqFx.setBandLevel(b, (short) (progress + minLevel));
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
                    LinearLayout.LayoutParams.WRAP_CONTENT, 0, 1f);
            sliderParams.gravity = Gravity.CENTER_HORIZONTAL;
            column.addView(slider, sliderParams);

            TextView freq = new TextView(this);
            freq.setGravity(Gravity.CENTER);
            freq.setTextSize(11);
            freq.setText(formatFreq(EqFx.getCenterFreqHz(b)));
            column.addView(freq, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            bandsContainer.addView(column);
            updateValueLabel(b);
        }
    }

    private void showPresetDialog() {
        String current = EqFx.getPresetName();
        int checked = -1;
        for (int i = 0; i < EqPresets.NAMES.length; i++) {
            if (EqPresets.NAMES[i].equals(current)) {
                checked = i;
                break;
            }
        }
        final int[] selected = {checked};
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.eq_preset_button)
                .setSingleChoiceItems(EqPresets.NAMES, checked,
                        (dialog, which) -> selected[0] = which)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (selected[0] >= 0) {
                        EqFx.applyPreset(EqPresets.NAMES[selected[0]]);
                        refreshSliders();
                        updatePresetButton();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void refreshSliders() {
        for (short band = 0; band < sliders.length; band++) {
            sliders[band].setProgress(EqFx.getBandLevel(band) - minLevel);
            updateValueLabel(band);
        }
    }

    private void updateValueLabel(short band) {
        float db = EqFx.getBandLevel(band) / 100f;
        valueLabels[band].setText(String.format(Locale.US, "%+.1f", db));
    }

    private void updatePresetButton() {
        String preset = EqFx.getPresetName();
        presetButton.setText(preset.isEmpty()
                ? getString(R.string.eq_custom) : preset);
    }

    private static String formatFreq(int hz) {
        if (hz >= 1000) {
            return (hz % 1000 == 0 ? String.valueOf(hz / 1000)
                    : String.format(Locale.US, "%.1f", hz / 1000f)) + "k";
        }
        return String.valueOf(hz);
    }
}
