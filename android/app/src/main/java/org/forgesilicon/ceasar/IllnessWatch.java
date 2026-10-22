package org.forgesilicon.ceasar;

import java.util.*;

/**
 * Early illness signal from nightly core temperature against a personal baseline.
 * Baseline: median of nights 3 to 30 before, robust spread from the median absolute deviation.
 * With cycle insights on, nights in the same cycle phase are compared so the normal luteal
 * rise is not mistaken for illness; until enough luteal nights exist, a typical 0.3 °C rise is discounted. Fever is a sustained 30 minutes at or above 38.0 °C.
 */
public final class IllnessWatch {
    public enum Level { LEARNING, NORMAL, WATCH, ELEVATED, FEVER }
    public static final double FEVER = 38.0, DELTA = 0.3, Z = 2.0, LUTEAL_RISE = 0.3;

    public Level level = Level.LEARNING;
    public double[] deltas;
    public boolean[] flagged;
    public double lastDelta = Double.NaN, lastZ = Double.NaN, feverPeak = Double.NaN;
    public int elevatedNights, baselineNights;
    public long feverStart = -1;
    public boolean phaseMatched;
    public Night lastNight;

    public static IllnessWatch evaluate(List<Night> nights, CycleTracker cycle, MinuteSeries s, List<HeatStrain.Episode> exertion, long now) {
        IllnessWatch w = new IllnessWatch();
        int n = nights.size();
        w.deltas = new double[n]; w.flagged = new boolean[n];
        Arrays.fill(w.deltas, Double.NaN);
        for (int i = 0; i < n; i++) {
            Night night = nights.get(i);
            if (!night.valid() || !Double.isFinite(night.mean)) continue;
            String phase = cycle == null ? null : cycle.phase(night.date);
            List<Double> all = new ArrayList<>(), same = new ArrayList<>();
            for (int j = Math.max(0, i - 30); j <= i - 3; j++) {
                Night b = nights.get(j);
                if (!b.valid() || b.fever || w.flagged[j] || b.coverage < 0.6 || !Double.isFinite(b.mean)) continue;
                all.add(b.mean);
                if (phase != null && phase.equals(cycle.phase(b.date))) same.add(b.mean);
            }
            boolean matched = same.size() >= 6;
            List<Double> base = matched ? same : all;
            if (base.size() < (matched ? 6 : 7)) continue;
            double median = median(base);
            List<Double> dev = new ArrayList<>();
            for (double v : base) dev.add(Math.abs(v - median));
            double spread = Math.max(0.07, 1.4826 * median(dev));
            // Too few luteal nights yet to compare like with like: discount the typical luteal rise.
            w.deltas[i] = night.mean - median - (!matched && "Luteal".equals(phase) ? LUTEAL_RISE : 0);
            w.flagged[i] = w.deltas[i] >= DELTA && w.deltas[i] / spread >= Z;
            w.lastDelta = w.deltas[i]; w.lastZ = w.deltas[i] / spread; w.baselineNights = base.size(); w.phaseMatched = matched;
            w.lastNight = night;
        }
        for (int i = n - 1; i >= 0; i--) {
            if (Double.isNaN(w.deltas[i])) continue;
            if (!w.flagged[i]) break;
            w.elevatedNights++;
        }
        fever(w, s, exertion, now);
        if (w.feverStart > 0) w.level = Level.FEVER;
        else if (Double.isNaN(w.lastDelta)) w.level = Level.LEARNING;
        else w.level = w.elevatedNights >= 2 ? Level.ELEVATED : w.elevatedNights == 1 ? Level.WATCH : Level.NORMAL;
        return w;
    }
    /** Exercise heat is excluded: a fever is not a workout. */
    private static void fever(IllnessWatch w, MinuteSeries s, List<HeatStrain.Episode> exertion, long now) {
        int to = s.clamp(s.index(now) + 1), from = s.clamp(to - 24 * 60);
        float[] smooth = MinuteSeries.smooth(Arrays.copyOfRange(s.core, from, to), 15);
        for (HeatStrain.Episode e : exertion)
            for (int i = s.index(e.riseStart) - from; i < s.index(e.settled) - from; i++) if (i >= 0 && i < smooth.length) smooth[i] = Float.NaN;
        int run = 0;
        for (int i = 0; i < smooth.length; i++) {
            if (!Float.isNaN(smooth[i]) && smooth[i] >= FEVER) {
                run++;
                if (run >= 30 && w.feverStart < 0) w.feverStart = s.time(from + i - run + 1);
                if (run >= 30 || w.feverStart > 0) w.feverPeak = Double.isNaN(w.feverPeak) ? smooth[i] : Math.max(w.feverPeak, smooth[i]);
            } else run = 0;
        }
    }
    static double median(List<Double> values) {
        List<Double> v = new ArrayList<>(values); Collections.sort(v);
        int n = v.size();
        return n == 0 ? Double.NaN : n % 2 == 1 ? v.get(n / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2;
    }
}
