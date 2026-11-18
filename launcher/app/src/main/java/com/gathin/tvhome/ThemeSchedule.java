package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Launcher settings' theme schedule: one bundled theme by day and another at night, switched at the times the owner
 * chose. Home asks {@link #check} when it comes back and on its minute tick, so there is no alarm and no service;
 * a switch that falls due while Home is hidden happens the next time Home shows. A scheduled switch keeps the
 * owner's layout choices ({@link ThemeSwitcher#apply(Context, String, boolean)}).
 */
final class ThemeSchedule {
    static final String ENABLED_KEY = "theme_schedule";
    /** A bundled theme's folder name; "" is the built-in look. */
    static final String DAY_KEY = "theme_day";
    static final String NIGHT_KEY = "theme_night";
    /** Minutes after midnight. */
    static final String DAY_STARTS_KEY = "theme_day_starts";
    static final String NIGHT_STARTS_KEY = "theme_night_starts";
    static final int DEFAULT_DAY_STARTS = 7 * 60;
    static final int DEFAULT_NIGHT_STARTS = 19 * 60;
    /** A switch that failed is not retried on every minute tick. */
    private static final long RETRY_MS = 5 * 60_000L;
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static long lastAttempt;

    private ThemeSchedule() {}

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    static boolean enabled(SharedPreferences p) {
        return p.getBoolean(ENABLED_KEY, false);
    }

    static int dayStarts(SharedPreferences p) {
        return clamp(p.getInt(DAY_STARTS_KEY, DEFAULT_DAY_STARTS));
    }

    static int nightStarts(SharedPreferences p) {
        return clamp(p.getInt(NIGHT_STARTS_KEY, DEFAULT_NIGHT_STARTS));
    }

    private static int clamp(int minutes) {
        return Math.floorMod(minutes, 24 * 60);
    }

    /** True when minuteOfDay falls in the day slot; the slots wrap past midnight either way round. */
    static boolean isDay(int minuteOfDay, int dayStarts, int nightStarts) {
        if (dayStarts == nightStarts) return true;
        if (dayStarts < nightStarts) return minuteOfDay >= dayStarts && minuteOfDay < nightStarts;
        return minuteOfDay >= dayStarts || minuteOfDay < nightStarts;
    }

    static boolean isDayNow(SharedPreferences p) {
        LocalTime now = LocalTime.now();
        return isDay(now.getHour() * 60 + now.getMinute(), dayStarts(p), nightStarts(p));
    }

    /** The theme the schedule wants now: a folder name, or null for the built-in look. */
    static String dueTheme(SharedPreferences p) {
        String name = p.getString(isDayNow(p) ? DAY_KEY : NIGHT_KEY, "");
        return name == null || name.isEmpty() ? null : name;
    }

    /** The key Launcher settings writes when the Theme row is stepped while the schedule is on. */
    static String currentSlotKey(SharedPreferences p) {
        return isDayNow(p) ? DAY_KEY : NIGHT_KEY;
    }

    /**
     * Switches to the slot's theme in the background when it is not on the TV yet. onSwitched runs on the main
     * thread after a switch; the settings revision has moved by then, so Home rebuilds on its next resume anyway.
     */
    static void check(Context context, Runnable onSwitched) {
        Context ctx = context.getApplicationContext();
        SharedPreferences p = prefs(ctx);
        if (!enabled(p)) return;
        String wanted = dueTheme(p);
        if (wanted != null && !ThemeSwitcher.available(ctx).contains(wanted)) return;   // a theme no longer bundled
        String active = ThemeSwitcher.active(ctx, p);
        boolean onTv = wanted == null ? active == null && !ThemeSwitcher.filesPresent(ctx) : wanted.equals(active);
        if (onTv) return;
        long now = SystemClock.elapsedRealtime();
        if (lastAttempt != 0 && now - lastAttempt < RETRY_MS) return;
        if (!RUNNING.compareAndSet(false, true)) return;
        lastAttempt = now;
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            boolean ok = false;
            try {
                ok = ThemeSwitcher.apply(ctx, wanted, true);
                if (ok) {
                    p.edit().putLong(Config.SETTINGS_REVISION_KEY, p.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).commit();
                    lastAttempt = 0;
                }
            } catch (RuntimeException ignored) {
                // tried again after RETRY_MS
            } finally {
                RUNNING.set(false);
            }
            if (ok && onSwitched != null) main.post(onSwitched);
        }, "theme-schedule").start();
    }

    /** "07:00" for Launcher settings. */
    static String format(int minutes) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d", clamp(minutes) / 60, clamp(minutes) % 60);
    }
}
