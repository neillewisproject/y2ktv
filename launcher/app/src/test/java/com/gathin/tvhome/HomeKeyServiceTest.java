package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HomeKeyServiceTest {
    private static final long SOON = 45_000L;

    @Test public void aTapIsAShortPress() {
        assertTrue(HomeKeyService.isShortPress(1_000, 1_120, 400));
    }

    @Test public void holdingPastTheTimeoutIsALongPress() {
        assertFalse(HomeKeyService.isShortPress(1_000, 1_400, 400));
        assertFalse(HomeKeyService.isShortPress(1_000, 3_000, 400));
    }

    @Test public void firstBindOfANewBootProbes() {
        assertTrue(HomeKeyService.firstBindAfterBoot(12, 11, SOON));
        assertTrue(HomeKeyService.firstBindAfterBoot(1, -1, SOON));
    }

    @Test public void aRebindInTheSameBootDoesNot() {
        assertFalse(HomeKeyService.firstBindAfterBoot(12, 12, SOON));
    }

    @Test public void aBindLongAfterBootDoesNot() {
        assertFalse(HomeKeyService.firstBindAfterBoot(12, 11, HomeKeyService.BOOT_WINDOW_MS));
    }

    @Test public void noBootCountMeansNoProbe() {
        assertFalse(HomeKeyService.firstBindAfterBoot(-1, -1, SOON));
    }

    private static final String TV = "com.mediatek.wwtv.tvcenter";

    @Test public void liveTvBeforeAnyKeyGoesHome() {
        assertTrue(HomeKeyService.powerOnRedirect(TV, TV, SOON, HomeKeyService.BOOT_WINDOW_MS));
    }

    @Test public void otherAppsAndInputsAreLeftAlone() {
        assertFalse(HomeKeyService.powerOnRedirect(TV, "com.netflix.ninja", SOON, HomeKeyService.BOOT_WINDOW_MS));
        assertFalse(HomeKeyService.powerOnRedirect(TV, null, SOON, HomeKeyService.BOOT_WINDOW_MS));
    }

    @Test public void afterAKeyOrTheWindowLiveTvStays() {
        assertFalse(HomeKeyService.powerOnRedirect(TV, TV, SOON, 0));
        assertFalse(HomeKeyService.powerOnRedirect(TV, TV, HomeKeyService.BOOT_WINDOW_MS, HomeKeyService.BOOT_WINDOW_MS));
    }

    @Test public void offMeansNothing() {
        assertFalse(HomeKeyService.powerOnRedirect("", TV, SOON, HomeKeyService.BOOT_WINDOW_MS));
    }

    @Test public void alwaysGoesHomeAfterScreenOnUntilAKey() {
        assertTrue(HomeKeyService.stillArmed(SOON, SOON + HomeKeyService.SCREEN_ON_WINDOW_MS));
        assertFalse(HomeKeyService.stillArmed(SOON, 0));
    }

    private static final String FIRE = "com.amazon.tv.launcher";
    private static final java.util.Set<String> FIRE_HOME =
            java.util.Collections.singleton("com.amazon.tv.launcher.ui.HomeActivity_vNext");

    @Test public void fireTvSettingsScreensAreWhitelisted() {
        assertTrue(HomeKeyService.isStockSettings("com.amazon.tv.launcher.ui.MainSettingsActivity"));
        assertTrue(HomeKeyService.isStockSettings("com.amazon.tv.launcher.ui.SettingsActivity"));
        assertFalse(HomeKeyService.isStockSettings("com.amazon.tv.launcher.ui.HomeActivity_vNext"));
        assertFalse(HomeKeyService.isStockSettings(null));
    }

    @Test public void theFireTvHomeIsTheStockHome() {
        assertTrue(HomeKeyService.isStockHomeWindow(FIRE, FIRE_HOME, FIRE,
                "com.amazon.tv.launcher.ui.HomeActivity_vNext"));
    }

    @Test public void settingsAndOtherAppsAreNot() {
        java.util.Set<String> withSettings = new java.util.HashSet<>(FIRE_HOME);
        withSettings.add("com.amazon.tv.launcher.ui.MainSettingsActivity");
        assertFalse(HomeKeyService.isStockHomeWindow(FIRE, withSettings, FIRE,
                "com.amazon.tv.launcher.ui.MainSettingsActivity"));
        assertFalse(HomeKeyService.isStockHomeWindow(FIRE, FIRE_HOME, "com.netflix.ninja",
                "com.amazon.tv.launcher.ui.HomeActivity_vNext"));
        assertFalse(HomeKeyService.isStockHomeWindow("", FIRE_HOME, FIRE,
                "com.amazon.tv.launcher.ui.HomeActivity_vNext"));
    }
    @Test public void threeQuickHomePressesTripTheFailsafe() {
        long[] taps = new long[3];
        assertFalse(HomeKeyService.recordTap(taps, 10_000, HomeKeyService.TRIPLE_TAP_MS));
        assertFalse(HomeKeyService.recordTap(taps, 10_400, HomeKeyService.TRIPLE_TAP_MS));
        assertTrue(HomeKeyService.recordTap(taps, 11_200, HomeKeyService.TRIPLE_TAP_MS));
        // The history is cleared, so a fourth press starts a new count.
        assertFalse(HomeKeyService.recordTap(taps, 11_300, HomeKeyService.TRIPLE_TAP_MS));
    }

    @Test public void fireOsSettingsWindowsOpenASettingsSession() {
        assertTrue(HomeKeyService.isSettingsWindow(FIRE, "com.amazon.tv.settings.v2", "android.widget.FrameLayout"));
        assertTrue(HomeKeyService.isSettingsWindow(FIRE, FIRE, "com.amazon.tv.launcher.ui.MainSettingsActivity"));
        assertFalse(HomeKeyService.isSettingsWindow(FIRE, FIRE, "com.amazon.tv.launcher.ui.HomeActivity_vNext"));
        assertFalse(HomeKeyService.isSettingsWindow(FIRE, "com.gathin.tvhome", "com.gathin.tvhome.MainActivity"));
        assertFalse(HomeKeyService.isSettingsWindow(FIRE, null, null));
    }

    @Test public void slowPressesDoNot() {
        long[] taps = new long[3];
        HomeKeyService.recordTap(taps, 10_000, HomeKeyService.TRIPLE_TAP_MS);
        HomeKeyService.recordTap(taps, 11_000, HomeKeyService.TRIPLE_TAP_MS);
        assertFalse(HomeKeyService.recordTap(taps, 11_600, HomeKeyService.TRIPLE_TAP_MS));
    }
}
