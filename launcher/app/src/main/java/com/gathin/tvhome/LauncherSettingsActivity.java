package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Remote-friendly settings owned by the launcher, in two panes: the rows on the left, and on the right a live
 * miniature of Home ({@link HomePreview}) that redraws as each choice changes, with a line on what the focused
 * row does. Left and Right are the only keys that change a value, and each change is saved at once, the Theme
 * row included (it copies the theme's files a moment after the last press). Select only opens the screens of
 * the rows that lead somewhere, such as TV system settings.
 */
public final class LauncherSettingsActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    static final String EXTRA_REORDER = "com.gathin.tvhome.extra.REORDER_FROM_SETTINGS";
    /** Opened from Home's Customize button: start on the Theme row. */
    static final String EXTRA_FOCUS_THEME = "com.gathin.tvhome.extra.FOCUS_THEME";

    private static final int[] COLUMN_CHOICES = {4, 5, 6, 7};
    private static final int[] ROW_CHOICES = {0, 1, 2, 3};
    private static final int[] DOCK_OPACITIES = {30, 50, 70, 100};
    private static final String[] CLOCK_FORMATS = {"system", "12", "24"};
    private static final String NONE = "none";

    private SharedPreferences choices;
    private Config config;
    private HomePreview preview;
    private TextView previewTitle;
    private TextView previewHint;
    private final List<Row> rows = new ArrayList<>();
    private boolean rtl;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final long THEME_SAVE_DELAY_MS = 450;
    private final Runnable applyPending = this::applyPendingTheme;
    private Thread themeThread;

    /** null is the built-in look; the rest are the themes bundled in the APK. */
    private final List<String> themeOptions = new ArrayList<>();
    /** The theme on the TV now as an index into themeOptions, or -1 for one pushed from a computer. */
    private int themeCurrent;
    /** The theme the Theme row shows; saved a moment after the last arrow press, so briefly ahead of themeCurrent. */
    private int themePending;
    /** null is Theme default, NONE no wallpaper, the rest file names from the theme folder. */
    private final List<String> wallpaperOptions = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        config = Config.load(this);
        preview = findViewById(R.id.preview);
        previewTitle = findViewById(R.id.previewTitle);
        previewHint = findViewById(R.id.previewHint);
        rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
        // Mirror the panes without globally opting the launcher into RTL.
        if (rtl) findViewById(R.id.settingsRoot).setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        themeOptions.add(null);
        themeOptions.addAll(ThemeSwitcher.available(this));
        themeCurrent = currentThemeIndex();
        themePending = themeCurrent;
        loadWallpaperOptions();

        addRows();
        for (Row row : rows) wire(row);
        findViewById(R.id.rowReorder).setVisibility(config.launcherSettingsButton ? View.VISIBLE : View.GONE);
        findViewById(R.id.rowOpenOnStart).setVisibility(config.openOnStartChoice ? View.VISIBLE : View.GONE);

        preview.setState(stateFrom(config));
        shownPreviewKey = "home";
        refresh();
        int first = getIntent().getBooleanExtra(EXTRA_FOCUS_THEME, false) || !config.launcherSettingsButton
                ? R.id.rowTheme : R.id.rowReorder;
        findViewById(first).requestFocus();
    }

    // --- rows ----------------------------------------------------------------------------------------------

    /** One row: its value, how Left/Right and OK change it, what it explains, and what it outlines in the preview. */
    private abstract class Row {
        final View view;
        final TextView value;
        final int title;
        final int emphasis;

        Row(int viewId, int valueId, int title, int emphasis) {
            this.view = findViewById(viewId);
            this.value = findViewById(valueId);
            this.title = title;
            this.emphasis = emphasis;
        }

        abstract String value();
        abstract String hint();
        /** Left/Right change the value; false for rows that open something. */
        boolean stepper() { return true; }
        void step(int direction) {}
        /** Select opens the row's own screen; on a value row it does nothing, only the arrows change values. */
        void ok() {}
        boolean enabled() { return true; }
        /** Rows whose result the preview cannot show dim it, so it is clear the picture is not theirs. */
        boolean visual() { return true; }
        void focusLost() {}
        /** What the preview shows while this row is focused; null shows Home as it is now. */
        HomePreview.State previewState() { return null; }
        /** Names previewState(), so the preview only cross-fades when the picture really changes. */
        String previewKey() { return "home"; }
    }

    /** Day theme / Night theme: a bundled theme or Built-in for one part of the day, previewed while focused. */
    private final class ScheduleThemeRow extends Row {
        private final String key;

        ScheduleThemeRow(int viewId, int valueId, int title, String key) {
            super(viewId, valueId, title, HomePreview.EMPHASIS_NONE);
            this.key = key;
        }

        private int index() {
            String name = choices.getString(key, "");
            return name == null || name.isEmpty() ? 0 : Math.max(0, themeOptions.indexOf(name));
        }

        @Override String value() { return themeLabel(index()); }
        @Override String hint() { return getString(R.string.settings_hint_schedule_slot); }
        @Override void step(int direction) {
            String name = themeOptions.get(Math.floorMod(index() + direction, themeOptions.size()));
            choices.edit().putString(key, name == null ? "" : name).apply();
            render(this);
            updatePreview(false);
            checkSchedule();
        }
        @Override HomePreview.State previewState() {
            return index() == themeCurrent ? null : themeState(themeOptions.get(index()));
        }
        @Override String previewKey() { return index() == themeCurrent ? "home" : "theme:" + index(); }
    }

    /** Day starts / Night starts: a time of day in 30-minute steps within [min, max]. */
    private final class ScheduleTimeRow extends Row {
        private final String key;
        private final int fallback, min, max;

        ScheduleTimeRow(int viewId, int valueId, int title, String key, int fallback, int min, int max) {
            super(viewId, valueId, title, HomePreview.EMPHASIS_NONE);
            this.key = key;
            this.fallback = fallback;
            this.min = min;
            this.max = max;
        }

        @Override String value() { return ThemeSchedule.format(choices.getInt(key, fallback)); }
        @Override String hint() { return getString(R.string.settings_hint_schedule_time); }
        @Override boolean visual() { return false; }
        @Override void step(int direction) {
            int minutes = choices.getInt(key, fallback) + 30 * direction;
            if (minutes > max) minutes = min;
            if (minutes < min) minutes = max;
            choices.edit().putInt(key, minutes).apply();
            render(this);
            checkSchedule();
        }
    }

    private static final int[] DIM_CHOICES = {0, 10, 20, 30, 40, 50};
    private static final int[] ROTATION_CHOICES = {Config.ROTATION_NEVER, 5, 10, 20, 30, 60};
    private static final int[] ACCENT_NAMES = {R.string.accent_white, R.string.accent_sky, R.string.accent_mint,
            R.string.accent_rose, R.string.accent_amber, R.string.accent_lilac};
    private static final int[] STYLE_NAMES = {R.string.clock_style_classic, R.string.clock_style_big,
            R.string.clock_style_flip};
    private String shownPreviewKey;

    private int accentIndex() {
        int accent = Config.focusAccent(this);
        for (int i = 0; i < Config.FOCUS_ACCENTS.length; i++) if (Config.FOCUS_ACCENTS[i] == accent) return i;
        return 0;
    }

    private int styleIndex() {
        return Math.max(0, Arrays.asList(ClockScene.STYLES).indexOf(ClockScene.style(this)));
    }

    /** The pictures in the theme's collection on the TV. */
    private int collectionSize() {
        File dir = Config.dir(this);
        return dir == null ? 0 : WallpaperRotation.images(new File(dir, ThemeFiles.WALLPAPERS)).size();
    }

    private HomePreview.State clockState() {
        HomePreview.State s = stateFrom(config);
        s.clockStyle = ClockScene.style(this);
        s.clockWeather = ClockScene.showsWeather(this);
        return s;
    }

    private String clockPreviewKey() {
        return "clock:" + ClockScene.style(this) + ":" + ClockScene.showsWeather(this);
    }

    /** Fetches a reading now that Weather was turned on, and redraws when it arrives. */
    private void requestWeather() {
        Weather.refresh(this, reading -> {
            if (isDestroyed()) return;
            updatePreview(true);
            refresh();
        });
    }

    /** Applies the schedule's theme now if it changed, then reloads everything that shows the theme. */
    private void checkSchedule() {
        ThemeSchedule.check(this, () -> {
            if (isDestroyed()) return;
            themeCurrent = currentThemeIndex();
            themePending = themeCurrent;
            loadWallpaperOptions();
            saved();
        });
    }

    private Row focusedRow() {
        View focused = getCurrentFocus();
        for (Row row : rows) if (row.view == focused) return row;
        return null;
    }

    /** Shows what the focused row wants in the preview; force redraws even when that did not change (a saved value). */
    private void updatePreview(boolean force) {
        Row row = focusedRow();
        HomePreview.State state = row == null ? null : row.previewState();
        String key = row == null ? "home" : row.previewKey();
        if (!force && key.equals(shownPreviewKey)) return;
        shownPreviewKey = key;
        preview.setState(state != null ? state : stateFrom(config));
    }

    private void addRows() {
        rows.add(new Row(R.id.rowTheme, R.id.valueTheme, R.string.theme_label, HomePreview.EMPHASIS_NONE) {
            @Override String value() {
                String label = themeLabel(themePending);
                return ThemeSchedule.enabled(choices) ? getString(R.string.theme_scheduled, label) : label;
            }
            @Override String hint() {
                if (!ThemeSchedule.enabled(choices)) return getString(R.string.settings_hint_theme);
                return getString(R.string.settings_hint_theme_scheduled,
                        getString(ThemeSchedule.isDayNow(choices) ? R.string.time_day : R.string.time_night));
            }
            @Override void step(int direction) {
                int base = themePending < 0 ? (direction > 0 ? -1 : 0) : themePending;
                themePending = Math.floorMod(base + direction, themeOptions.size());
                if (ThemeSchedule.enabled(choices)) {
                    // The schedule would switch straight back: this changes the theme of the part of the day it is.
                    String name = themeOptions.get(themePending);
                    choices.edit().putString(ThemeSchedule.currentSlotKey(choices), name == null ? "" : name).apply();
                }
                updatePreview(false);
                // Saved as it is switched; a short wait lets quick presses through without copying every theme.
                handler.removeCallbacks(applyPending);
                handler.postDelayed(applyPending, THEME_SAVE_DELAY_MS);
            }
            @Override HomePreview.State previewState() {
                return themePending == themeCurrent ? null : themeState(themeOptions.get(themePending));
            }
            @Override String previewKey() { return themePending == themeCurrent ? "home" : "theme:" + themePending; }
            @Override void focusLost() { applyPendingTheme(); }
        });
        rows.add(new Row(R.id.rowWallpaper, R.id.valueWallpaper, R.string.wallpaper_label, HomePreview.EMPHASIS_NONE) {
            @Override String value() { return wallpaperLabel(choices.getString(Config.WALLPAPER_CHOICE_KEY, null)); }
            @Override String hint() { return getString(R.string.settings_hint_wallpaper); }
            @Override void step(int direction) {
                String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
                int at = Math.max(0, wallpaperOptions.indexOf(current));
                String next = wallpaperOptions.get(Math.floorMod(at + direction, wallpaperOptions.size()));
                if (next == null) choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
                else choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, next).apply();
                saved();
            }
        });
        rows.add(new Row(R.id.rowWallpaperDim, R.id.valueWallpaperDim, R.string.wallpaper_dim_label, HomePreview.EMPHASIS_NONE) {
            @Override String value() { return config.wallpaperDim + "%"; }
            @Override String hint() { return getString(R.string.settings_hint_dim); }
            @Override boolean enabled() { return config.wallpaperEnabled; }
            @Override void step(int direction) {
                choices.edit().putInt(Config.WALLPAPER_DIM_KEY, stepInts(DIM_CHOICES, config.wallpaperDim, direction)).apply();
                saved();
            }
        });
        rows.add(new Row(R.id.rowRotation, R.id.valueRotation, R.string.rotation_label, HomePreview.EMPHASIS_NONE) {
            @Override String value() {
                int minutes = config.wallpaperRotationMinutes;
                return minutes <= 0 ? getString(R.string.rotation_never) : getString(R.string.rotation_minutes, minutes);
            }
            @Override String hint() { return getString(R.string.settings_hint_rotation); }
            @Override boolean enabled() { return config.wallpaperEnabled && collectionSize() > 1; }
            @Override void step(int direction) {
                int current = config.wallpaperRotationMinutes <= 0 ? Config.ROTATION_NEVER : config.wallpaperRotationMinutes;
                choices.edit().putInt(Config.WALLPAPER_ROTATION_KEY, stepInts(ROTATION_CHOICES, current, direction)).apply();
                saved();
            }
        });
        rows.add(new Row(R.id.rowAccent, R.id.valueAccent, R.string.accent_label, HomePreview.EMPHASIS_DOCK) {
            @Override String value() { return getString(ACCENT_NAMES[accentIndex()]); }
            @Override String hint() { return getString(R.string.settings_hint_accent); }
            @Override void step(int direction) {
                int next = Config.FOCUS_ACCENTS[Math.floorMod(accentIndex() + direction, Config.FOCUS_ACCENTS.length)];
                if (next == 0) choices.edit().remove(Config.FOCUS_ACCENT_KEY).apply();
                else choices.edit().putInt(Config.FOCUS_ACCENT_KEY, next).apply();
                saved();
            }
        });
        rows.add(new Row(R.id.rowDock, R.id.valueDock, R.string.dock_opacity_label, HomePreview.EMPHASIS_DOCK) {
            @Override String value() { return config.dockOpacity + "%"; }
            @Override String hint() {
                return getString(config.dockBand && config.dockRow ? R.string.settings_hint_dock : R.string.settings_hint_dock_hidden);
            }
            @Override void step(int direction) {
                choices.edit().putInt(Config.DOCK_OPACITY_KEY, stepInts(DOCK_OPACITIES, config.dockOpacity, direction)).apply();
                saved();
            }
        });
        rows.add(new Row(R.id.rowColumns, R.id.valueColumns, R.string.columns_label, HomePreview.EMPHASIS_TILES) {
            @Override String value() { return String.valueOf(config.columns); }
            @Override String hint() { return getString(R.string.settings_hint_columns); }
            @Override void step(int direction) {
                choices.edit().putInt("columns", stepInts(COLUMN_CHOICES, config.columns, direction)).apply();
                saved();
            }
        });
        rows.add(new Row(R.id.rowRows, R.id.valueRows, R.string.rows_below_dock_label, HomePreview.EMPHASIS_ROWS) {
            @Override String value() { return String.valueOf(config.rowsBelowDock); }
            @Override String hint() { return getString(R.string.settings_hint_rows); }
            @Override void step(int direction) {
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, stepInts(ROW_CHOICES, config.rowsBelowDock, direction)).apply();
                saved();
            }
        });
        rows.add(new Row(R.id.rowClock, R.id.valueClock, R.string.dream_name, HomePreview.EMPHASIS_CLOCK) {
            @Override String value() { return clockLabel(config.clockFormat); }
            @Override String hint() { return getString(R.string.settings_hint_clock); }
            @Override void step(int direction) {
                int at = Math.max(0, Arrays.asList(CLOCK_FORMATS).indexOf(config.clockFormat));
                choices.edit().putString(Config.CLOCK_FORMAT_KEY,
                        CLOCK_FORMATS[Math.floorMod(at + direction, CLOCK_FORMATS.length)]).apply();
                saved();
            }
        });
        rows.add(new Row(R.id.rowClockStyle, R.id.valueClockStyle, R.string.clock_style_label, HomePreview.EMPHASIS_NONE) {
            @Override String value() { return getString(STYLE_NAMES[styleIndex()]); }
            @Override String hint() { return getString(R.string.settings_hint_clock_style); }
            @Override void step(int direction) {
                choices.edit().putString(ClockScene.STYLE_KEY,
                        ClockScene.STYLES[Math.floorMod(styleIndex() + direction, ClockScene.STYLES.length)]).apply();
                saved();
            }
            @Override HomePreview.State previewState() { return clockState(); }
            @Override String previewKey() { return clockPreviewKey(); }
            /** Select shows the real screensaver full screen; any key returns here. */
            @Override void ok() { open(new Intent(LauncherSettingsActivity.this, ClockActivity.class)); }
        });
        rows.add(new Row(R.id.rowWeather, R.id.valueWeather, R.string.weather_label, HomePreview.EMPHASIS_CLOCK) {
            @Override String value() { return getString(Weather.enabled(LauncherSettingsActivity.this) ? R.string.on : R.string.off); }
            @Override String hint() {
                if (!Weather.enabled(LauncherSettingsActivity.this)) return getString(R.string.settings_hint_weather_off);
                Weather.Reading reading = Weather.cached(LauncherSettingsActivity.this);
                String where = reading == null ? getString(R.string.settings_hint_weather_waiting)
                        : reading.city.isEmpty() ? reading.temperature(Weather.fahrenheit(LauncherSettingsActivity.this))
                        : reading.city + ", " + reading.temperature(Weather.fahrenheit(LauncherSettingsActivity.this));
                return getString(R.string.settings_hint_weather, where);
            }
            @Override void step(int direction) {
                boolean on = !Weather.enabled(LauncherSettingsActivity.this);
                choices.edit().putBoolean(Weather.ENABLED_KEY, on).apply();
                saved();
                if (on) requestWeather();
            }
        });
        rows.add(new Row(R.id.rowUnits, R.id.valueUnits, R.string.weather_units_label, HomePreview.EMPHASIS_CLOCK) {
            @Override String value() { return Weather.fahrenheit(LauncherSettingsActivity.this) ? "°F" : "°C"; }
            @Override String hint() { return getString(R.string.settings_hint_units); }
            @Override boolean enabled() { return Weather.enabled(LauncherSettingsActivity.this); }
            @Override void step(int direction) {
                choices.edit().putString(Weather.UNITS_KEY, Weather.fahrenheit(LauncherSettingsActivity.this) ? "c" : "f").apply();
                saved();
            }
        });
        rows.add(new Row(R.id.rowClockWeather, R.id.valueClockWeather, R.string.clock_weather_label, HomePreview.EMPHASIS_NONE) {
            @Override String value() {
                return getString(choices.getBoolean(ClockScene.WEATHER_KEY, true) ? R.string.on : R.string.off);
            }
            @Override String hint() { return getString(R.string.settings_hint_clock_weather); }
            @Override boolean enabled() { return Weather.enabled(LauncherSettingsActivity.this); }
            @Override void step(int direction) {
                choices.edit().putBoolean(ClockScene.WEATHER_KEY, !choices.getBoolean(ClockScene.WEATHER_KEY, true)).apply();
                saved();
            }
            @Override HomePreview.State previewState() { return clockState(); }
            @Override String previewKey() { return clockPreviewKey(); }
        });
        rows.add(new Row(R.id.rowSchedule, R.id.valueSchedule, R.string.schedule_label, HomePreview.EMPHASIS_NONE) {
            @Override String value() { return getString(ThemeSchedule.enabled(choices) ? R.string.on : R.string.off); }
            @Override String hint() { return getString(R.string.settings_hint_schedule); }
            @Override void step(int direction) {
                boolean on = !ThemeSchedule.enabled(choices);
                SharedPreferences.Editor editor = choices.edit().putBoolean(ThemeSchedule.ENABLED_KEY, on);
                if (on && !choices.contains(ThemeSchedule.DAY_KEY)) {
                    // First time on: the theme on the TV now by day, and the next one along at night.
                    String now = themeCurrent > 0 ? themeOptions.get(themeCurrent) : null;
                    int other = themeOptions.size() > 1 ? 1 + Math.floorMod(Math.max(0, themeCurrent), themeOptions.size() - 1) : 0;
                    String night = themeOptions.get(Math.min(other, themeOptions.size() - 1));
                    editor.putString(ThemeSchedule.DAY_KEY, now == null ? "" : now)
                            .putString(ThemeSchedule.NIGHT_KEY, night == null ? "" : night);
                }
                editor.apply();
                saved();
                if (on) checkSchedule();
            }
        });
        rows.add(new ScheduleThemeRow(R.id.rowDayTheme, R.id.valueDayTheme, R.string.day_theme_label, ThemeSchedule.DAY_KEY));
        rows.add(new ScheduleTimeRow(R.id.rowDayStarts, R.id.valueDayStarts, R.string.day_starts_label,
                ThemeSchedule.DAY_STARTS_KEY, ThemeSchedule.DEFAULT_DAY_STARTS, 4 * 60, 12 * 60));
        rows.add(new ScheduleThemeRow(R.id.rowNightTheme, R.id.valueNightTheme, R.string.night_theme_label, ThemeSchedule.NIGHT_KEY));
        rows.add(new ScheduleTimeRow(R.id.rowNightStarts, R.id.valueNightStarts, R.string.night_starts_label,
                ThemeSchedule.NIGHT_STARTS_KEY, ThemeSchedule.DEFAULT_NIGHT_STARTS, 15 * 60, 23 * 60 + 30));
        rows.add(new Row(R.id.rowAnimations, R.id.valueAnimations, R.string.animations_label, HomePreview.EMPHASIS_NONE) {
            @Override String value() { return getString(config.animations ? R.string.on : R.string.off); }
            @Override String hint() { return getString(R.string.settings_hint_animations); }
            @Override void step(int direction) {
                boolean on = !config.animations;
                choices.edit().putBoolean("animations", on)
                        .putBoolean("advanced_effects", on && config.advancedEffects).apply();
                saved();
            }
        });
        rows.add(new Row(R.id.rowAdvanced, R.id.valueAdvanced, R.string.advanced_effects, HomePreview.EMPHASIS_NONE) {
            @Override String value() {
                return getString(config.animations && config.advancedEffects ? R.string.on : R.string.off);
            }
            @Override String hint() {
                return config.animations ? getString(R.string.advanced_effects_warning)
                        : getString(R.string.settings_hint_advanced_off);
            }
            @Override boolean enabled() { return config.animations; }
            @Override void step(int direction) {
                if (!config.animations) return;
                choices.edit().putBoolean("advanced_effects", !config.advancedEffects).apply();
                saved();
            }
        });
        rows.add(new Row(R.id.rowReorder, R.id.valueReorder, R.string.reorder, HomePreview.EMPHASIS_TILES) {
            @Override String value() { return "›"; }
            @Override String hint() { return getString(R.string.settings_hint_reorder); }
            @Override boolean stepper() { return false; }
            @Override void ok() {
                setResult(RESULT_OK, new Intent().putExtra(EXTRA_REORDER, true));
                finish();
            }
        });
        rows.add(new Row(R.id.rowOpenOnStart, R.id.valueOpenOnStart, R.string.open_on_start_label, HomePreview.EMPHASIS_NONE) {
            @Override String value() { return openOnStartLabel(); }
            @Override String hint() { return getString(R.string.settings_hint_open_on_start); }
            @Override boolean stepper() { return false; }
            @Override boolean visual() { return false; }
            @Override void ok() { showOpenOnStartPicker(); }
        });
        rows.add(new Row(R.id.rowFollowTheme, R.id.valueFollowTheme, R.string.follow_theme, HomePreview.EMPHASIS_NONE) {
            @Override String value() { return getString(hasOnTvChoices() ? R.string.settings_reset : R.string.settings_in_use); }
            @Override String hint() { return getString(R.string.follow_theme_hint); }
            @Override boolean stepper() { return false; }
            @Override boolean enabled() { return hasOnTvChoices(); }
            @Override void ok() {
                if (!hasOnTvChoices()) return;
                SharedPreferences.Editor editor = choices.edit();
                Config.clearThemeChoices(editor);
                editor.remove(OpenOnStart.KEY).commit();
                saved();
            }
        });
        rows.add(new Row(R.id.rowDefaultHome, R.id.valueDefaultHome, R.string.home_settings, HomePreview.EMPHASIS_NONE) {
            @Override String value() { return "›"; }
            @Override String hint() { return getString(R.string.home_settings_help); }
            @Override boolean stepper() { return false; }
            @Override boolean visual() { return false; }
            @Override void ok() { open(new Intent(LauncherSettingsActivity.this, HomeSettingsActivity.class)); }
        });
        rows.add(new Row(R.id.rowHomeButton, R.id.valueHomeButton, R.string.home_key_name, HomePreview.EMPHASIS_NONE) {
            @Override String value() { return getString(homeButtonEnabled() ? R.string.on : R.string.off); }
            @Override String hint() { return getString(R.string.settings_hint_home_button); }
            @Override boolean stepper() { return false; }
            @Override boolean visual() { return false; }
            @Override void ok() { showHomeButtonSetup(); }
        });
        rows.add(new Row(R.id.rowTvSettings, R.id.valueTvSettings, R.string.tv_settings, HomePreview.EMPHASIS_NONE) {
            @Override String value() { return "›"; }
            @Override String hint() { return getString(R.string.settings_hint_tv); }
            @Override boolean stepper() { return false; }
            @Override boolean visual() { return false; }
            @Override void ok() { open(SystemSettings.intent(LauncherSettingsActivity.this, Settings.ACTION_SETTINGS)); }
        });
    }

    private boolean homeButtonEnabled() {
        AccessibilityManager manager = getSystemService(AccessibilityManager.class);
        if (manager == null || !manager.isEnabled()) return false;
        ComponentName helper = new ComponentName(this, HomeKeyService.class);
        for (AccessibilityServiceInfo service :
                manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (helper.equals(ComponentName.unflattenFromString(service.getId()))) return true;
        }
        return false;
    }

    private void showHomeButtonSetup() {
        Intent accessibility = SystemSettings.standardIntent(this, Settings.ACTION_ACCESSIBILITY_SETTINGS);
        Intent settings = accessibility != null ? accessibility : SystemSettings.intent(this, Settings.ACTION_SETTINGS);
        new AlertDialog.Builder(this)
                .setTitle(R.string.home_key_name)
                .setMessage(getString(accessibility != null ? R.string.home_button_setup
                        : R.string.home_button_setup_fallback, getString(R.string.home_key_name)))
                .setPositiveButton(R.string.tv_settings, (dialog, which) -> open(settings))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void wire(Row row) {
        row.view.setOnClickListener(v -> {
            if (row.enabled() || !row.stepper()) row.ok();
            render(row);
            describe(row);
        });
        row.view.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            // Left and Right belong to the row; focus never wanders into the preview.
            if (event.getAction() != KeyEvent.ACTION_DOWN || !row.stepper() || !row.enabled()) return true;
            int direction = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
            row.step(rtl ? -direction : direction);
            render(row);
            describe(row);
            return true;
        });
        row.view.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                describe(row);
                keepCentred(row.view);
            } else {
                row.focusLost();
            }
            render(row);
        });
    }

    /** Scrolls the list so the focused row sits mid-way, clear of the fading edges at the top and bottom. */
    private void keepCentred(View rowView) {
        android.widget.ScrollView scroll = findViewById(R.id.settingsScroll);
        scroll.post(() -> scroll.smoothScrollTo(0,
                Math.max(0, rowView.getTop() - (scroll.getHeight() - rowView.getHeight()) / 2)));
    }

    /** The row's value, with the arrows that say Left and Right change it while it is focused. */
    private void render(Row row) {
        String value = row.value();
        boolean arrows = row.stepper() && row.enabled() && row.view.hasFocus();
        row.value.setText(arrows ? "‹   " + value + "   ›" : value);
        row.view.setAlpha(row.enabled() || !row.stepper() ? 1f : 0.45f);
    }

    private void describe(Row row) {
        previewTitle.setText(row.title);
        previewHint.setText(row.hint());
        preview.setEmphasis(row.emphasis);
        preview.animate().alpha(row.visual() ? 1f : 0.45f).setDuration(180).start();
        updatePreview(false);
    }

    private void refresh() {
        for (Row row : rows) render(row);
        View focused = getCurrentFocus();
        for (Row row : rows) if (row.view == focused) describe(row);
    }

    /** A choice was written: reload the config it changes, redraw the preview and the rows, and tell Home. */
    private void saved() {
        changed();
        config = Config.load(this);
        updatePreview(true);
        refresh();
    }

    private static int stepInts(int[] values, int current, int direction) {
        int at = -1;
        for (int i = 0; i < values.length; i++) if (values[i] == current) at = i;
        if (at < 0) {
            // A theme value outside the choices (say 8 columns) steps to the nearest choice in that direction.
            at = direction > 0 ? -1 : values.length;
            for (int i = 0; i < values.length; i++) {
                if (direction > 0 && values[i] < current) at = i;
                if (direction < 0 && values[i] > current && at == values.length) at = i;
            }
        }
        return values[Math.floorMod(at + direction, values.length)];
    }

    // --- preview states ------------------------------------------------------------------------------------

    private HomePreview.State stateFrom(Config c) {
        HomePreview.State s = new HomePreview.State();
        s.background = c.backgroundColor;
        File picture = !c.wallpaperEnabled ? null : !c.wallpapers.isEmpty() ? c.wallpapers.get(0) : c.wallpaper;
        if (c.wallpaperEnabled && c.wallpapers.size() > 1) {
            // The picture Home is showing right now (WallpaperPlayer remembers it), not just the first one.
            String showing = getSharedPreferences("wallpaper_rotation", MODE_PRIVATE).getString("image", "");
            File found = WallpaperRotation.find(c.wallpapers, showing);
            if (found != null) picture = found;
        }
        s.wallpaper = picture == null ? null : "file:" + picture.getAbsolutePath();
        s.wallpaperDim = c.wallpaperDim;
        s.columns = c.columns;
        s.rowsBelow = c.rowsBelowDock;
        s.dockOpacity = c.dockOpacity;
        s.dockBand = c.dockBand && c.dockRow;
        s.dockGlass = c.dockGlass;
        s.dockTitle = c.dockTitle;
        s.ambientGlow = c.ambientGlow;
        s.appGlow = c.appGlow;
        s.iconOnlyButtons = c.iconOnlyButtons;
        s.glassStyle = c.glassStyle;
        s.clockFormat = c.clockFormat;
        s.animations = c.animations;
        s.advanced = c.animations && c.advancedEffects;
        s.nextWallpaper = c.wallpaperEnabled && (c.wallpapers.size() > 1 || collectionSize() > 1);
        s.dockLibrary = c.appLibrary && c.dockRow && !c.singleRow;
        addOwnerChoices(s);
        return s;
    }

    /** The choices that are the owner's, not the theme's: they show the same whichever theme is previewed. */
    private void addOwnerChoices(HomePreview.State s) {
        s.accent = Config.focusAccent(this);
        Weather.Reading reading = Weather.enabled(this) ? Weather.cached(this) : null;
        if (reading != null) {
            s.weatherText = reading.temperature(Weather.fahrenheit(this));
            s.weatherIcon = reading.icon();
        }
    }

    /**
     * What Home would look like with a bundled theme applied: its config.json over the built-in config.xml.
     * Applying a theme clears the on-TV choices, so they do not count here.
     */
    private HomePreview.State themeState(String name) {
        Resources res = getResources();
        JSONObject j = name == null ? new JSONObject() : assetJson(ThemeSwitcher.ASSETS + "/" + name + "/" + Config.FILE_NAME);
        HomePreview.State s = new HomePreview.State();
        s.background = color(j.optString("background_color", ""), Color.BLACK);
        s.columns = Math.max(1, Math.min(8, j.optInt("columns", res.getInteger(R.integer.columns))));
        s.rowsBelow = Math.max(0, Math.min(3, j.optInt("rows_below_dock", res.getInteger(R.integer.rows_below_dock))));
        s.dockOpacity = Math.max(30, Math.min(100, j.optInt("dock_opacity", 50)));
        s.dockBand = j.optBoolean("dock_band", res.getBoolean(R.bool.dock_band))
                && j.optBoolean("dock_row", res.getBoolean(R.bool.dock_row));
        s.dockGlass = j.optBoolean("dock_glass", res.getBoolean(R.bool.dock_glass));
        s.dockTitle = j.optBoolean("dock_title", res.getBoolean(R.bool.dock_title));
        s.ambientGlow = j.optBoolean("ambient_glow", res.getBoolean(R.bool.ambient_glow));
        s.appGlow = j.optBoolean("app_glow", res.getBoolean(R.bool.app_glow));
        s.iconOnlyButtons = j.optBoolean("icon_only_buttons", res.getBoolean(R.bool.icon_only_buttons));
        s.glassStyle = Config.glassStyle(j.optString("glass_style", res.getString(R.string.glass_style)));
        s.clockFormat = j.optString(Config.CLOCK_FORMAT_KEY, res.getString(R.string.clock_format));
        s.animations = j.optBoolean("animations", res.getBoolean(R.bool.animations));
        s.advanced = s.animations && j.optBoolean("advanced_effects", res.getBoolean(R.bool.advanced_effects));
        s.wallpaperDim = Math.max(0, Math.min(90, j.optInt("wallpaper_dim", 0)));
        if (name != null && j.optBoolean("wallpaper", true)) s.wallpaper = assetWallpaper(name, j);
        s.nextWallpaper = s.wallpaper != null && j.optInt("wallpaper_rotation_minutes", 0) > 0;
        s.dockLibrary = j.optBoolean("app_library", res.getBoolean(R.bool.app_library))
                && j.optBoolean("dock_row", res.getBoolean(R.bool.dock_row))
                && !j.optBoolean("single_row", res.getBoolean(R.bool.single_row));
        addOwnerChoices(s);
        return s;
    }

    private String assetWallpaper(String name, JSONObject j) {
        String root = ThemeSwitcher.ASSETS + "/" + name;
        try {
            if (j.optInt("wallpaper_rotation_minutes", 0) > 0) {
                String[] images = getAssets().list(root + "/" + ThemeFiles.WALLPAPERS);
                if (images != null) {
                    Arrays.sort(images);
                    for (String image : images) {
                        if (ThemeFiles.collectionImage(image)) return "asset:" + root + "/" + ThemeFiles.WALLPAPERS + "/" + image;
                    }
                }
            }
            String[] files = getAssets().list(root);
            if (files != null && Arrays.asList(files).contains(Config.WALLPAPER_NAME)) {
                return "asset:" + root + "/" + Config.WALLPAPER_NAME;
            }
        } catch (java.io.IOException ignored) {
            // no picture: the preview shows the background colour
        }
        return null;
    }

    private JSONObject assetJson(String path) {
        try (InputStream in = getAssets().open(path)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static int color(String value, int fallback) {
        try {
            return value.isEmpty() ? fallback : Color.parseColor(value.trim());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    // --- theme and wallpaper -------------------------------------------------------------------------------

    private int currentThemeIndex() {
        String active = ThemeSwitcher.active(this, choices);
        if (active != null) return Math.max(0, themeOptions.indexOf(active));
        return themeFilesPresent() ? -1 : 0;
    }

    private String themeLabel(int index) {
        if (index < 0) return getString(R.string.theme_custom);
        String name = themeOptions.get(index);
        return name == null ? getString(R.string.theme_builtin) : ThemeSwitcher.label(name);
    }

    /** Saves the theme the Theme row shows, unless it is already on the TV or a save is still copying files. */
    private void applyPendingTheme() {
        handler.removeCallbacks(applyPending);
        if (themePending < 0 || themePending == themeCurrent) return;
        if (themeThread != null && themeThread.isAlive()) return;   // themeSaved() picks up the newer choice
        String name = themeOptions.get(themePending);
        themeThread = new Thread(() -> {
            boolean ok = ThemeSwitcher.apply(getApplicationContext(), name);
            runOnUiThread(() -> themeSaved(ok));
        }, "theme-switch");
        themeThread.start();
    }

    private void themeSaved(boolean ok) {
        themeCurrent = currentThemeIndex();
        changed();
        if (isFinishing() || isDestroyed()) return;
        if (!ok) {
            themePending = themeCurrent;
            Toast.makeText(this, R.string.theme_failed, Toast.LENGTH_SHORT).show();
        } else if (themePending != themeCurrent) {
            // The arrows moved on while this one was copying: save the newer one too.
            applyPendingTheme();
            return;
        }
        loadWallpaperOptions();
        config = Config.load(this);
        updatePreview(true);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    /**
     * Leaving the page (Back or Home) finishes a theme switch before Home resumes and reads the theme files,
     * so the last theme the arrows showed is the one Home comes back with.
     */
    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(applyPending);
        if (themeThread != null) {
            try {
                themeThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (themePending >= 0 && themePending != currentThemeIndex()) {
            ThemeSwitcher.apply(getApplicationContext(), themeOptions.get(themePending));
            themeCurrent = currentThemeIndex();
            changed();
        }
    }

    /** A config.json or wallpaper on the TV that the switcher did not put there (tools/theme.sh pushed it). */
    private boolean themeFilesPresent() {
        File dir = Config.dir(this);
        if (dir == null) return false;
        return new File(dir, Config.FILE_NAME).isFile() || new File(dir, Config.WALLPAPER_NAME).isFile()
                || !WallpaperRotation.images(new File(dir, ThemeFiles.WALLPAPERS)).isEmpty();
    }

    private void loadWallpaperOptions() {
        wallpaperOptions.clear();
        wallpaperOptions.add(null);
        File dir = Config.dir(this);
        // A theme with a single wallpaper.jpg offers that picture too (Config maps the choice back to it).
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) wallpaperOptions.add(single.getName());
        for (File f : WallpaperRotation.images(dir == null ? null : new File(dir, ThemeFiles.WALLPAPERS))) {
            if (!wallpaperOptions.contains(f.getName())) wallpaperOptions.add(f.getName());
        }
        wallpaperOptions.add(NONE);
    }

    /** "night-water-lily.jpg" reads as "Night water lily"; a pick this theme does not have reads as Theme default. */
    private String wallpaperLabel(String choice) {
        if (choice == null || (!NONE.equals(choice) && !wallpaperOptions.contains(choice))) {
            return getString(R.string.wallpaper_theme_default);
        }
        if (NONE.equals(choice)) return getString(R.string.wallpaper_none);
        String name = choice.replaceFirst("\\.[A-Za-z]+$", "").replace('-', ' ').replace('_', ' ').trim();
        return name.isEmpty() ? choice : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private String clockLabel(String format) {
        if ("12".equals(format)) return getString(R.string.clock_format_12);
        if ("24".equals(format)) return getString(R.string.clock_format_24);
        return getString(R.string.clock_format_system);
    }

    // --- open on start -------------------------------------------------------------------------------------

    /** Off, then every app with a TV entry point by name. Saved at once; it is read on the next boot. */
    private void showOpenOnStartPicker() {
        PackageManager pm = getPackageManager();
        List<String[]> apps = new ArrayList<>();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(tv, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            boolean seen = false;
            for (String[] a : apps) seen |= a[1].equals(pkg);
            if (!seen) apps.add(new String[]{r.loadLabel(pm).toString(), pkg});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        String[] labels = new String[apps.size() + 1];
        labels[0] = getString(R.string.off);
        String current = choices.getString(OpenOnStart.KEY, "");
        int checked = 0;
        for (int i = 0; i < apps.size(); i++) {
            labels[i + 1] = apps.get(i)[0];
            if (apps.get(i)[1].equals(current)) checked = i + 1;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.open_on_start_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 0) choices.edit().remove(OpenOnStart.KEY).apply();
                    else {
                        OpenOnStart.skipThisBoot(this);
                        choices.edit().putString(OpenOnStart.KEY, apps.get(which - 1)[1]).apply();
                    }
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The picked app's name, its package when it is gone, or Off. */
    private String openOnStartLabel() {
        String pkg = choices.getString(OpenOnStart.KEY, "");
        if (pkg.isEmpty()) return getString(R.string.off);
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    // --- shared --------------------------------------------------------------------------------------------

    /**
     * A choice was saved. Home rebuilds when it next resumes because the change count moved, whether these
     * settings are left with Back or with the remote's Home button (which cancels the activity result).
     */
    private void changed() {
        setResult(RESULT_OK);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private boolean hasOnTvChoices() {
        for (String key : Config.THEME_CHOICE_KEYS) if (choices.contains(key)) return true;
        return choices.contains(OpenOnStart.KEY);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
