package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sends the remote's Home button to this launcher on remotes that emit KEYCODE_ALL_APPS for Home
 * (Xiaomi sticks and boxes). Android TV 9 routes that key only to the system-image launcher, so once
 * the stock launcher is disabled the button does nothing. Every other key passes through untouched.
 *
 * With stock_home_redirect set, it also sends the stock launcher's home screen back here, for firmware that turns
 * that launcher on again after it was disabled (XGIMI's xrmservice does, on its own remote keys), or that cannot be
 * disabled at all, and a short press of Home opens this launcher even when the stock home is already on screen.
 * The stock launcher's Settings screens (Fire OS hosts them in com.amazon.tv.launcher) are never redirected, and
 * three Home presses within 1.5 s stand the redirect down until the next reboot (three more re-arm it).
 * Fire OS's key policy takes Home before any accessibility service sees it, so there HomePressSensor reports each
 * Home press, and the stock home's window event catches it coming up any other way.
 *
 * With power_on_home set, a Live TV window (live_tv_package) that the firmware opens by itself at power-on is sent
 * back here too, until the first key press after boot or after the screen turns on (Changhong / Homwee boards).
 * With power_on_home_always set, Home comes up at every power-on whatever is in front, for TVs that resume their
 * last source: a Philips back on an HDMI input sends no window event until the input has a picture.
 *
 * With home_long_press_home set, it takes every Home press, so one held past the long-press timeout opens this launcher
 * instead of Android's ALL_APPS action, which nothing answers once the stock launcher is disabled (Philips TPM191E).
 *
 * Enable over adb:
 *   settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 */
public class HomeKeyService extends AccessibilityService {

    /** restore_key_services was on when the config was read: this helper is noted for ServiceRepair. */
    private boolean repairRecord;
    /** Remote buttons that should open Settings (config.xml, settings_key_scancodes and settings_key_keycodes). */
    private int[] settingsScancodes = new int[0];
    private int[] settingsKeycodes = new int[0];
    private boolean settingsPanel;
    private boolean settingsClearTask;
    /** consume_volume_keys: Volume Up/Down go to VolumeKeys instead of on to the firmware. */
    private boolean consumeVolumeKeys;
    /** app_keys, the same maps InputKeyService reads, for setups that run only this service. */
    private Map<Integer, String> appKeys = Collections.emptyMap();
    private Map<Integer, String> appScancodes = Collections.emptyMap();
    private Map<Integer, String> holdAppKeys = Collections.emptyMap();
    private Map<Integer, String> holdAppScancodes = Collections.emptyMap();

    /** Waits for the user to unlock when Android binds this service at boot; null once the config is read. */
    private BroadcastReceiver unlockReceiver;
    /** The stock launcher's HOME activities (stock_home_redirect), disabled ones included. */
    private final Set<String> stockHome = new HashSet<>();
    /** stock_home_redirect, the stock launcher's package. */
    private String stockHomePackage = "";
    /** power_on_home: the Live TV package to send back to Home, empty when off; armed until this uptime or a key. */
    private String powerOnLiveTv = "";
    private long powerOnUntil;
    /** home_long_press_home: this service takes every Home press, held ones included. */
    private boolean homeLongPressHome;
    /** app_keys "front:" entries; the package in front goes to RemoteKeys.frontPackage, for both helpers. */
    private Map<Integer, String> frontAppKeys = Collections.emptyMap();
    private Map<Integer, String> frontAppScancodes = Collections.emptyMap();
    /** power_on_home_always: Home at every power-on, not only over Live TV. */
    private boolean powerOnAlways;
    /** power_on_home: re-arms after the screen turns on; null unless the key is on. */
    private BroadcastReceiver screenOnReceiver;
    /** Stops key filtering while a game controller is connected or after a volume change; null unless gamepad_passthrough or volume_pauses_filter, and key_filter, or before the unlock. */
    private GamepadPassthrough gamepads;
    /** Screen-off receiver while guard_off_in_standby is set; null otherwise. */
    private BroadcastReceiver standbyExit;
    /** disableSelf is how this service leaves for standby, not the user turning it off. */
    private boolean steppingOut;
    /** The bound helper, so the opt-in broadcast can arm it without waiting for a new connection. */
    static HomeKeyService running;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        running = this;
        // Fire OS: ask for window transitions up front, before the config is read, so the stock home
        // (com.amazon.tv.launcher) coming up is never missed while the service waits for the unlock.
        AccessibilityServiceInfo windowEvents = getServiceInfo();
        if (windowEvents != null) {
            windowEvents.eventTypes |= AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            setServiceInfo(windowEvents);
        }
        settingsScancodes = getResources().getIntArray(R.array.settings_key_scancodes);
        // The service is directBootAware: Android 11 skips a service that is not while the user is still
        // locked at boot, writes accessibility_enabled=0 and, on TCL G03 firmware, never binds it after the
        // unlock. Config.load reads credential-protected storage, so wait for the unlock before reading it.
        UserManager users = getSystemService(UserManager.class);
        if (users.isUserUnlocked()) {
            loadConfig();
            return;
        }
        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                stopWaitingForUnlock();
                loadConfig();
            }
        };
        registerReceiver(unlockReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        // The unlock may have happened between the check and the registration.
        if (users.isUserUnlocked()) {
            stopWaitingForUnlock();
            loadConfig();
        }
    }

    private void stopWaitingForUnlock() {
        if (unlockReceiver == null) return;
        unregisterReceiver(unlockReceiver);
        unlockReceiver = null;
    }

    /** The opt-in broadcast: arm this binding now, or drop the receiver if the opt-in was turned off. */
    static void applyStandbyOptIn() {
        HomeKeyService service = running;
        if (service == null) return;
        if (GuardKeep.offInStandby(service)) service.armStandbyExit();
        else service.disarmStandbyExit();
    }

    @Override
    public void onDestroy() {
        if (running == this) running = null;
        if (homeSensor != null) {
            homeSensor.detach();
            homeSensor = null;
        }
        RemoteKeys.frontPackage = "";
        RemoteKeys.release(this);
        stopWaitingForUnlock();
        if (screenOnReceiver != null) unregisterReceiver(screenOnReceiver);
        if (standbyExit != null) {
            try {
                unregisterReceiver(standbyExit);
            } catch (RuntimeException ignored) {
            }
            standbyExit = null;
        }
        if (gamepads != null) gamepads.stop();
        if (consumeVolumeKeys) VolumeKeys.stop();
        super.onDestroy();
    }

    /** Some TCL boards never wake from the remote while this helper stays bound with the screen off. */
    private void armStandbyExit() {
        if (standbyExit != null || !GuardKeep.offInStandby(this)) return;
        standbyExit = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) return;
                leaveForStandby();
            }
        };
        registerReceiver(standbyExit, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        PowerManager power = getSystemService(PowerManager.class);
        if (power != null && !power.isInteractive()) leaveForStandby();
    }

    private void disarmStandbyExit() {
        if (standbyExit == null) return;
        try {
            unregisterReceiver(standbyExit);
        } catch (RuntimeException ignored) {
        }
        standbyExit = null;
    }

    private void leaveForStandby() {
        steppingOut = true;
        GuardKeep.stepOut(this);
    }

    private void loadConfig() {
        // Keycodes and the panel choice can also come from a theme; theme.sh restarts this service.
        Config config = Config.load(this);
        // restore_key_services: note that this helper runs, so Home can put it back after a force stop. Here, not
        // in onServiceConnected: the note is in credential-protected storage too, readable only after the unlock.
        repairRecord = config.restoreKeyServices;
        if (repairRecord) ServiceRepair.connected(this, getClass());
        settingsKeycodes = config.settingsKeycodes;
        settingsPanel = config.settingsKeyPanel;
        settingsClearTask = config.settingsKeyClearTask;
        consumeVolumeKeys = config.consumeVolumeKeys;
        appKeys = RemoteKeys.parseAppKeys(config.appKeys);
        appScancodes = RemoteKeys.parseAppScancodes(config.appKeys);
        RemoteKeys.applyKeyFilter(this, config.keyFilter);
        homeLongPressHome = config.homeLongPressHome;
        frontAppKeys = RemoteKeys.parseFrontAppKeys(config.appKeys);
        frontAppScancodes = RemoteKeys.parseFrontAppScancodes(config.appKeys);
        boolean watchFront = !frontAppKeys.isEmpty() || !frontAppScancodes.isEmpty();
        stockHome.clear();
        stockHomePackage = config.stockHomeRedirect;
        if (!config.stockHomeRedirect.isEmpty()) {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(config.stockHomeRedirect);
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, PackageManager.MATCH_DISABLED_COMPONENTS)) {
                stockHome.add(ri.activityInfo.name);
            }
            Log.i("HomeKeyService", "stock_home_redirect " + stockHomePackage + ": " + stockHome);
            if (homeSensor == null) homeSensor = HomePressSensor.attach(this, this::onHomePressed);
        }
        // power_on_home, off by default. Not with boot_to_live_tv, or with Live TV picked as the Open on start app:
        // those open Live TV on purpose at boot.
        String openOnStart = config.openOnStartChoice
                ? getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).getString(OpenOnStart.KEY, "") : "";
        powerOnLiveTv = config.powerOnHome && !config.bootToLiveTv && !config.liveTvPackage.equals(openOnStart)
                ? config.liveTvPackage : "";
        powerOnAlways = config.powerOnHomeAlways && !config.bootToLiveTv && openOnStart.isEmpty();
        if (!powerOnLiveTv.isEmpty() || powerOnAlways) {
            armPowerOnHome();
        } else if (screenOnReceiver != null) {
            unregisterReceiver(screenOnReceiver);
            screenOnReceiver = null;
            powerOnUntil = 0;
        }
        // home_key_service.xml subscribes to no events, so the redirects ask for window changes only when one is on.
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null && (!stockHome.isEmpty() || !powerOnLiveTv.isEmpty() || watchFront)) {
            info.eventTypes |= AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            setServiceInfo(info);
        }
        if (!stockHome.isEmpty() || !powerOnLiveTv.isEmpty()) probeAtBoot();
        armStandbyExit();
        holdAppKeys = RemoteKeys.parseHoldAppKeys(config.appKeys);
        holdAppScancodes = RemoteKeys.parseHoldAppScancodes(config.appKeys);
        // gamepad_passthrough and volume_pauses_filter, off by default.
        if (gamepads == null) gamepads = GamepadPassthrough.start(this, config);
    }

    /** A tap's target: the "front:" entry while the button's own app is already in front, else the usual one. */
    private String inFront(Map<Integer, String> keys, Map<Integer, String> front, int code) {
        return RemoteKeys.tap(keys.get(code), front.get(code));
    }

    private boolean isSettingsScancode(KeyEvent event) {
        for (int code : settingsScancodes) if (event.getScanCode() == code) return true;
        return false;
    }

    private boolean isSettingsKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) return true;
        if (isSettingsScancode(event)) return true;
        for (int code : settingsKeycodes) if (event.getKeyCode() == code) return true;
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        // power_on_home: the first key after power-on hands the TV to the user (power and wake keys don't count).
        if (powerOnUntil != 0 && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getKeyCode() != KeyEvent.KEYCODE_POWER && event.getKeyCode() != KeyEvent.KEYCODE_WAKEUP) {
            powerOnUntil = 0;
        }
        if (consumeVolumeKeys && VolumeKeys.handle(this, event)) return true;
        // A scancode entry is more specific than any keycode, so it goes first.
        if (RemoteKeys.handle(this, event, inFront(appScancodes, frontAppScancodes, event.getScanCode()),
                holdAppScancodes.get(event.getScanCode()))) return true;
        // A keycode app_keys entry with a tap target wins over the Settings keycodes (InputKeyService opens it too, and
        // RemoteKeys answers a press once), but not over a button named in settings_key_scancodes. A hold-only entry
        // leaves the tap to Settings and still runs its hold.
        boolean settingsKey = isSettingsKey(event);
        String tap = inFront(appKeys, frontAppKeys, event.getKeyCode());
        String hold = holdAppKeys.get(event.getKeyCode());
        if ((!settingsKey || (tap != null && !isSettingsScancode(event))) && RemoteKeys.handle(this, event, tap, hold)) {
            return true;
        }
        // The stock launcher handled the remote's Settings button; with it disabled the button does nothing.
        if (settingsKey) {
            RemoteKeys.handle(this, event, null, hold);
            boolean held = hold != null && event.getEventTime() - event.getDownTime() >= RemoteKeys.HOLD_MS;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled() && !held) {
                Intent open = settingsPanel ? new Intent(this, QuickSettingsActivity.class)
                        : SystemSettings.intent(this, Settings.ACTION_SETTINGS);
                // settings_key_clear_task: on MediaTek boards KEYCODE_SETTINGS is also a global key that opens Display &
                // Sound in the Settings task before this helper sees it; cleared, Back from Settings goes Home, not there.
                if (settingsClearTask && !settingsPanel) open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                try {
                    startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException | SecurityException unavailable) {
                    Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        }
        // home_long_press_home: Android never sees the Home key, so a held press cannot turn into ALL_APPS.
        if (homeLongPressHome && event.getKeyCode() == KeyEvent.KEYCODE_HOME) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) openHome();
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME && !stockHome.isEmpty()) {
            redirectHomeKey(event);
            return false;
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_ALL_APPS) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) openHome();
        return true;
    }

    /** Unbound by Android: the user turned the helper off, so Home doesn't put it back (ServiceRepair). */
    @Override
    public boolean onUnbind(Intent intent) {
        RemoteKeys.release(this);
        if (repairRecord && !steppingOut) ServiceRepair.disconnected(this, getClass());
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Fire OS 7 Settings tab: only subscribed while watching it (watchSettingsTab).
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (settingsWatch && event.getPackageName() != null
                    && stockHomePackage.contentEquals(event.getPackageName())) {
                scheduleSettingsCheck(SETTINGS_CHECK_MS);
            }
            return;
        }
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        // app_keys "front:" entries: only activities count, so a toast or the volume bar doesn't change the app in front.
        if ((!frontAppKeys.isEmpty() || !frontAppScancodes.isEmpty())
                && event.getPackageName() != null && event.getClassName() != null) {
            try {
                ComponentName window = new ComponentName(event.getPackageName().toString(), event.getClassName().toString());
                getPackageManager().getActivityInfo(window, 0);
                RemoteKeys.frontPackage = window.getPackageName();
            } catch (PackageManager.NameNotFoundException notAnActivity) {
                // a dialog, panel or overlay: the app under it is still in front
            }
        }
        // power_on_home: the firmware opened Live TV by itself at power-on, before anyone pressed a key.
        if (powerOnRedirect(powerOnLiveTv, event.getPackageName(), SystemClock.elapsedRealtime(), powerOnUntil)) {
            openHome();
            return;
        }
        // stock_home_redirect: the stock launcher's home screen came up (Fire OS brings it back on every Home press).
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        if (stockHomePackage.isEmpty() || pkg == null) return;
        if (isStockHomeWindow(stockHomePackage, stockHome, pkg, cls)) {
            onStockHome(SystemClock.uptimeMillis());
        } else if (isSettingsWindow(stockHomePackage, pkg, cls)) {
            afterSettings = true;
            stopSettingsWatch();
        } else if (isActivityWindow(pkg, cls)) {
            afterSettings = false;
            stopSettingsWatch();
        }
    }

    /** One Home press can bring the stock home's window event twice; closer together they count as one press. */
    static final long STOCK_HOME_DEBOUNCE_MS = 250L;
    /** Three Home presses within this window stand stock_home_redirect down (or re-arm it) until the next reboot. */
    static final long TRIPLE_TAP_MS = 1500L;
    private final long[] homeTaps = new long[3];
    private boolean redirectStoodDown;
    private long lastStockHomeEvent;
    /** The last window was a Settings screen (isSettingsWindow). */
    private boolean afterSettings;
    private final Runnable openHomeLater = this::openHome;
    /** stock_home_redirect: hears every Home press, which Fire OS never sends to onKeyEvent; null if refused. */
    private HomePressSensor homeSensor;

    /** A Home press, from anywhere: Fire OS's own Settings tab and its home screen included, where Home brings no
     *  window change. Counts toward the failsafe, then opens this launcher unless the redirect stands down. */
    private void onHomePressed() {
        afterSettings = false;
        stopSettingsWatch();
        countHomePress(SystemClock.uptimeMillis());
        if (!redirectStoodDown) openHome();
    }

    /** Failsafe: three quick Home presses toggle the redirect, so the stock home is always reachable. */
    private void countHomePress(long now) {
        if (!recordTap(homeTaps, now, TRIPLE_TAP_MS)) return;
        redirectStoodDown = !redirectStoodDown;
        Toast.makeText(this, redirectStoodDown ? R.string.stock_home_stood_down : R.string.stock_home_rearmed,
                Toast.LENGTH_LONG).show();
        if (redirectStoodDown) {
            cancelPendingHome();
            // The first two presses already brought this launcher back; go to the stock home now, not on the next press.
            handler.postDelayed(this::openStockHome, STOCK_HOME_AFTER_TRIPLE_MS);
        }
    }

    /** Lets this launcher's start from the earlier presses land first, so the stock home ends up in front. */
    static final long STOCK_HOME_AFTER_TRIPLE_MS = 500L;

    private void openStockHome() {
        if (!redirectStoodDown || stockHomePackage.isEmpty()) return;
        Intent stock = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(stockHomePackage)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            if (!startFromSystem(stock)) startActivity(stock);
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            // The next Home press still reaches the stock home, as it did before.
        }
    }

    /** The stock home came to the front: after Home, Back out of an app, or at boot. */
    private void onStockHome(long now) {
        boolean newPress = now - lastStockHomeEvent > STOCK_HOME_DEBOUNCE_MS;
        lastStockHomeEvent = now;
        if (settingsWatch) return;
        if (afterSettings) {
            afterSettings = false;
            watchSettingsTab(now);
            return;
        }
        // Without the sensor, the stock home coming up is the best count of Home presses there is.
        if (homeSensor == null && newPress) countHomePress(now);
        if (!redirectStoodDown) openHome();
    }

    /** Fire OS 7 shows Settings as a tab of the stock home activity itself: com.amazon.tv.settings.v2's trampoline
     *  reopens HomeActivity_vNext, and Back from a Settings screen lands there too, and moving to another tab
     *  brings no window event. So when the stock home follows a Settings window, its content decides: while the
     *  Settings cards are on screen it is left alone; once they are gone (the user moved to another tab), or if
     *  they never show up (Back from a Settings screen to the home tab), this launcher opens. Home itself is
     *  reported by HomePressSensor. */
    static final String SETTINGS_TAB_MARKER = ":id/settings_card_view";
    /** How long the stock home gets to show its Settings cards before it counts as the home screen. */
    static final long SETTINGS_TAB_DEADLINE_MS = 2000L;
    static final long SETTINGS_CHECK_MS = 300L;
    private boolean settingsWatch;
    private boolean settingsTabSeen;
    private long settingsWatchDeadline;
    private boolean settingsCheckPosted;
    private final Runnable settingsCheck = this::checkSettingsTab;

    private void watchSettingsTab(long now) {
        settingsWatch = true;
        settingsTabSeen = false;
        settingsWatchDeadline = now + SETTINGS_TAB_DEADLINE_MS;
        setContentEvents(true);
        scheduleSettingsCheck(SETTINGS_CHECK_MS);
    }

    private void stopSettingsWatch() {
        if (!settingsWatch) return;
        settingsWatch = false;
        settingsCheckPosted = false;
        handler.removeCallbacks(settingsCheck);
        setContentEvents(false);
    }

    private void scheduleSettingsCheck(long delay) {
        if (settingsCheckPosted) return;
        settingsCheckPosted = true;
        handler.postDelayed(settingsCheck, delay);
    }

    private void checkSettingsTab() {
        settingsCheckPosted = false;
        if (!settingsWatch) return;
        Boolean shown = settingsTabShown();
        boolean timedOut = SystemClock.uptimeMillis() > settingsWatchDeadline;
        if (Boolean.TRUE.equals(shown)) {
            settingsTabSeen = true;
        } else if (settingsTabSeen || timedOut) {
            stopSettingsWatch();
            if (Boolean.FALSE.equals(shown) && !redirectStoodDown) openHome();
            return;
        }
        // Content events wake the check while the tab is up; this one also covers the wait for the first draw.
        if (!settingsTabSeen) scheduleSettingsCheck(SETTINGS_CHECK_MS);
    }

    /** Whether the stock launcher's Settings tab is on screen; null when another app's window is in front. */
    private Boolean settingsTabShown() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        try {
            if (root.getPackageName() == null || !stockHomePackage.contentEquals(root.getPackageName())) return null;
            List<AccessibilityNodeInfo> cards =
                    root.findAccessibilityNodeInfosByViewId(stockHomePackage + SETTINGS_TAB_MARKER);
            for (AccessibilityNodeInfo card : cards) card.recycle();
            return !cards.isEmpty();
        } finally {
            root.recycle();
        }
    }

    /** Content changes are many; they are asked for only while the Settings tab is watched. */
    private void setContentEvents(boolean on) {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        if (on) {
            info.eventTypes |= AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        } else {
            info.eventTypes &= ~AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
            info.flags &= ~AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        }
        setServiceInfo(info);
    }

    /** Records a Home press; true (and the history cleared) when it is the last of three within window ms. */
    static boolean recordTap(long[] taps, long now, long window) {
        System.arraycopy(taps, 1, taps, 0, taps.length - 1);
        taps[taps.length - 1] = now;
        if (taps[0] == 0 || now - taps[0] > window) return false;
        Arrays.fill(taps, 0);
        return true;
    }

    /** Fire TV Settings lives inside com.amazon.tv.launcher (.ui.SettingsActivity, .ui.MainSettingsActivity):
     *  any of its Settings screens is left alone, never redirected. */
    static boolean isStockSettings(CharSequence cls) {
        if (cls == null) return false;
        String name = cls.toString();
        String simple = name.substring(name.lastIndexOf('.') + 1);
        return simple.equals("MainSettingsActivity") || name.contains("Settings");
    }

    /** A Settings screen: one of the stock launcher's Settings activities, or any window of a settings package
     *  (com.amazon.tv.settings.v2 on Fire OS, its trampoline, network and HUD screens included). */
    static boolean isSettingsWindow(String stockPackage, CharSequence pkg, CharSequence cls) {
        if (pkg == null) return false;
        if (stockPackage.contentEquals(pkg)) return isStockSettings(cls);
        return pkg.toString().contains(".settings");
    }

    /** Only an activity, not a toast, dialog or overlay, means another app came to the front. */
    private boolean isActivityWindow(CharSequence pkg, CharSequence cls) {
        if (cls == null) return false;
        try {
            getPackageManager().getActivityInfo(new ComponentName(pkg.toString(), cls.toString()), 0);
            return true;
        } catch (PackageManager.NameNotFoundException notAnActivity) {
            return false;
        }
    }

    /** Whether a window is the stock launcher's home screen (never one of its Settings screens). */
    static boolean isStockHomeWindow(String stockPackage, Set<String> homeActivities, CharSequence pkg,
                                     CharSequence cls) {
        if (stockPackage.isEmpty() || pkg == null || cls == null) return false;
        if (!stockPackage.contentEquals(pkg)) return false;
        if (isStockSettings(cls)) return false;
        return homeActivities.contains(cls.toString());
    }

    /** How long power_on_home stays armed after the screen turns on without a reboot. */
    static final long SCREEN_ON_WINDOW_MS = 60 * 1000L;

    /** power_on_home: armed for the boot window (if this bind is part of the boot) and after every screen-on. */
    private void armPowerOnHome() {
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) {
            powerOnUntil = BOOT_WINDOW_MS;
            if (powerOnAlways) handler.postDelayed(this::homeAtPowerOn, POWER_ON_ALWAYS_DELAY_MS);
        }
        if (screenOnReceiver != null) return;
        screenOnReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                powerOnUntil = SystemClock.elapsedRealtime() + SCREEN_ON_WINDOW_MS;
                if (powerOnAlways) handler.postDelayed(HomeKeyService.this::homeAtPowerOn, POWER_ON_ALWAYS_DELAY_MS);
            }
        };
        registerReceiver(screenOnReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    /** power_on_home_always: after the firmware has brought its last source back (Philips: within 0.5 s). */
    static final long POWER_ON_ALWAYS_DELAY_MS = 800L;

    /** power_on_home_always: Home, unless a key was pressed since the screen came on (the user has the TV). */
    private void homeAtPowerOn() {
        if (stillArmed(SystemClock.elapsedRealtime(), powerOnUntil)) openHome();
    }

    static boolean stillArmed(long now, long armedUntil) {
        return now < armedUntil;
    }

    /** The settings menu offers Restart only while this helper is bound: it opens the system power menu. */
    static boolean canOpenPowerMenu() {
        return running != null;
    }

    /** The system power menu (Restart, and Power off where the firmware has it); the TV asks before it acts. */
    static void openPowerMenu() {
        HomeKeyService service = running;
        if (service != null) service.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
    }

    static boolean powerOnRedirect(String liveTv, CharSequence pkg, long now, long armedUntil) {
        return !liveTv.isEmpty() && pkg != null && liveTv.contentEquals(pkg) && now < armedUntil;
    }

    /** How long after boot the first bind still counts as "at boot" for probeAtBoot. */
    static final long BOOT_WINDOW_MS = 3 * 60 * 1000L;

    /** stock_home_redirect at boot: the stock home is often on screen before Android binds this service, so no
     *  window event for it arrives (#1116). Once per boot, open ProbeActivity: when it closes, whatever was in
     *  front comes back and sends a window event, and onAccessibilityEvent covers it only if it is the stock home.
     *  An HDMI input or an app that the TV resumed at boot is left alone. */
    private void probeAtBoot() {
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        SharedPreferences prefs = getSharedPreferences("boot", MODE_PRIVATE);
        if (!firstBindAfterBoot(boot, prefs.getInt("probe_boot", -1), SystemClock.elapsedRealtime())) return;
        prefs.edit().putInt("probe_boot", boot).apply();
        try {
            startActivity(new Intent(this, ProbeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
    }

    static boolean firstBindAfterBoot(int boot, int lastBoot, long uptimeMs) {
        return boot >= 0 && boot != lastBoot && uptimeMs < BOOT_WINDOW_MS;
    }

    /** Lets the system see the key, so a long press still does what the firmware gives it. */
    private static final long HOME_KEY_DELAY_MS = 250;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** stock_home_redirect on the Home key: when the stock home is already on screen (it came up at boot, before
     *  Android bound this service), Home brings it up again with no window change, so the redirect above never
     *  fires and the TV stays on the stock home until another app opens (#1116). A short press opens this
     *  launcher shortly after the system has handled the key; a long press (Google TV's quick settings) is left
     *  alone. Held time, not repeats: key repeats are made after accessibility filtering, so none arrive here.
     *  Fire OS never delivers Home to this filter at all; HomePressSensor reports it there instead. */
    private void redirectHomeKey(KeyEvent event) {
        if (!redirectStoodDown && event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()
                && isShortPress(event.getDownTime(), event.getEventTime(), ViewConfiguration.getLongPressTimeout())) {
            handler.postDelayed(openHomeLater, HOME_KEY_DELAY_MS);
        }
    }

    static boolean isShortPress(long downTime, long upTime, long longPressTimeout) {
        return upTime - downTime < longPressTimeout;
    }

    private void openHome() {
        boolean stockStays = stockHomeStays();
        Intent open = stockStays ? new Intent(this, MainActivity.class)
                : new Intent(Intent.ACTION_MAIN).setPackage(getPackageName());
        open.addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (stockStays && startFromSystem(open)) return;
        startActivity(open);
    }

    /** The stock home just came up after Home, and the system now holds back every app's activity starts for 5 s
     *  (stopAppSwitches; Fire OS's launcher never lifts it), so a plain startActivity lands seconds late. An
     *  activity PendingIntent fired by an alarm is sent by the system, which is exempt, and lands at once.
     *  Before Android 12 only: from there exact alarms need a permission, and starts are gated differently. */
    private boolean startFromSystem(Intent open) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return false;
        AlarmManager alarms = getSystemService(AlarmManager.class);
        if (alarms == null) return false;
        alarms.setExact(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime(),
                homePendingIntent(open, PendingIntent.FLAG_UPDATE_CURRENT));
        return true;
    }

    /** Failsafe: a redirect already scheduled by startFromSystem must not land over the stock home. */
    private void cancelPendingHome() {
        handler.removeCallbacks(openHomeLater);
        AlarmManager alarms = getSystemService(AlarmManager.class);
        PendingIntent pending = homePendingIntent(new Intent(this, MainActivity.class)
                .addCategory(Intent.CATEGORY_HOME), PendingIntent.FLAG_NO_CREATE);
        if (alarms != null && pending != null) alarms.cancel(pending);
    }

    private PendingIntent homePendingIntent(Intent open, int flags) {
        return PendingIntent.getActivity(this, 0, open, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    /** stock_home_redirect while Android's Home is still the stock launcher (launcherx on Google TV outranks this
     *  one; Fire OS's com.amazon.tv.launcher is protected and always stays Home). A Home intent would then put this
     *  launcher in the home task and make it the home process, and from Android 10 the stock launcher, no longer
     *  exempt from the limits on background activity starts, can't open apps for the remote's YouTube and Netflix
     *  buttons (#1274). openHome starts it with an explicit intent instead, on every Android version:
     *  CATEGORY_HOME without ACTION_MAIN is not a Home intent, but still sends MainActivity back to the first tile.
     *  A MainActivity already in the home task, as the install scripts' first start leaves it, is reused as it is
     *  until that task goes (a restart or a reinstall). With this launcher or Android's chooser as Home, openHome
     *  keeps the Home intent. */
    private boolean stockHomeStays() {
        if (stockHome.isEmpty()) return false;
        ResolveInfo home = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        return home != null && stockHomePackage.equals(home.activityInfo.packageName);
    }

    @Override
    public void onInterrupt() {
    }
}
