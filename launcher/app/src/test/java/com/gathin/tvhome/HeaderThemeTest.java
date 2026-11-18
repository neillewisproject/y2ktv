package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HeaderThemeTest {

    @Test public void inkKeepsTheWhitesAlpha() {
        assertEquals(0xFF1C1C1E, HeaderTheme.ink(0xFFFFFFFF));
        assertEquals(0x8C1C1C1E, HeaderTheme.ink(0x8CFFFFFF));
        assertEquals(0x331C1C1E, HeaderTheme.ink(0x33FFFFFF));
    }

    @Test public void eachShadeFadesOutDownTheScreen() {
        for (boolean light : new boolean[] {false, true}) {
            int[] colors = HeaderTheme.scrimColors(light);
            assertEquals(3, colors.length);
            assertEquals(0, colors[2] >>> 24);
            assertEquals(true, (colors[0] >>> 24) > (colors[1] >>> 24));
        }
    }

    @Test public void lightShadesWhiteAndDarkShadesBlack() {
        assertEquals(0xFFFFFF, HeaderTheme.scrimColors(true)[0] & 0xFFFFFF);
        assertEquals(0x000000, HeaderTheme.scrimColors(false)[0] & 0xFFFFFF);
    }

    @Test public void theGlowMutesLoudColoursTowardsGrey() {
        int red = HeaderTheme.mutedGlow(0xFFFF0000);
        assertEquals(0xFF, red >>> 24);
        assertEquals(true, ((red >> 16) & 0xFF) < 0xFF);   // less red
        assertEquals(true, ((red >> 8) & 0xFF) > 0);       // some grey mixed in
        assertEquals(true, ((red >> 16) & 0xFF) > ((red >> 8) & 0xFF));   // still reads as red
    }

    @Test public void greysAndTheAlphaAreLeftAlone() {
        assertEquals(0xFF808080, HeaderTheme.mutedGlow(0xFF808080));
        assertEquals(0xFF808080, HeaderTheme.mutedGlow(0x00808080));
    }
}
