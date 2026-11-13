package com.example.viby.ui;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.NumberPicker;
import android.widget.Toast;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.example.viby.R;
import com.example.viby.VibyApp;
import com.example.viby.download.PlaylistAutoUpdate;
import com.example.viby.playback.Crossfade;
import com.example.viby.playback.VolumeNormalization;
import com.example.viby.util.YtCookies;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.yausername.youtubedl_android.YoutubeDL;

/**
 * One page of the settings. Values live in the app's own preference files, so every item
 * is non-persistent here and reads/writes through the owning class; pages are rebuilt on
 * resume so values changed elsewhere (e.g. the effects screen) show up.
 */
public class SettingsFragment extends PreferenceFragmentCompat {

    enum Screen {
        ROOT(R.string.menu_settings),
        PLAYBACK(R.string.settings_playback),
        DOWNLOADS(R.string.settings_downloads),
        YOUTUBE(R.string.settings_youtube),
        ABOUT(R.string.settings_about);

        @StringRes final int title;

        Screen(@StringRes int title) {
            this.title = title;
        }
    }

    private static final String ARG_SCREEN = "screen";
    private static final VolumeNormalization.Level[] LEVELS = VolumeNormalization.Level.values();
    private static final int[] LEVEL_LABELS = {
            R.string.normalization_level_quiet,
            R.string.normalization_level_normal,
            R.string.normalization_level_loud,
    };

    private Screen screen = Screen.ROOT;

    static SettingsFragment create(Screen screen) {
        SettingsFragment fragment = new SettingsFragment();
        Bundle args = new Bundle();
        args.putString(ARG_SCREEN, screen.name());
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        Bundle args = getArguments();
        if (args != null) {
            screen = Screen.valueOf(args.getString(ARG_SCREEN, Screen.ROOT.name()));
        }
        build();
    }

    @Override
    public void onResume() {
        super.onResume();
        build();
    }

    /** Rebuilds after the click that changed a value has been handled. */
    private void rebuild() {
        if (getView() != null) {
            getView().post(() -> {
                if (isAdded()) {
                    build();
                }
            });
        }
    }

    private void build() {
        Context context = requireContext();
        PreferenceScreen root = getPreferenceManager().createPreferenceScreen(context);
        setPreferenceScreen(root);
        switch (screen) {
            case PLAYBACK:
                buildPlayback(root);
                break;
            case DOWNLOADS:
                buildDownloads(root);
                break;
            case YOUTUBE:
                buildYoutube(root);
                break;
            case ABOUT:
                buildAbout(root);
                break;
            default:
                buildRoot(root);
                break;
        }
    }

    // --------------------------------------------------------------------- root

    private void buildRoot(PreferenceScreen root) {
        Context context = requireContext();
        section(root, R.drawable.ic_play, R.string.settings_playback,
                getString(R.string.settings_playback_summary, crossfadeLabel(context),
                        onOff(VolumeNormalization.isEnabled(context))),
                Screen.PLAYBACK);
        section(root, R.drawable.ic_download, R.string.settings_downloads,
                getString(R.string.settings_downloads_summary,
                        DownloadFormatDialog.currentLabel(context),
                        onOff(PlaylistAutoUpdate.isEnabled(context))),
                Screen.DOWNLOADS);
        section(root, R.drawable.ic_account, R.string.settings_youtube,
                getString(YtCookies.isLoggedIn(context)
                        ? R.string.settings_youtube_signed_in
                        : R.string.settings_youtube_signed_out),
                Screen.YOUTUBE);
        Preference storage = item(root, R.string.menu_storage,
                getString(R.string.settings_storage_summary));
        storage.setIcon(accentIcon(R.drawable.ic_storage));
        storage.setOnPreferenceClickListener(p -> {
            startActivity(new Intent(context, StorageActivity.class));
            return true;
        });
        section(root, R.drawable.ic_update, R.string.settings_about,
                getString(R.string.settings_about_summary, versionName(context)),
                Screen.ABOUT);
    }

    private void section(PreferenceScreen root, @DrawableRes int icon, @StringRes int title,
                         CharSequence summary, Screen target) {
        Preference preference = item(root, title, summary);
        preference.setIcon(accentIcon(icon));
        preference.setOnPreferenceClickListener(p -> {
            ((SettingsActivity) requireActivity()).openScreen(target);
            return true;
        });
    }

    // ----------------------------------------------------------------- playback

    private void buildPlayback(PreferenceScreen root) {
        Context context = requireContext();
        PreferenceCategory transitions = category(root, R.string.settings_category_transitions);
        Preference crossfade = item(transitions, R.string.crossfade_title,
                crossfadeSummary(context));
        crossfade.setOnPreferenceClickListener(p -> {
            showCrossfadeDialog();
            return true;
        });

        PreferenceCategory volume = category(root, R.string.settings_category_volume);
        SwitchPreferenceCompat normalization = new SwitchPreferenceCompat(context);
        normalization.setPersistent(false);
        normalization.setIconSpaceReserved(false);
        normalization.setTitle(R.string.normalization_title);
        normalization.setSummary(R.string.settings_normalization_summary);
        normalization.setChecked(VolumeNormalization.isEnabled(context));
        normalization.setOnPreferenceChangeListener((p, value) -> {
            VolumeNormalization.setEnabled(context, (Boolean) value);
            rebuild();
            return true;
        });
        volume.addPreference(normalization);

        Preference level = item(volume, R.string.normalization_level_title,
                getString(LEVEL_LABELS[VolumeNormalization.level(context).ordinal()]));
        level.setEnabled(VolumeNormalization.isEnabled(context));
        level.setOnPreferenceClickListener(p -> {
            String[] labels = new String[LEVELS.length];
            for (int i = 0; i < LEVELS.length; i++) {
                labels[i] = getString(LEVEL_LABELS[i]);
            }
            new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.normalization_level_title)
                    .setSingleChoiceItems(labels, VolumeNormalization.level(context).ordinal(),
                            (dialog, which) -> {
                                VolumeNormalization.setLevel(context, LEVELS[which]);
                                rebuild();
                                dialog.dismiss();
                            })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        });

        Preference effects = item(volume, R.string.menu_effects,
                getString(R.string.settings_effects_summary));
        effects.setOnPreferenceClickListener(p -> {
            startActivity(new Intent(context, EqualizerActivity.class));
            return true;
        });
    }

    private String crossfadeLabel(Context context) {
        int ms = Crossfade.durationMs(context);
        return ms <= 0 ? getString(R.string.crossfade_off)
                : getString(R.string.crossfade_seconds, ms / 1000);
    }

    private String crossfadeSummary(Context context) {
        return Crossfade.durationMs(context) <= 0 ? getString(R.string.crossfade_off)
                : getString(R.string.crossfade_summary_on, crossfadeLabel(context));
    }

    /** Off, 1 s and 3 s like the desktop players, plus any other length up to 12 s. */
    private void showCrossfadeDialog() {
        Context context = requireContext();
        int current = Crossfade.durationMs(context);
        int presets = Crossfade.PRESETS_MS.length;
        String[] labels = new String[presets + 1];
        int checked = presets; // a custom length unless it matches a preset
        for (int i = 0; i < presets; i++) {
            int ms = Crossfade.PRESETS_MS[i];
            labels[i] = ms == 0 ? getString(R.string.crossfade_off)
                    : getString(R.string.crossfade_seconds, ms / 1000);
            if (ms == current) {
                checked = i;
            }
        }
        labels[presets] = checked == presets
                ? getString(R.string.crossfade_custom_current, current / 1000)
                : getString(R.string.crossfade_custom);
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.crossfade_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which < presets) {
                        Crossfade.setDurationMs(context, Crossfade.PRESETS_MS[which]);
                        rebuild();
                    } else {
                        showCrossfadePicker(current);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showCrossfadePicker(int currentMs) {
        Context context = requireContext();
        NumberPicker picker = new NumberPicker(context);
        picker.setMinValue(1);
        picker.setMaxValue(Crossfade.MAX_MS / 1000);
        picker.setValue(Math.max(1, currentMs > 0 ? currentMs / 1000 : 5));
        picker.setFormatter(value -> getString(R.string.crossfade_seconds, value));
        picker.setWrapSelectorWheel(false);
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.crossfade_pick_title)
                .setView(picker)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    picker.clearFocus();
                    Crossfade.setDurationMs(context, picker.getValue() * 1000);
                    rebuild();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---------------------------------------------------------------- downloads

    private void buildDownloads(PreferenceScreen root) {
        Context context = requireContext();
        Preference format = item(root, R.string.download_format_title,
                DownloadFormatDialog.currentLabel(context));
        format.setOnPreferenceClickListener(p -> {
            DownloadFormatDialog.show(context, this::rebuild);
            return true;
        });

        SwitchPreferenceCompat autoUpdate = new SwitchPreferenceCompat(context);
        autoUpdate.setPersistent(false);
        autoUpdate.setIconSpaceReserved(false);
        autoUpdate.setTitle(R.string.auto_update_title);
        autoUpdate.setSummary(R.string.auto_update_summary);
        autoUpdate.setChecked(PlaylistAutoUpdate.isEnabled(context));
        autoUpdate.setOnPreferenceChangeListener((p, value) -> {
            PlaylistAutoUpdate.setEnabled(context, (Boolean) value);
            return true;
        });
        root.addPreference(autoUpdate);
    }

    // ------------------------------------------------------------------ youtube

    private void buildYoutube(PreferenceScreen root) {
        Context context = requireContext();
        if (!YtCookies.isLoggedIn(context)) {
            Preference login = item(root, R.string.login_title,
                    getString(R.string.settings_youtube_login_summary));
            login.setOnPreferenceClickListener(p -> {
                startActivity(new Intent(context, YoutubeLoginActivity.class));
                return true;
            });
            return;
        }
        Preference status = item(root, R.string.menu_account,
                getString(R.string.settings_youtube_signed_in));
        status.setSelectable(false);
        Preference playlists = item(root, R.string.account_my_playlists,
                getString(R.string.settings_youtube_playlists_summary));
        playlists.setOnPreferenceClickListener(p -> {
            startActivity(new Intent(context, YoutubePlaylistsActivity.class));
            return true;
        });
        // destructive action last, with a confirmation
        Preference logout = item(root, R.string.account_logout, null);
        logout.setOnPreferenceClickListener(p -> {
            new MaterialAlertDialogBuilder(context)
                    .setMessage(R.string.settings_logout_confirm)
                    .setPositiveButton(R.string.account_logout, (dialog, which) ->
                            YtCookies.clear(context, () -> {
                                Toast.makeText(context, R.string.logged_out,
                                        Toast.LENGTH_SHORT).show();
                                if (isAdded()) {
                                    rebuild();
                                }
                            }))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        });
    }

    // -------------------------------------------------------------------- about

    private void buildAbout(PreferenceScreen root) {
        Context context = requireContext();
        Preference version = item(root, R.string.settings_version, versionName(context));
        version.setSelectable(false);

        Preference updates = item(root, R.string.menu_check_updates,
                getString(R.string.settings_updates_summary));
        updates.setOnPreferenceClickListener(p -> {
            ((SettingsActivity) requireActivity()).checkForAppUpdate();
            return true;
        });

        Preference ytDlp = item(root, R.string.menu_update_ytdlp,
                getString(R.string.settings_ytdlp_summary));
        ytDlp.setOnPreferenceClickListener(p -> {
            updateYtDlp(context);
            return true;
        });
    }

    private static void updateYtDlp(Context context) {
        Context app = context.getApplicationContext();
        Toast.makeText(app, R.string.ytdlp_updating, Toast.LENGTH_SHORT).show();
        ((VibyApp) app).updateYtDlp((status, error) -> {
            if (error != null) {
                Toast.makeText(app, app.getString(R.string.ytdlp_update_error,
                        error.getMessage()), Toast.LENGTH_LONG).show();
            } else if (status == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) {
                Toast.makeText(app, R.string.ytdlp_up_to_date, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(app, R.string.ytdlp_updated, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private Preference item(androidx.preference.PreferenceGroup parent, @StringRes int title,
                            @Nullable CharSequence summary) {
        Preference preference = new Preference(requireContext());
        preference.setPersistent(false);
        preference.setIconSpaceReserved(false);
        preference.setTitle(title);
        preference.setSummary(summary);
        parent.addPreference(preference);
        return preference;
    }

    private PreferenceCategory category(PreferenceScreen root, @StringRes int title) {
        PreferenceCategory category = new PreferenceCategory(requireContext());
        category.setIconSpaceReserved(false);
        category.setTitle(title);
        root.addPreference(category);
        return category;
    }

    @Nullable
    private Drawable accentIcon(@DrawableRes int icon) {
        Drawable drawable = AppCompatResources.getDrawable(requireContext(), icon);
        if (drawable == null) {
            return null;
        }
        drawable = DrawableCompat.wrap(drawable.mutate());
        DrawableCompat.setTintList(drawable, ColorStateList.valueOf(MaterialColors.getColor(
                requireContext(), androidx.appcompat.R.attr.colorPrimary, 0)));
        return drawable;
    }

    private String onOff(boolean on) {
        return getString(on ? R.string.settings_on : R.string.settings_off);
    }

    private static String versionName(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception unknown) {
            return "?";
        }
    }

    @NonNull
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable android.view.ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = super.onCreateView(inflater, container, savedInstanceState);
        view.setBackgroundColor(MaterialColors.getColor(view,
                com.google.android.material.R.attr.colorSurface));
        return view;
    }
}
