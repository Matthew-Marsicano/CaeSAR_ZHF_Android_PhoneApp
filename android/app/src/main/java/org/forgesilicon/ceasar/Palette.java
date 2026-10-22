package org.forgesilicon.ceasar;

import android.graphics.Color;

/** Forge Silicon Design colors: orange on warm charcoal, or on cream in light mode. */
public final class Palette {
    public final boolean dark;
    public final int bg, card, tint, text, muted, accent, onAccent, grid, chip, heater, sleep, good, warn, alert;

    public Palette(boolean dark) {
        this.dark = dark;
        bg = dark ? Color.rgb(20, 17, 15) : Color.rgb(251, 246, 239);
        card = dark ? Color.rgb(34, 29, 26) : Color.WHITE;
        tint = dark ? Color.rgb(48, 35, 26) : Color.rgb(255, 236, 219);
        text = dark ? Color.rgb(246, 239, 230) : Color.rgb(42, 33, 27);
        muted = dark ? Color.rgb(184, 170, 154) : Color.rgb(110, 96, 85);
        accent = dark ? Color.rgb(255, 157, 85) : Color.rgb(196, 86, 15);
        onAccent = Color.rgb(42, 20, 5);
        grid = dark ? Color.rgb(58, 50, 45) : Color.rgb(236, 226, 214);
        chip = dark ? Color.rgb(52, 45, 40) : Color.rgb(246, 238, 229);
        heater = dark ? Color.rgb(125, 183, 255) : Color.rgb(39, 102, 213);
        sleep = dark ? Color.rgb(185, 162, 255) : Color.rgb(111, 73, 174);
        good = dark ? Color.rgb(95, 197, 165) : Color.rgb(28, 125, 98);
        warn = dark ? Color.rgb(240, 196, 84) : Color.rgb(150, 100, 0);
        alert = dark ? Color.rgb(255, 120, 120) : Color.rgb(190, 45, 45);
    }
    public int fill() { return Color.rgb(255, 140, 58); }
    public static int alpha(int color, int a) { return (color & 0x00FFFFFF) | (a << 24); }
}
