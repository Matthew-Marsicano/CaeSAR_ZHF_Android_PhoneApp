package org.forgesilicon.ceasar;

import java.util.Arrays;

/** Evenly spaced one-minute aggregates. Missing minutes are NaN, never zero. */
public final class MinuteSeries {
    public static final long MINUTE = 60_000L;
    public final long start;
    public final float[] core, top, heater;

    public MinuteSeries(long start, int minutes) {
        this.start = Math.floorDiv(start, MINUTE) * MINUTE;
        core = nan(minutes); top = nan(minutes); heater = nan(minutes);
    }
    static float[] nan(int n) { float[] a = new float[n]; Arrays.fill(a, Float.NaN); return a; }
    public int size() { return core.length; }
    public long end() { return start + size() * MINUTE; }
    public long time(int i) { return start + i * MINUTE; }
    public int index(long t) { return (int) Math.floorDiv(t - start, MINUTE); }
    public int clamp(int i) { return Math.max(0, Math.min(size(), i)); }

    /** Index of the newest minute with a core reading, or -1. */
    public int latest() {
        for (int i = size() - 1; i >= 0; i--) if (!Float.isNaN(core[i])) return i;
        return -1;
    }
    public static double mean(float[] v, int from, int to) {
        double sum = 0; int n = 0;
        for (int i = Math.max(0, from); i < Math.min(v.length, to); i++) if (!Float.isNaN(v[i])) { sum += v[i]; n++; }
        return n == 0 ? Double.NaN : sum / n;
    }
    public static int count(float[] v, int from, int to) {
        int n = 0;
        for (int i = Math.max(0, from); i < Math.min(v.length, to); i++) if (!Float.isNaN(v[i])) n++;
        return n;
    }
    /** Centered moving mean ignoring NaN. A minute stays NaN when under a third of its window is present. */
    public static float[] smooth(float[] v, int window) {
        int n = v.length, half = window / 2;
        double[] sum = new double[n + 1]; int[] cnt = new int[n + 1];
        for (int i = 0; i < n; i++) {
            boolean ok = !Float.isNaN(v[i]);
            sum[i + 1] = sum[i] + (ok ? v[i] : 0); cnt[i + 1] = cnt[i] + (ok ? 1 : 0);
        }
        float[] out = nan(n);
        for (int i = 0; i < n; i++) {
            if (Float.isNaN(v[i])) continue;
            int a = Math.max(0, i - half), b = Math.min(n, i + half + 1), c = cnt[b] - cnt[a];
            if (c * 3 >= (b - a)) out[i] = (float) ((sum[b] - sum[a]) / c);
        }
        return out;
    }
    /** Estimated air temperature at the outer face: core minus heater power times the outward thermal resistance. */
    public static double ambient(double core, double heaterMw, double rOut) {
        return core - heaterMw / 1000.0 * rOut;
    }
}
