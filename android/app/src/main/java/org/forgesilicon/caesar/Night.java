package org.forgesilicon.caesar;

import java.time.*;
import java.util.*;

/**
 * One night of temperature-derived sleep metrics, keyed by the morning it ends on.
 * In-bed time comes from the ZHF heater: bedding warms the air around the patch, so the
 * heater needs far less power to hold zero flux. Without heater data, a core-temperature
 * threshold is used instead.
 */
public final class Night {
    public final LocalDate date;
    public long onset = -1, wake = -1, nadirTime = -1;
    public double nadir = Double.NaN, evening = Double.NaN, mean = Double.NaN, microclimate = Double.NaN;
    public double ultradianMinutes = Double.NaN, ultradianStrength = Double.NaN, coverage;
    public boolean fromHeater, fever;

    Night(LocalDate date) { this.date = date; }
    public boolean valid() { return onset > 0 && wake > onset; }
    public double hours() { return valid() ? (wake - onset) / 3.6e6 : Double.NaN; }
    public double drop() { return evening - nadir; }
    public double nadirToWake() { return valid() && nadirTime > 0 ? (wake - nadirTime) / 3.6e6 : Double.NaN; }
    public int cycles() { return Double.isNaN(ultradianMinutes) ? 0 : (int) Math.round(hours() * 60 / ultradianMinutes); }
    /** Hours after the midnight that starts {@link #date}; evening times are negative. */
    public double clockHours(long t, ZoneId zone) { return (t - date.atStartOfDay(zone).toInstant().toEpochMilli()) / 3.6e6; }
    public double midpoint(ZoneId zone) { return valid() ? clockHours((onset + wake) / 2, zone) : Double.NaN; }
    public boolean weekendMorning() { DayOfWeek d = date.getDayOfWeek(); return d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY; }

    public static List<Night> analyze(MinuteSeries s, ZoneId zone, double rOut, long now) {
        List<Night> nights = new ArrayList<>();
        if (s.size() == 0) return nights;
        float[] core = MinuteSeries.smooth(s.core, 31), heat = MinuteSeries.smooth(s.heater, 15);
        LocalDate first = Instant.ofEpochMilli(s.start).atZone(zone).toLocalDate().plusDays(1);
        LocalDate last = Instant.ofEpochMilli(Math.min(now, s.end())).atZone(zone).toLocalDate();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            long from = d.minusDays(1).atTime(18, 0).atZone(zone).toInstant().toEpochMilli();
            long to = d.atTime(14, 0).atZone(zone).toInstant().toEpochMilli();
            Night n = new Night(d);
            int a = s.clamp(s.index(from)), b = s.clamp(s.index(Math.min(to, now)));
            if (b - a < 6 * 60) continue;
            int[] run = heaterRun(heat, a, b);
            n.fromHeater = run != null;
            if (run == null) run = coreRun(core, a, b);
            if (run != null && run[1] < b - 20) fill(n, s, core, run[0], run[1], rOut);
            nights.add(n);
        }
        return nights;
    }

    private static int[] heaterRun(float[] heat, int a, int b) {
        float[] vals = finite(heat, a, b);
        if (vals.length < (b - a) / 2) return null;
        Arrays.sort(vals);
        float awake = vals[(int) (vals.length * 0.85)], asleep = vals[(int) (vals.length * 0.15)];
        if (awake - asleep < 25) return null;          // no clear bedding step, fall back to core
        float threshold = asleep + (awake - asleep) * 0.5f;
        return longestRun(heat, a, b, threshold);
    }
    private static int[] coreRun(float[] core, int a, int b) {
        float[] vals = finite(core, a, b);
        if (vals.length < (b - a) / 2) return null;
        Arrays.sort(vals);
        float high = vals[(int) (vals.length * 0.85)], low = vals[(int) (vals.length * 0.05)];
        return longestRun(core, a, b, low + (high - low) * 0.45f);
    }
    /** Longest stretch below threshold, bridging excursions of up to 25 minutes; at least three hours. */
    private static int[] longestRun(float[] v, int a, int b, float threshold) {
        int bestStart = -1, bestEnd = -1, start = -1, lastBelow = -1;
        for (int i = a; i < b; i++) {
            if (!Float.isNaN(v[i]) && v[i] < threshold) { if (start < 0) start = i; lastBelow = i; }
            else if (start >= 0 && i - lastBelow > 25) {
                if (lastBelow - start > bestEnd - bestStart) { bestStart = start; bestEnd = lastBelow; }
                start = -1;
            }
        }
        if (start >= 0 && lastBelow - start > bestEnd - bestStart) { bestStart = start; bestEnd = lastBelow; }
        return bestStart >= 0 && bestEnd - bestStart >= 180 ? new int[]{bestStart, bestEnd + 1} : null;
    }
    private static float[] finite(float[] v, int a, int b) {
        float[] out = new float[b - a]; int n = 0;
        for (int i = a; i < b; i++) if (!Float.isNaN(v[i])) out[n++] = v[i];
        return Arrays.copyOf(out, n);
    }

    private static void fill(Night n, MinuteSeries s, float[] core, int on, int off, double rOut) {
        n.onset = s.time(on); n.wake = s.time(off);
        n.coverage = MinuteSeries.count(s.core, on, off) / (double) (off - on);
        int nadirAt = -1;
        for (int i = on; i < off; i++) if (!Float.isNaN(core[i]) && (nadirAt < 0 || core[i] < core[nadirAt])) nadirAt = i;
        if (nadirAt >= 0) { n.nadir = core[nadirAt]; n.nadirTime = s.time(nadirAt); }
        for (int i = on; i < off; i++) if (core[i] >= IllnessWatch.FEVER) n.fever = true;
        n.evening = MinuteSeries.mean(s.core, on - 120, on);
        n.mean = MinuteSeries.mean(s.core, on + 60, off);
        double sum = 0; int c = 0;
        for (int i = on; i < off; i++) if (!Float.isNaN(s.core[i]) && !Float.isNaN(s.heater[i])) { sum += MinuteSeries.ambient(s.core[i], s.heater[i], rOut); c++; }
        n.microclimate = c == 0 ? Double.NaN : sum / c;
        ultradian(n, s.core, on + 30, off - 15);
    }

    /** Sleep-cycle rhythm: quadratic-detrended core, FFT, strongest period in the 60–130 minute band. */
    static void ultradian(Night n, float[] core, int a, int b) {
        double[] x = Fft.fill(core, a, b, 0.8);
        if (x == null || x.length < 240) return;
        detrendQuadratic(x);
        double[] p = Fft.power(x, 1024);
        double band = 0, total = 0, best = 0; int bestK = -1;
        for (int k = 1; k < p.length; k++) {
            double period = 1024.0 / k;
            if (period > 300 || period < 20) continue;
            total += p[k];
            if (period >= 60 && period <= 130) { band += p[k]; if (p[k] > best) { best = p[k]; bestK = k; } }
        }
        if (bestK < 0 || total == 0) return;
        n.ultradianStrength = band / total;
        if (n.ultradianStrength >= 0.35) n.ultradianMinutes = 1024.0 / bestK;
    }
    static void detrendQuadratic(double[] y) {
        int n = y.length; double[][] m = new double[3][4];
        for (int i = 0; i < n; i++) {
            double t = (i - n / 2.0) / n, f[] = {1, t, t * t};
            for (int r = 0; r < 3; r++) { for (int c = 0; c < 3; c++) m[r][c] += f[r] * f[c]; m[r][3] += f[r] * y[i]; }
        }
        double[] c = solve(m);
        if (c == null) return;
        for (int i = 0; i < n; i++) { double t = (i - n / 2.0) / n; y[i] -= c[0] + c[1] * t + c[2] * t * t; }
    }
    /** Gaussian elimination on an augmented 3x4 matrix. */
    static double[] solve(double[][] m) {
        int n = 3;
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int r = col + 1; r < n; r++) if (Math.abs(m[r][col]) > Math.abs(m[pivot][col])) pivot = r;
            if (Math.abs(m[pivot][col]) < 1e-12) return null;
            double[] t = m[col]; m[col] = m[pivot]; m[pivot] = t;
            for (int r = 0; r < n; r++) if (r != col) {
                double f = m[r][col] / m[col][col];
                for (int c = col; c <= n; c++) m[r][c] -= f * m[col][c];
            }
        }
        return new double[]{m[0][3] / m[0][0], m[1][3] / m[1][1], m[2][3] / m[2][2]};
    }
}
