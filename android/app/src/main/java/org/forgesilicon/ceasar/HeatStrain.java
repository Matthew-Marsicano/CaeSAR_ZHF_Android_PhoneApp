package org.forgesilicon.ceasar;

import java.util.*;

/**
 * Exertional heat episodes: core temperature that climbs fast (at least 0.1 °C per 10 min)
 * by at least 0.5 °C to 37.8 °C or more. Slow climbs are left to the illness watch.
 * Strain is the thermal half of the Physiological Strain Index (Moran 1998), scaled 0 to 10.
 */
public final class HeatStrain {
    public static final double LIMIT = 39.5;

    public static final class Episode {
        public long riseStart, peakTime, settled;
        public double base, peak, maxRate, ambient = Double.NaN;
        public int minutesAbove385, minutesAbove39;
        public boolean recovered;
        public double rise() { return peak - base; }
        public double strain() { return HeatStrain.strain(base, peak); }
        public double recoveryMinutes() { return recovered ? (settled - peakTime) / 60000.0 : Double.NaN; }
    }

    public static double strain(double base, double core) {
        if (!Double.isFinite(base) || !Double.isFinite(core)) return Double.NaN;
        return Math.max(0, Math.min(10, 10 * (core - base) / (LIMIT - Math.min(base, LIMIT - 0.5))));
    }

    public static List<Episode> detect(MinuteSeries s, double rOut) {
        List<Episode> out = new ArrayList<>();
        float[] c = MinuteSeries.smooth(s.core, 5);
        int n = c.length, i = 0;
        while (i < n) {
            if (Float.isNaN(c[i]) || c[i] < 37.8f) { i++; continue; }
            int end = i, lastHot = i;
            while (end < n && end - lastHot <= 10) { if (!Float.isNaN(c[end]) && c[end] >= 37.8f) lastHot = end; end++; }
            int peakAt = i;
            for (int k = i; k <= lastHot; k++) if (!Float.isNaN(c[k]) && c[k] > c[peakAt]) peakAt = k;
            int rise = i;
            for (int k = i - 1; k >= Math.max(0, i - 90); k--) { if (Float.isNaN(c[k]) || c[k] > c[rise] + 0.02f) break; if (c[k] <= c[rise]) rise = k; }
            Episode e = new Episode();
            e.riseStart = s.time(rise); e.peakTime = s.time(peakAt); e.base = c[rise]; e.peak = c[peakAt];
            for (int k = Math.max(rise + 10, 10); k <= peakAt; k++)
                if (!Float.isNaN(c[k]) && !Float.isNaN(c[k - 10])) e.maxRate = Math.max(e.maxRate, c[k] - c[k - 10]);
            int settle = lastHot;
            while (settle < Math.min(n, peakAt + 180) && (Float.isNaN(c[settle]) || c[settle] > e.base + 0.3f)) settle++;
            e.recovered = settle < n && !Float.isNaN(c[settle]) && c[settle] <= e.base + 0.3f;
            e.settled = s.time(settle);
            double amb = 0; int ac = 0;
            for (int k = rise; k <= lastHot; k++) {
                if (!Float.isNaN(c[k]) && c[k] >= 38.5f) e.minutesAbove385++;
                if (!Float.isNaN(c[k]) && c[k] >= 39.0f) e.minutesAbove39++;
                if (!Float.isNaN(s.core[k]) && !Float.isNaN(s.heater[k])) { amb += MinuteSeries.ambient(s.core[k], s.heater[k], rOut); ac++; }
            }
            if (ac > 0) e.ambient = amb / ac;
            if (e.rise() >= 0.5 && e.maxRate >= 0.1) out.add(e);
            i = Math.max(end, settle) + 1;
        }
        return out;
    }

    /** Minutes until 39.0 °C at the current 10-minute rate of rise, or NaN when not climbing. */
    public static double minutesTo39(MinuteSeries s) {
        int last = s.latest();
        if (last < 10) return Double.NaN;
        float[] c = MinuteSeries.smooth(Arrays.copyOfRange(s.core, last - 20, last + 1), 5);
        double now = c[c.length - 1], before = c[c.length - 11];
        double rate = (now - before) / 10;
        if (!Double.isFinite(rate) || rate < 0.01 || now < 37.8 || now >= 39) return Double.NaN;
        return (39 - now) / rate;
    }

    /** Change in mean rise between the latest five episodes and the five before; negative suggests heat adaptation. */
    public static double adaptation(List<Episode> episodes) {
        if (episodes.size() < 8) return Double.NaN;
        int n = episodes.size(), half = Math.min(5, n / 2);
        double recent = 0, earlier = 0;
        for (int i = 0; i < half; i++) { recent += episodes.get(n - 1 - i).rise(); earlier += episodes.get(n - 1 - half - i).rise(); }
        return (recent - earlier) / half;
    }
}
