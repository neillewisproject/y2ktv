package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.util.LruCache;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A miniature Home drawn from a {@link State}, for Launcher settings: the wallpaper, the header, the dock band
 * and the tiles with the user's own apps, and a focus that walks the dock so animation choices show. Every
 * change cross-fades from the previous picture. Geometry is laid out in Home's own dp (960 x 540) and scaled.
 */
public final class HomePreview extends View {

    /** What the preview shows; Launcher settings builds one from Config or from a theme's config.json. */
    static final class State {
        int background = Color.BLACK;
        /** "file:/abs/path" or "asset:themes/x/wallpapers/a.jpg"; null for no wallpaper. */
        String wallpaper;
        int wallpaperDim;
        int columns = 5;
        int rowsBelow = 1;
        int dockOpacity = 50;
        boolean dockBand = true;
        boolean dockGlass;
        boolean dockTitle = true;
        boolean ambientGlow = true;
        boolean appGlow;
        boolean iconOnlyButtons;
        String glassStyle = "glass";
        String clockFormat = "system";
        boolean animations = true;
        boolean advanced;
        /** Launcher settings' Accent colour, 0xRRGGBB; 0 is white. */
        int accent;
        /** The header's weather ("24°") and its icon; null text for none. */
        String weatherText;
        int weatherIcon;
        /** Home shows the Next wallpaper button (a collection of two or more). */
        boolean nextWallpaper;
        /** Home shows the App library button on the dock title's line. */
        boolean dockLibrary;
        /** Not null: draw the clock screensaver in this style (ClockScene.STYLE_*) instead of Home. */
        String clockStyle;
        boolean clockWeather;
    }

    static final int EMPHASIS_NONE = 0;
    static final int EMPHASIS_CLOCK = 1;
    static final int EMPHASIS_DOCK = 2;
    static final int EMPHASIS_TILES = 3;
    static final int EMPHASIS_ROWS = 4;
    static final int EMPHASIS_HEADER = 5;

    private static final float HOME_W = 960f;
    private static final float HOME_H = 540f;
    private static final int MAX_TILES = 32;
    private static final int ACCENT = 0xFF7CC8FF;

    private static final class Tile {
        final String label;
        final Drawable art;
        final boolean banner;
        final int color;

        Tile(String label, Drawable art, boolean banner, int color) {
            this.label = label;
            this.art = art;
            this.banner = banner;
            this.color = color;
        }
    }

    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "home-preview");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final LruCache<String, Bitmap> WALLPAPERS = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF rect = new RectF();
    private final Rect src = new Rect();
    private final Rect bounds = new Rect();

    private State state = new State();
    private Bitmap wallpaper;
    private String wallpaperKey;
    private String wallpaperWanted;
    private List<Tile> tiles = Collections.emptyList();
    private int emphasis = EMPHASIS_NONE;

    private int focus;
    private float focusPos;
    private float focusLift = 1f;
    private ValueAnimator focusAnimator;
    private Bitmap fadeFrom;
    private float fadeAlpha;
    private ValueAnimator fadeAnimator;
    private final Drawable wifiIcon, nextIcon, paletteIcon, settingsIcon, moonIcon, appsIcon;
    private final Typeface medium;

    private final Runnable walk = new Runnable() {
        @Override public void run() {
            int columns = Math.max(1, Math.min(state.columns, 8));
            moveFocus((focus + 1) % Math.min(columns, Math.max(1, tiles.isEmpty() ? columns : tiles.size())));
            main.postDelayed(this, 1700);
        }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            invalidate();
            main.postDelayed(this, 30_000);
        }
    };

    public HomePreview(Context context, AttributeSet attrs) {
        super(context, attrs);
        wifiIcon = icon(context, R.drawable.ic_wifi);
        nextIcon = icon(context, R.drawable.ic_next_wallpaper);
        paletteIcon = icon(context, R.drawable.ic_palette);
        settingsIcon = icon(context, R.drawable.ic_settings);
        moonIcon = icon(context, R.drawable.ic_theme_dark);
        appsIcon = icon(context, R.drawable.ic_apps_pill);
        medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        setFocusable(false);
    }

    private static Drawable icon(Context context, int id) {
        Drawable d = context.getDrawable(id).mutate();
        d.setTint(Color.WHITE);
        return d;
    }

    private final Map<Integer, Drawable> weatherIcons = new HashMap<>();

    private Drawable weatherIcon(int id) {
        Drawable d = weatherIcons.get(id);
        if (d == null) {
            d = icon(getContext(), id);
            weatherIcons.put(id, d);
        }
        return d;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = Math.round(width * HOME_H / HOME_W);
        if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) {
            int max = MeasureSpec.getSize(heightSpec);
            if (height > max) {
                height = max;
                width = Math.round(height * HOME_W / HOME_H);
            }
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (tiles.isEmpty()) loadTiles();
        main.postDelayed(walk, 1200);
        main.postDelayed(tick, 30_000);
    }

    @Override
    protected void onDetachedFromWindow() {
        main.removeCallbacksAndMessages(null);
        if (focusAnimator != null) focusAnimator.cancel();
        if (fadeAnimator != null) fadeAnimator.cancel();
        super.onDetachedFromWindow();
    }

    /** Shows {@code next}, cross-fading from what is on screen now. */
    void setState(State next) {
        crossFade();
        state = next;
        requestWallpaper(next.wallpaper);
        if (focus >= Math.max(1, next.columns)) {
            focus = 0;
            focusPos = 0;
        }
        invalidate();
    }

    /** Outlines the part of Home the focused setting changes. */
    void setEmphasis(int emphasis) {
        if (this.emphasis == emphasis) return;
        this.emphasis = emphasis;
        invalidate();
    }

    // --- loading -------------------------------------------------------------------------------------------

    private void requestWallpaper(String key) {
        wallpaperWanted = key;
        if (key == null) {
            wallpaper = null;
            wallpaperKey = null;
            return;
        }
        if (key.equals(wallpaperKey)) return;
        Bitmap cached = WALLPAPERS.get(key);
        if (cached != null) {
            wallpaper = cached;
            wallpaperKey = key;
            return;
        }
        Context app = getContext().getApplicationContext();
        LOADER.execute(() -> {
            Bitmap bitmap = decode(app, key);
            main.post(() -> {
                if (bitmap != null) WALLPAPERS.put(key, bitmap);
                if (!key.equals(wallpaperWanted)) return;
                crossFade();
                wallpaper = bitmap;
                wallpaperKey = key;
                invalidate();
            });
        });
    }

    private static Bitmap decode(Context ctx, String key) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            open(ctx, key, bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= 900) opts.inSampleSize *= 2;
            return open(ctx, key, opts);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static Bitmap open(Context ctx, String key, BitmapFactory.Options opts) throws Exception {
        if (key.startsWith("asset:")) {
            try (InputStream in = ctx.getAssets().open(key.substring(6))) {
                return BitmapFactory.decodeStream(in, null, opts);
            }
        }
        return BitmapFactory.decodeFile(key.substring(key.indexOf(':') + 1), opts);
    }

    /** The user's TV apps in roughly Home's order: the saved order, then dock_apps, then by name. */
    private void loadTiles() {
        Context ctx = getContext().getApplicationContext();
        LOADER.execute(() -> {
            List<Tile> loaded = new ArrayList<>();
            try {
                Config cfg = Config.load(ctx);
                PackageManager pm = ctx.getPackageManager();
                Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
                List<ResolveInfo> found = new ArrayList<>(pm.queryIntentActivities(intent, 0));
                Map<String, Integer> saved = Order.ranks(Order.load(Order.file(ctx)), cfg.dockApps);
                Map<ResolveInfo, String> labels = new HashMap<>();
                for (ResolveInfo r : found) labels.put(r, String.valueOf(r.loadLabel(pm)));
                Collections.sort(found, (a, b) -> {
                    String ka = new android.content.ComponentName(a.activityInfo.packageName, a.activityInfo.name).flattenToShortString();
                    String kb = new android.content.ComponentName(b.activityInfo.packageName, b.activityInfo.name).flattenToShortString();
                    int bySaved = Order.compare(saved, ka, kb);
                    if (bySaved != 0) return bySaved;
                    int da = cfg.dockApps.indexOf(a.activityInfo.packageName);
                    int db = cfg.dockApps.indexOf(b.activityInfo.packageName);
                    if (da != db) return da < 0 ? 1 : db < 0 ? -1 : Integer.compare(da, db);
                    return labels.get(a).compareToIgnoreCase(labels.get(b));
                });
                for (ResolveInfo r : found) {
                    String pkg = r.activityInfo.packageName;
                    if (pkg.equals(ctx.getPackageName()) || cfg.hiddenApps.contains(pkg)) continue;
                    Drawable banner = r.activityInfo.loadBanner(pm);
                    if (banner == null) banner = r.activityInfo.applicationInfo.loadBanner(pm);
                    Drawable art = banner != null ? banner : r.loadIcon(pm);
                    if (art == null) continue;
                    art = art.mutate();
                    loaded.add(new Tile(labels.get(r), art, banner != null, averageColor(art)));
                    if (loaded.size() >= MAX_TILES) break;
                }
            } catch (RuntimeException ignored) {
                // a preview without the user's apps still shows the layout
            }
            main.post(() -> {
                crossFade();
                tiles = loaded;
                invalidate();
            });
        });
    }

    private static int averageColor(Drawable art) {
        try {
            Bitmap one = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(one);
            art.setBounds(0, 0, 4, 4);
            art.draw(c);
            int r = 0, g = 0, b = 0, n = 0;
            for (int x = 0; x < 4; x++) {
                for (int y = 0; y < 4; y++) {
                    int p = one.getPixel(x, y);
                    if (Color.alpha(p) < 40) continue;
                    r += Color.red(p);
                    g += Color.green(p);
                    b += Color.blue(p);
                    n++;
                }
            }
            one.recycle();
            return n == 0 ? 0xFF3A4150 : Color.rgb(r / n, g / n, b / n);
        } catch (RuntimeException e) {
            return 0xFF3A4150;
        }
    }

    // --- animation -----------------------------------------------------------------------------------------

    private void moveFocus(int next) {
        if (focusAnimator != null) focusAnimator.cancel();
        float from = focusPos;
        focus = next;
        if (!state.animations) {
            focusPos = next;
            focusLift = 1f;
            invalidate();
            return;
        }
        focusAnimator = ValueAnimator.ofFloat(0f, 1f);
        focusAnimator.setDuration(state.advanced ? 420 : 260);
        focusAnimator.setInterpolator(state.advanced ? new OvershootInterpolator(1.1f) : new DecelerateInterpolator());
        focusAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            focusPos = from + (next - from) * t;
            focusLift = Math.min(1f, t * 1.4f);
            invalidate();
        });
        focusAnimator.start();
    }

    /** Snapshots the current picture so the next one fades in over it. */
    private void crossFade() {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || !isAttachedToWindow()) return;
        if (fadeFrom == null || fadeFrom.getWidth() != w || fadeFrom.getHeight() != h) {
            if (fadeFrom != null) fadeFrom.recycle();
            fadeFrom = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        fadeFrom.eraseColor(Color.TRANSPARENT);
        Canvas c = new Canvas(fadeFrom);
        drawScene(c, w, h);
        if (fadeAnimator != null) fadeAnimator.cancel();
        fadeAlpha = 1f;
        fadeAnimator = ValueAnimator.ofFloat(1f, 0f);
        fadeAnimator.setDuration(280);
        fadeAnimator.addUpdateListener(a -> {
            fadeAlpha = (float) a.getAnimatedValue();
            invalidate();
        });
        fadeAnimator.start();
    }

    // --- drawing -------------------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        drawScene(canvas, w, h);
        if (fadeFrom != null && fadeAlpha > 0f) {
            paint.reset();
            paint.setAlpha(Math.round(fadeAlpha * 255));
            canvas.drawBitmap(fadeFrom, 0, 0, paint);
        }
        float u = w / HOME_W;
        drawEmphasis(canvas, u, w, h);
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, 1.2f * u));
        paint.setColor(0x40FFFFFF);
        rect.set(0.5f, 0.5f, w - 0.5f, h - 0.5f);
        canvas.drawRoundRect(rect, 16 * u, 16 * u, paint);
    }

    private void drawScene(Canvas c, int w, int h) {
        float u = w / HOME_W;
        int save = c.save();
        clip.reset();
        rect.set(0, 0, w, h);
        clip.addRoundRect(rect, 16 * u, 16 * u, Path.Direction.CW);
        c.clipPath(clip);
        if (state.clockStyle != null) {
            drawClockScreensaver(c, u, w, h);
            c.restoreToCount(save);
            return;
        }
        c.drawColor(state.background | 0xFF000000);

        if (wallpaper != null && state.wallpaper != null) {
            float scale = Math.max(w / (float) wallpaper.getWidth(), h / (float) wallpaper.getHeight());
            int sw = Math.round(w / scale), sh = Math.round(h / scale);
            int sx = (wallpaper.getWidth() - sw) / 2, sy = (wallpaper.getHeight() - sh) / 2;
            src.set(sx, sy, sx + sw, sy + sh);
            bounds.set(0, 0, w, h);
            paint.reset();
            paint.setFilterBitmap(true);
            c.drawBitmap(wallpaper, src, bounds, paint);
            if (state.wallpaperDim > 0) c.drawColor(Color.argb(Math.round(state.wallpaperDim * 2.55f), 0, 0, 0));
        }

        Geometry g = geometry(u, h);
        Tile focused = tileAt(focus);
        int glow = focused == null ? 0xFF4A6FA5 : focused.color;
        if (state.ambientGlow) {
            float cx = g.tileX(Math.round(focusPos)) + g.tileW / 2f;
            float cy = g.dockTop + g.tileH / 2f;
            paint.reset();
            paint.setShader(new RadialGradient(cx, cy, 420 * u,
                    (glow & 0x00FFFFFF) | 0x66000000, glow & 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
        }

        drawHeader(c, u, w);

        if (state.dockTitle && focused != null) {
            text.reset();
            text.setAntiAlias(true);
            text.setColor(Color.WHITE);
            text.setTypeface(Typeface.DEFAULT);
            text.setTextSize(30 * u * g.scale);
            c.drawText(focused.label, g.left, g.dockTop - 28 * u * g.scale, text);
        }
        if (state.dockLibrary) drawLibraryPill(c, u, g);

        if (state.dockBand) {
            float pad = 15 * u * g.scale;
            rect.set(g.left - pad, g.dockTop - 14 * u * g.scale, g.right + pad, g.dockTop + g.tileH + 14 * u * g.scale);
            paint.reset();
            paint.setAntiAlias(true);
            int opacity = Math.max(0, Math.min(100, state.dockOpacity));
            if (state.dockGlass) {
                paint.setColor(Color.argb(Math.round(opacity * 0.6f), 255, 255, 255));
            } else {
                paint.setColor(Color.argb(Math.round(opacity * 2.0f), 18, 22, 30));
            }
            c.drawRoundRect(rect, 18 * u, 18 * u, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, (state.accent != 0 ? 1.5f : 1f) * u));
            paint.setColor(state.accent != 0 ? 0x8C000000 | state.accent : 0x38FFFFFF);
            c.drawRoundRect(rect, 18 * u, 18 * u, paint);
        }

        int columns = g.columns;
        for (int row = 0; row <= state.rowsBelow + 1; row++) {
            float top = g.dockTop + (row == 0 ? 0 : g.firstRowGap + (row - 1) * g.pitch);
            if (top > h) break;
            for (int col = 0; col < columns; col++) {
                int index = row * columns + col;
                if (row == 0 && col == focus) continue;
                drawTile(c, tileAt(index), g.tileX(col), top, g.tileW, g.tileH, u, 1f);
            }
        }
        drawFocus(c, g, u, focused, glow);
        c.restoreToCount(save);
    }

    /** Launcher settings' Clock screensaver row: the screensaver in the chosen style, at the preview's scale. */
    private void drawClockScreensaver(Canvas c, float u, int w, int h) {
        c.drawColor(Color.BLACK);
        if (wallpaper != null && state.wallpaper != null) {
            float scale = Math.max(w / (float) wallpaper.getWidth(), h / (float) wallpaper.getHeight());
            int sw = Math.round(w / scale), sh = Math.round(h / scale);
            int sx = (wallpaper.getWidth() - sw) / 2, sy = (wallpaper.getHeight() - sh) / 2;
            src.set(sx, sy, sx + sw, sy + sh);
            bounds.set(0, 0, w, h);
            paint.reset();
            paint.setFilterBitmap(true);
            c.drawBitmap(wallpaper, src, bounds, paint);
            c.drawColor(0x8C000000);
        } else {
            paint.reset();
            paint.setShader(new RadialGradient(w * 0.35f, h * 0.45f, w * 0.55f, 0x402B5CFF, 0x002B5CFF, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
        }
        boolean is24 = "24".equals(state.clockFormat)
                || (!"12".equals(state.clockFormat) && DateFormat.is24HourFormat(getContext()));
        Date now = new Date();
        String time = new SimpleDateFormat(is24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now);
        String ampm = is24 ? "" : new SimpleDateFormat("a", Locale.US).format(now).toUpperCase(Locale.ROOT);
        String date = new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now).toUpperCase(Locale.getDefault());
        boolean big = ClockScene.STYLE_BIG.equals(state.clockStyle);
        boolean flip = ClockScene.STYLE_FLIP.equals(state.clockStyle);
        float left = 150 * u;
        float y;
        text.reset();
        text.setAntiAlias(true);
        if (flip) {
            float cw = 170 * u, ch = 190 * u, gap = 28 * u, top = 110 * u;
            String hours = time.substring(0, time.indexOf(':'));
            String[] cards = {is24 && hours.length() < 2 ? "0" + hours : hours, time.substring(time.indexOf(':') + 1)};
            text.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            text.setTextSize(ch * 0.74f);
            text.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < 2; i++) {
                float l = left + i * (cw + gap);
                rect.set(l, top, l + cw, top + ch);
                paint.reset();
                paint.setAntiAlias(true);
                paint.setColor(0xFF1B1F27);
                c.drawRoundRect(rect, 14 * u, 14 * u, paint);
                text.setColor(0xF2FFFFFF);
                Paint.FontMetrics fm = text.getFontMetrics();
                c.drawText(cards[i], l + cw / 2f, top + ch / 2f - (fm.ascent + fm.descent) / 2f, text);
                paint.setColor(0xFF07090C);
                c.drawRect(l, top + ch / 2f - u, l + cw, top + ch / 2f + u, paint);
            }
            text.setTextAlign(Paint.Align.LEFT);
            y = top + ch + 40 * u;
        } else {
            float size = big ? 210 * u : 120 * u;
            float top = big ? 70 * u : 130 * u;
            text.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
            text.setColor(0xE6FFFFFF);
            text.setTextSize(size);
            text.setLetterSpacing(-0.04f);
            Paint.FontMetrics fm = text.getFontMetrics();
            float baseline = top - fm.ascent * 0.8f;
            c.drawText(time, left, baseline, text);
            float x = left + text.measureText(time) + 10 * u;
            text.setLetterSpacing(0.08f);
            if (!ampm.isEmpty()) {
                text.setTypeface(medium);
                text.setColor(0x99FFFFFF);
                text.setTextSize(big ? 32 * u : 22 * u);
                c.drawText(ampm, x, baseline, text);
            }
            y = baseline + (big ? 50 * u : 36 * u);
        }
        text.setTypeface(Typeface.DEFAULT);
        text.setColor(0x8CFFFFFF);
        text.setTextSize(big ? 24 * u : flip ? 20 * u : 18 * u);
        text.setLetterSpacing(0.14f);
        c.drawText(date, left + 6 * u, y, text);
        if (state.clockWeather && state.weatherText != null) {
            y += 36 * u;
            int is = Math.round(20 * u);
            Drawable icon = weatherIcon(state.weatherIcon);
            icon.setBounds(Math.round(left + 6 * u), Math.round(y - is + 3 * u), Math.round(left + 6 * u) + is, Math.round(y + 3 * u));
            icon.setAlpha(0xB3);
            icon.draw(c);
            icon.setAlpha(0xFF);
            text.setColor(0xB3FFFFFF);
            text.setLetterSpacing(0.06f);
            c.drawText(state.weatherText, left + 6 * u + is + 10 * u, y, text);
        }
        text.setLetterSpacing(0f);
    }

    /** Home's App library button: a pill at the right end of the dock title's line. */
    private void drawLibraryPill(Canvas c, float u, Geometry g) {
        String name = getContext().getString(R.string.app_library);
        text.reset();
        text.setAntiAlias(true);
        text.setTypeface(medium);
        text.setTextSize(13 * u);
        float h = 36 * u, is = 17 * u;
        float w = 14 * u + is + 8 * u + text.measureText(name) + 16 * u;
        float cy = g.dockTop - 28 * u * g.scale - 10 * u * g.scale;
        rect.set(g.right + 8 * u - w, cy - h / 2f, g.right + 8 * u, cy + h / 2f);
        paint.reset();
        paint.setAntiAlias(true);
        paint.setColor(0x2EFFFFFF);
        c.drawRoundRect(rect, h / 2f, h / 2f, paint);
        appsIcon.setBounds(Math.round(rect.left + 14 * u), Math.round(cy - is / 2f),
                Math.round(rect.left + 14 * u + is), Math.round(cy + is / 2f));
        appsIcon.draw(c);
        text.setColor(Color.WHITE);
        c.drawText(name, rect.left + 14 * u + is + 8 * u, cy + 4.5f * u, text);
    }

    private void drawFocus(Canvas c, Geometry g, float u, Tile focused, int glow) {
        // The tile grows in its own place; only the outline glides from the previous tile, as on Home.
        float x = g.columns == 1 ? g.tileX(0) : g.tileX(0) + (g.tileX(1) - g.tileX(0)) * focusPos;
        float tileX = g.tileX(Math.min(focus, g.columns - 1));
        float y = g.dockTop;
        float scale = 1f + (state.animations ? (state.advanced ? 0.12f : 0.07f) * focusLift : 0.05f);
        float fw = g.tileW * scale, fh = g.tileH * scale;
        float lift = state.advanced ? 5 * u * focusLift : 0;
        float tx = tileX - (fw - g.tileW) / 2f, fy = y - (fh - g.tileH) / 2f - lift;
        float fx = x - (fw - g.tileW) / 2f;
        if (state.advanced) {
            paint.reset();
            paint.setAntiAlias(true);
            for (int i = 4; i >= 1; i--) {
                paint.setColor(Color.argb(22, 0, 0, 0));
                rect.set(tx - i * 2 * u, fy + 6 * u, tx + fw + i * 2 * u, fy + fh + (6 + i * 3) * u);
                c.drawRoundRect(rect, 12 * u, 12 * u, paint);
            }
        }
        drawTile(c, focused, tx, fy, fw, fh, u, 1f);
        int ring = state.appGlow ? (glow | 0xFF000000) : state.accent != 0 ? (state.accent | 0xFF000000) : Color.WHITE;
        String style = state.glassStyle == null ? "glass" : state.glassStyle;
        rect.set(fx - 4 * u, fy - 4 * u, fx + fw + 4 * u, fy + fh + 4 * u);
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        if ("halo".equals(style) || state.appGlow) {
            for (int i = 4; i >= 1; i--) {
                paint.setStrokeWidth(i * 3 * u);
                paint.setColor((ring & 0x00FFFFFF) | (0x18 << 24));
                c.drawRoundRect(rect, 12 * u, 12 * u, paint);
            }
        }
        paint.setStrokeWidth(2.4f * u);
        paint.setColor(ring);
        c.drawRoundRect(rect, 12 * u, 12 * u, paint);
        if ("glass".equals(style)) {
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(0, rect.top, 0, rect.top + fh * 0.5f,
                    0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRoundRect(rect, 12 * u, 12 * u, paint);
            paint.setShader(null);
        }
    }

    private void drawTile(Canvas c, Tile tile, float x, float y, float w, float h, float u, float alpha) {
        int save = c.save();
        clip.reset();
        rect.set(x, y, x + w, y + h);
        clip.addRoundRect(rect, 9 * u, 9 * u, Path.Direction.CW);
        c.clipPath(clip);
        if (tile == null) {
            c.drawColor(0x22FFFFFF);
        } else if (tile.banner) {
            tile.art.setBounds(Math.round(x), Math.round(y), Math.round(x + w), Math.round(y + h));
            tile.art.setAlpha(Math.round(alpha * 255));
            tile.art.draw(c);
        } else {
            c.drawColor(darken(tile.color));
            float s = h * 0.62f;
            tile.art.setBounds(Math.round(x + (w - s) / 2), Math.round(y + (h - s) / 2),
                    Math.round(x + (w + s) / 2), Math.round(y + (h + s) / 2));
            tile.art.setAlpha(Math.round(alpha * 255));
            tile.art.draw(c);
        }
        c.restoreToCount(save);
    }

    private static int darken(int color) {
        return Color.rgb(Color.red(color) * 2 / 3, Color.green(color) * 2 / 3, Color.blue(color) * 2 / 3);
    }

    private void drawHeader(Canvas c, float u, int w) {
        boolean is24 = "24".equals(state.clockFormat)
                || (!"12".equals(state.clockFormat) && DateFormat.is24HourFormat(getContext()));
        Date now = new Date();
        String time = new SimpleDateFormat(is24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now);
        String ampm = is24 ? "" : new SimpleDateFormat("a", Locale.US).format(now).toUpperCase(Locale.ROOT);
        String date = new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now).toUpperCase(Locale.getDefault());

        text.reset();
        text.setAntiAlias(true);
        text.setColor(0xCCFFFFFF);
        text.setTypeface(medium);
        text.setTextSize(11 * u);
        text.setLetterSpacing(0.18f);
        c.drawText(greeting(), 60 * u, 38 * u, text);

        text.setLetterSpacing(0f);
        text.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        text.setColor(Color.WHITE);
        text.setTextSize(46 * u);
        float x = 58 * u;
        c.drawText(time, x, 92 * u, text);
        x += text.measureText(time) + 6 * u;
        if (!ampm.isEmpty()) {
            text.setTypeface(medium);
            text.setTextSize(13 * u);
            c.drawText(ampm, x, 88 * u, text);
            x += text.measureText(ampm) + 10 * u;
        }
        paint.reset();
        paint.setColor(0x55FFFFFF);
        c.drawRect(x + 6 * u, 66 * u, x + 7 * u, 94 * u, paint);
        text.setTypeface(medium);
        text.setTextSize(12 * u);
        text.setLetterSpacing(0.12f);
        text.setColor(0xD9FFFFFF);
        c.drawText(date, x + 20 * u, 86 * u, text);
        if (state.weatherText != null) {
            float wx = x + 20 * u + text.measureText(date) + 16 * u;
            text.setLetterSpacing(0.04f);
            text.setTextSize(13 * u);
            int is = Math.round(15 * u);
            Drawable icon = weatherIcon(state.weatherIcon);
            icon.setBounds(Math.round(wx), Math.round(86 * u - is + 2 * u), Math.round(wx) + is, Math.round(86 * u + 2 * u));
            icon.draw(c);
            c.drawText(state.weatherText, wx + is + 5 * u, 86 * u, text);
        }
        text.setLetterSpacing(0f);

        float right = w - 57 * u;
        float cy = 80 * u;
        float r = 20 * u;
        List<Drawable> icons = new ArrayList<>(Arrays.asList(wifiIcon, nextIcon, paletteIcon, settingsIcon, moonIcon));
        List<String> labels = new ArrayList<>(Arrays.asList(getContext().getString(R.string.wifi), null, null,
                getContext().getString(R.string.settings), null));
        if (!state.nextWallpaper) {
            icons.remove(1);
            labels.remove(1);
        }
        for (int i = icons.size() - 1; i >= 0; i--) {
            String name = labels.get(i);
            boolean label = !state.iconOnlyButtons && name != null;
            text.setTypeface(medium);
            text.setTextSize(13 * u);
            float pw = label ? 2 * r + text.measureText(name) + 8 * u : 2 * r;
            rect.set(right - pw, cy - r, right, cy + r);
            paint.reset();
            paint.setAntiAlias(true);
            paint.setColor(0x2EFFFFFF);
            c.drawRoundRect(rect, r, r, paint);
            int is = Math.round(18 * u);
            int ix = Math.round(rect.left + r - is / 2f), iy = Math.round(cy - is / 2f);
            icons.get(i).setBounds(ix, iy, ix + is, iy + is);
            icons.get(i).draw(c);
            if (label) {
                text.setColor(Color.WHITE);
                c.drawText(name, rect.left + 2 * r - 2 * u, cy + 4.5f * u, text);
            }
            right = rect.left - 10 * u;
        }
    }

    private String greeting() {
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        int id = hour < 5 ? R.string.good_night : hour < 12 ? R.string.good_morning
                : hour < 17 ? R.string.good_afternoon : hour < 22 ? R.string.good_evening : R.string.good_night;
        try {
            return getContext().getString(id).toUpperCase(Locale.getDefault());
        } catch (RuntimeException e) {
            return "";
        }
    }

    private void drawEmphasis(Canvas c, float u, int w, int h) {
        if (emphasis == EMPHASIS_NONE) return;
        Geometry g = geometry(u, h);
        switch (emphasis) {
            case EMPHASIS_CLOCK: rect.set(48 * u, 20 * u, w * 0.62f, 104 * u); break;
            case EMPHASIS_HEADER: rect.set(w * 0.62f, 52 * u, w - 44 * u, 108 * u); break;
            case EMPHASIS_DOCK:
                rect.set(g.left - 24 * u, g.dockTop - 22 * u, g.right + 24 * u, g.dockTop + g.tileH + 22 * u); break;
            case EMPHASIS_TILES:
                rect.set(g.left - 24 * u, g.dockTop - 22 * u, g.right + 24 * u,
                        Math.min(h - 6 * u, g.dockTop + g.firstRowGap + Math.max(0, state.rowsBelow - 1) * g.pitch
                                + (state.rowsBelow > 0 ? g.tileH : 0) + 22 * u));
                break;
            case EMPHASIS_ROWS:
                if (state.rowsBelow == 0) {
                    rect.set(g.left - 24 * u, g.dockTop + g.tileH + 26 * u, g.right + 24 * u, h - 8 * u);
                } else {
                    rect.set(g.left - 24 * u, g.dockTop + g.firstRowGap - 16 * u, g.right + 24 * u,
                            Math.min(h - 6 * u, g.dockTop + g.firstRowGap + (state.rowsBelow - 1) * g.pitch + g.tileH + 18 * u));
                }
                break;
            default: return;
        }
        paint.reset();
        paint.setAntiAlias(true);
        paint.setColor((ACCENT & 0x00FFFFFF) | 0x16000000);
        c.drawRoundRect(rect, 14 * u, 14 * u, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f * u);
        paint.setPathEffect(new DashPathEffect(new float[] {9 * u, 6 * u}, 0));
        paint.setColor(ACCENT);
        c.drawRoundRect(rect, 14 * u, 14 * u, paint);
        paint.setPathEffect(null);
    }

    private Tile tileAt(int index) {
        return index >= 0 && index < tiles.size() ? tiles.get(index) : null;
    }

    /** Where the dock and rows sit, in pixels, for the current state. */
    private final class Geometry {
        final int columns;
        final float scale, left, right, gap, tileW, tileH, pitch, firstRowGap, dockTop;

        Geometry(float u, int h) {
            columns = Math.max(1, Math.min(8, state.columns));
            int rows = Math.max(0, Math.min(3, state.rowsBelow));
            left = 72 * u;
            right = (HOME_W - 72) * u;
            float baseGap = 24 * u;
            float fullW = (right - left - baseGap * (columns - 1)) / columns;
            float fullH = fullW * 0.5625f;
            float fullPitch = fullH + 58 * u;
            float top = (state.dockTitle ? 220 : 160) * u;
            float bottom = h - 34 * u;
            float need = fullH + rows * fullPitch;
            float avail = bottom - top - (rows == 0 ? 0 : 6 * u);
            scale = need > avail ? avail / need : 1f;
            tileW = fullW * scale;
            tileH = fullH * scale;
            gap = columns == 1 ? 0 : (right - left - tileW * columns) / (columns - 1);
            pitch = fullPitch * scale;
            firstRowGap = pitch;
            float preferred = h - 34 * u - need * scale - (rows == 0 ? 120 * u : 46 * u);
            dockTop = Math.max(top, preferred);
        }

        float tileX(int col) {
            return left + col * (tileW + gap);
        }
    }

    private Geometry geometry(float u, int h) {
        return new Geometry(u, h);
    }
}
