package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The header's weather: an approximate location from the TV's public IP address (ipwho.is, ipapi.co as a
 * fallback; kept a day) and the current temperature and conditions from Open-Meteo. Neither needs an account or a
 * key. Read at most every 30 minutes, only when Home or the clock screensaver asks (no service, no alarm), five
 * minutes apart after a failure, and kept in preferences so Home shows the last reading at once. Every failure is
 * silent: the header simply shows no weather.
 */
final class Weather {
    private static final String TAG = "TvHome.Weather";
    private static final String PREFS = "weather";
    /** Launcher settings' Weather: on unless turned off. */
    static final String ENABLED_KEY = "weather_enabled";
    /** Launcher settings' Temperature: "c" or "f". */
    static final String UNITS_KEY = "weather_units";

    private static final long REFRESH_MS = 30 * 60_000L;
    private static final long RETRY_MS = 5 * 60_000L;
    private static final long LOCATION_MS = 24 * 3_600_000L;
    /** An older reading is not shown: the header says nothing rather than something stale. */
    private static final long STALE_MS = 3 * 3_600_000L;
    private static final int TIMEOUT_MS = 8_000;
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    /** One reading, in degrees Celsius. */
    static final class Reading {
        final float celsius;
        final int code;
        final boolean day;
        final String city;

        Reading(float celsius, int code, boolean day, String city) {
            this.celsius = celsius;
            this.code = code;
            this.day = day;
            this.city = city;
        }

        /** "24°", in the owner's unit. */
        String temperature(boolean fahrenheit) {
            float value = fahrenheit ? celsius * 9f / 5f + 32f : celsius;
            return String.format(Locale.ROOT, "%d°", Math.round(value));
        }

        int icon() {
            return Weather.icon(code, day);
        }
    }

    interface Listener {
        void onWeather(Reading reading);
    }

    private Weather() {}

    static boolean enabled(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).getBoolean(ENABLED_KEY, true);
    }

    static boolean fahrenheit(Context ctx) {
        return "f".equals(ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).getString(UNITS_KEY, "c"));
    }

    /** The last reading if it is recent enough to show, else null. */
    static Reading cached(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long at = p.getLong("at", 0);
        if (at == 0 || Math.abs(System.currentTimeMillis() - at) > STALE_MS) return null;
        return new Reading(p.getFloat("celsius", 0f), p.getInt("code", 0), p.getBoolean("day", true),
                p.getString("city", ""));
    }

    /**
     * Fetches a new reading in the background when the last one is due, and hands it to listener on the main
     * thread. Does nothing while Weather is off, while a fetch is running, or before the next one is due.
     */
    static void refresh(Context context, Listener listener) {
        Context ctx = context.getApplicationContext();
        if (!enabled(ctx)) return;
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long at = p.getLong("at", 0), failed = p.getLong("failed", 0);
        if (at != 0 && now - at >= 0 && now - at < REFRESH_MS) return;
        if (failed != 0 && now - failed >= 0 && now - failed < RETRY_MS) return;
        if (!RUNNING.compareAndSet(false, true)) return;
        Handler main = new Handler(Looper.getMainLooper());
        Thread worker = new Thread(() -> {
            Reading reading = null;
            try {
                reading = fetch(p);
            } catch (Exception e) {
                Log.i(TAG, "no weather now: " + e.getClass().getSimpleName());
            }
            if (reading == null) {
                p.edit().putLong("failed", System.currentTimeMillis()).apply();
            } else {
                p.edit().putLong("at", System.currentTimeMillis()).remove("failed")
                        .putFloat("celsius", reading.celsius).putInt("code", reading.code)
                        .putBoolean("day", reading.day).putString("city", reading.city).apply();
            }
            RUNNING.set(false);
            Reading result = reading;
            if (result != null && listener != null) main.post(() -> {
                try {
                    listener.onWeather(result);
                } catch (RuntimeException e) {
                    Log.w(TAG, "listener failed: " + e);
                }
            });
        }, "weather");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    private static Reading fetch(SharedPreferences p) throws Exception {
        String lat = p.getString("lat", null), lon = p.getString("lon", null);
        String city = p.getString("city", "");
        long located = p.getLong("located", 0);
        if (lat == null || lon == null || System.currentTimeMillis() - located > LOCATION_MS) {
            String[] place = locate();
            if (place != null) {
                lat = place[0];
                lon = place[1];
                city = place[2];
                p.edit().putString("lat", lat).putString("lon", lon).putString("city", city)
                        .putLong("located", System.currentTimeMillis()).apply();
            }
        }
        if (lat == null || lon == null) return null;
        JSONObject json = new JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=" + lat
                + "&longitude=" + lon + "&current=temperature_2m,weather_code,is_day&timezone=auto"));
        JSONObject current = json.getJSONObject("current");
        return new Reading((float) current.getDouble("temperature_2m"), current.optInt("weather_code", 0),
                current.optInt("is_day", 1) == 1, city);
    }

    /** {latitude, longitude, city} from the public IP address, or null. */
    private static String[] locate() {
        try {
            JSONObject j = new JSONObject(get("https://ipwho.is/?fields=success,latitude,longitude,city"));
            if (j.optBoolean("success", false) && j.has("latitude") && j.has("longitude")) {
                return place(j.getDouble("latitude"), j.getDouble("longitude"), j.optString("city", ""));
            }
        } catch (Exception e) {
            Log.i(TAG, "ipwho.is: " + e.getClass().getSimpleName());
        }
        try {
            JSONObject j = new JSONObject(get("https://ipapi.co/json/"));
            if (j.has("latitude") && j.has("longitude")) {
                return place(j.getDouble("latitude"), j.getDouble("longitude"), j.optString("city", ""));
            }
        } catch (Exception e) {
            Log.i(TAG, "ipapi.co: " + e.getClass().getSimpleName());
        }
        return null;
    }

    private static String[] place(double lat, double lon, String city) {
        // Two decimals (about a kilometre) is all a forecast needs.
        return new String[]{String.format(Locale.ROOT, "%.2f", lat), String.format(Locale.ROOT, "%.2f", lon), city};
    }

    private static String get(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setRequestProperty("User-Agent", "CleanYourTV-launcher");
        c.setRequestProperty("Accept", "application/json");
        try {
            int status = c.getResponseCode();
            if (status != 200) throw new java.io.IOException("HTTP " + status);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                // Small JSON answers; anything past 64 KB is not one of them.
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 65_536) throw new java.io.IOException("answer too large");
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    /** A WMO weather code (Open-Meteo's weather_code) as one of the header's icons. */
    static int icon(int code, boolean day) {
        if (code == 0 || code == 1) return day ? R.drawable.ic_wx_clear_day : R.drawable.ic_wx_clear_night;
        if (code == 2) return R.drawable.ic_wx_partly;
        if (code == 3) return R.drawable.ic_wx_cloud;
        if (code == 45 || code == 48) return R.drawable.ic_wx_fog;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.ic_wx_snow;
        if (code >= 95) return R.drawable.ic_wx_storm;
        if (code >= 51) return R.drawable.ic_wx_rain;
        return R.drawable.ic_wx_cloud;
    }
}
