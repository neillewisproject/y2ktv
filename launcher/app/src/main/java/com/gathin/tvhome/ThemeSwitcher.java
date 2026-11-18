package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Themes bundled in the APK under assets/themes/<name>/ (copied from the repo's themes/ folder at build time),
 * applied on the TV from Launcher settings. Applying one writes the same files tools/theme.sh pushes into
 * {@link Config#dir}, plus a fresh theme_revision, so Config reads it exactly like a pushed theme.
 */
final class ThemeSwitcher {
    private static final String TAG = "ThemeSwitcher";
    static final String ASSETS = "themes";
    /** The bundled theme last applied from Launcher settings; absent for the built-in look or a pushed theme. */
    static final String ACTIVE_KEY = "active_theme";
    /** The theme_revision token the switcher wrote; a push from tools/theme.sh replaces it and ends the match. */
    private static final String ACTIVE_REVISION_KEY = "active_theme_revision";
    /** The installed APK that unpacked {@link #ACTIVE_KEY}, so an updated APK refreshes its bundled wallpapers. */
    private static final String ACTIVE_APK_KEY = "active_theme_apk";
    private static final String REVISION_NAME = "theme_revision";

    private ThemeSwitcher() {}

    private static String apkStamp(Context ctx) {
        String source = ctx.getApplicationInfo().sourceDir;
        return source == null ? "" : source + ":" + new File(source).lastModified();
    }

    /**
     * Re-copies the active bundled theme when the installed APK changes, so wallpapers added to or removed from
     * {@code assets/themes/<name>/} reach {@link Config#dir} while keeping the owner's on-TV layout choices.
     */
    static synchronized void syncBundled(Context ctx) {
        SharedPreferences choices = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        String active = active(ctx, choices);
        if (active == null) return;
        if (apkStamp(ctx).equals(choices.getString(ACTIVE_APK_KEY, null))) return;
        if (available(ctx).contains(active)) apply(ctx, active, true);
    }

    /** Bundled theme folder names that carry a config.json, sorted. */
    static List<String> available(Context ctx) {
        List<String> names = new ArrayList<>();
        AssetManager assets = ctx.getAssets();
        try {
            String[] folders = assets.list(ASSETS);
            if (folders == null) return names;
            Arrays.sort(folders);
            for (String name : folders) {
                String[] files = assets.list(ASSETS + "/" + name);
                if (files != null && Arrays.asList(files).contains(Config.FILE_NAME)) names.add(name);
            }
        } catch (IOException e) {
            Log.w(TAG, "could not list bundled themes: " + e.getMessage());
        }
        return names;
    }

    /** "nature" -> "Nature"; a name with a digit in it reads as an acronym: "y2k" -> "Y2K". */
    static String label(String name) {
        if (name == null || name.isEmpty()) return "";
        String spaced = name.replace('-', ' ').replace('_', ' ');
        if (spaced.matches(".*\\d.*")) return spaced.toUpperCase(Locale.ROOT);
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /** The bundled theme on the TV now, or null for the built-in look or a theme pushed from a computer. */
    static String active(Context ctx, SharedPreferences choices) {
        String name = choices.getString(ACTIVE_KEY, null);
        String token = choices.getString(ACTIVE_REVISION_KEY, null);
        File dir = Config.dir(ctx);
        if (name == null || token == null || dir == null) return null;
        return token.equals(readRevision(new File(dir, REVISION_NAME))) ? name : null;
    }

    private static String readRevision(File file) {
        if (!file.isFile() || file.length() > 64) return null;
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int n = in.read(bytes);
            return n > 0 ? new String(bytes, 0, n, StandardCharsets.US_ASCII) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Replaces the theme files on the TV with the bundled theme {@code name}, or removes them for the built-in
     * look when {@code name} is null. Runs file I/O: call it off the main thread.
     */
    static boolean apply(Context ctx, String name) {
        return apply(ctx, name, false);
    }

    /**
     * As {@link #apply(Context, String)}; with keepChoices the on-TV layout choices (columns, dock opacity, the
     * wallpaper pick, ...) survive the switch instead of going back to the new theme's own. The theme schedule
     * switches this way.
     */
    static synchronized boolean apply(Context ctx, String name, boolean keepChoices) {
        File dir = Config.dir(ctx);
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) return false;
        clear(dir);
        SharedPreferences choices = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        String token;
        try {
            if (name != null) copyTheme(ctx.getAssets(), ASSETS + "/" + name, dir);
            token = writeRevision(dir);
        } catch (IOException e) {
            Log.w(TAG, "could not apply theme " + name + ": " + e.getMessage());
            clear(dir);
            writeRevisionQuietly(dir);
            choices.edit().remove(ACTIVE_KEY).remove(ACTIVE_REVISION_KEY).remove(ACTIVE_APK_KEY).commit();
            return false;
        }
        SharedPreferences.Editor editor = choices.edit();
        if (name == null) editor.remove(ACTIVE_KEY).remove(ACTIVE_REVISION_KEY).remove(ACTIVE_APK_KEY);
        else editor.putString(ACTIVE_KEY, name).putString(ACTIVE_REVISION_KEY, token)
                .putString(ACTIVE_APK_KEY, apkStamp(ctx));
        editor.commit();
        if (keepChoices) Config.acceptCurrentTheme(ctx);
        return true;
    }

    /** A config.json or wallpaper on the TV (from the switcher or pushed from a computer). */
    static boolean filesPresent(Context ctx) {
        File dir = Config.dir(ctx);
        if (dir == null) return false;
        return new File(dir, Config.FILE_NAME).isFile() || new File(dir, Config.WALLPAPER_NAME).isFile()
                || !WallpaperRotation.images(new File(dir, ThemeFiles.WALLPAPERS)).isEmpty();
    }

    private static void clear(File dir) {
        new File(dir, Config.FILE_NAME).delete();
        new File(dir, Config.WALLPAPER_NAME).delete();
        File[] old = new File(dir, ThemeFiles.WALLPAPERS).listFiles((d, n) ->
                ThemeFiles.collectionImage(n) || WallpaperCaptions.FILE_NAME.equals(n));
        if (old != null) for (File f : old) f.delete();
    }

    private static void copyTheme(AssetManager assets, String root, File dir) throws IOException {
        String[] files = assets.list(root);
        if (files == null) throw new IOException("no bundled theme at " + root);
        for (String f : files) {
            if (Config.FILE_NAME.equals(f) || Config.WALLPAPER_NAME.equals(f)) {
                copy(assets, root + "/" + f, new File(dir, f));
            }
        }
        String[] images = assets.list(root + "/" + ThemeFiles.WALLPAPERS);
        if (images == null || images.length == 0) return;
        File wallpapers = new File(dir, ThemeFiles.WALLPAPERS);
        if (!wallpapers.isDirectory() && !wallpapers.mkdirs()) throw new IOException("cannot create " + wallpapers);
        for (String f : images) {
            if (ThemeFiles.collectionImage(f) || WallpaperCaptions.FILE_NAME.equals(f)) {
                copy(assets, root + "/" + ThemeFiles.WALLPAPERS + "/" + f, new File(wallpapers, f));
            }
        }
    }

    private static void copy(AssetManager assets, String from, File to) throws IOException {
        File partial = new File(to.getParentFile(), "." + to.getName() + ".part");
        try (InputStream in = assets.open(from); OutputStream out = new FileOutputStream(partial)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (!partial.renameTo(to)) {
            partial.delete();
            throw new IOException("cannot write " + to);
        }
    }

    /** A new token makes Config treat the change like a theme push: on-TV layout choices follow the new theme. */
    private static String writeRevision(File dir) throws IOException {
        String token = UUID.randomUUID().toString().replace("-", "");
        try (OutputStream out = new FileOutputStream(new File(dir, REVISION_NAME))) {
            out.write(token.getBytes(StandardCharsets.US_ASCII));
        }
        return token;
    }

    private static void writeRevisionQuietly(File dir) {
        try {
            writeRevision(dir);
        } catch (IOException ignored) {
            // the next push or switch writes it
        }
    }
}
