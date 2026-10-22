package org.forgesilicon.ceasar;

import java.time.*;
import java.util.*;

/**
 * Circadian rhythm from core temperature: a 24 h cosinor fit (mesor, amplitude, peak time),
 * the nightly temperature low as a phase marker, schedule regularity, social jet lag,
 * and a week-long FFT spectrum showing how much of the variation is the 24 h rhythm.
 */
public final class BodyClock {
    public double mesor = Double.NaN, amplitude = Double.NaN, peakHour = Double.NaN;
    public double nadirHour = Double.NaN, nadirShift = Double.NaN, onsetHour = Double.NaN, onsetSpread = Double.NaN;
    public double socialJetLag = Double.NaN, medianDrop = Double.NaN, rhythmShare = Double.NaN;
    public double[] spectrumPeriods, spectrumPower;
    public int score = -1;

    public static BodyClock analyze(MinuteSeries s, List<Night> nights, ZoneId zone, long now) {
        BodyClock b = new BodyClock();
        cosinor(b, s, zone, now);
        List<Night> recent = new ArrayList<>();
        for (int i = nights.size() - 1; i >= 0 && recent.size() < 14; i--) if (nights.get(i).valid() && nights.get(i).nadirTime > 0) recent.add(0, nights.get(i));
        if (recent.size() >= 3) {
            List<Double> nadir = new ArrayList<>(), onset = new ArrayList<>(), drop = new ArrayList<>(), weekend = new ArrayList<>(), weekday = new ArrayList<>();
            for (Night n : recent.subList(Math.max(0, recent.size() - 7), recent.size())) {
                nadir.add(n.clockHours(n.nadirTime, zone)); onset.add(n.clockHours(n.onset, zone));
                if (Double.isFinite(n.drop())) drop.add(n.drop());
            }
            for (Night n : recent) (n.weekendMorning() ? weekend : weekday).add(n.midpoint(zone));
            b.nadirHour = IllnessWatch.median(nadir); b.onsetHour = IllnessWatch.median(onset); b.onsetSpread = sd(onset);
            b.medianDrop = drop.isEmpty() ? Double.NaN : IllnessWatch.median(drop);
            if (weekend.size() >= 2 && weekday.size() >= 2) b.socialJetLag = mean(weekend) - mean(weekday);
            if (recent.size() >= 10) {
                List<Double> early = new ArrayList<>(), late = new ArrayList<>();
                for (int i = 0; i < recent.size(); i++) (i < recent.size() / 2 ? early : late).add(recent.get(i).clockHours(recent.get(i).nadirTime, zone));
                b.nadirShift = IllnessWatch.median(late) - IllnessWatch.median(early);
            }
        }
        spectrum(b, s, now);
        if (Double.isFinite(b.amplitude) && Double.isFinite(b.onsetSpread) && Double.isFinite(b.medianDrop)) {
            double amp = clamp(b.amplitude / 0.35), regular = clamp(1 - b.onsetSpread / 1.25), drop = clamp(b.medianDrop / 0.45);
            b.score = (int) Math.round(40 * amp + 30 * regular + 30 * drop);
        }
        return b;
    }

    /** Least-squares fit of mesor + a·cos(ωt) + b·sin(ωt) over the last 72 hours, sampled every 10 minutes. */
    private static void cosinor(BodyClock b, MinuteSeries s, ZoneId zone, long now) {
        int to = s.clamp(s.index(now) + 1), from = s.clamp(to - 72 * 60);
        double[][] m = new double[3][4]; int count = 0;
        long midnight = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli();
        for (int i = from; i < to; i += 10) {
            double y = MinuteSeries.mean(s.core, i, i + 10);
            if (Double.isNaN(y)) continue;
            double w = 2 * Math.PI * ((s.time(i) + 5 * MinuteSeries.MINUTE - midnight) / 3.6e6) / 24, f[] = {1, Math.cos(w), Math.sin(w)};
            for (int r = 0; r < 3; r++) { for (int c = 0; c < 3; c++) m[r][c] += f[r] * f[c]; m[r][3] += f[r] * y; }
            count++;
        }
        if (count < 216) return;     // at least 36 hours of the 72
        double[] c = Night.solve(m);
        if (c == null) return;
        b.mesor = c[0]; b.amplitude = Math.hypot(c[1], c[2]);
        b.peakHour = ((Math.atan2(c[2], c[1]) / (2 * Math.PI) * 24) % 24 + 24) % 24;
    }

    /** Seven days in 10-minute bins, zero-padded to 4096. Periods from 3 to 48 hours. */
    private static void spectrum(BodyClock b, MinuteSeries s, long now) {
        int to = s.clamp(s.index(now) + 1), from = s.clamp(to - 7 * 24 * 60);
        int bins = (to - from) / 10;
        if (bins < 432) return;
        float[] binned = new float[bins];
        for (int i = 0; i < bins; i++) binned[i] = (float) MinuteSeries.mean(s.core, from + i * 10, from + i * 10 + 10);
        double[] x = Fft.fill(binned, 0, bins, 0.6);
        if (x == null) return;
        int n = 4096;
        double[] p = Fft.power(x, n);
        double total = 0, circadian = 0;
        for (int k = 1; k < p.length; k++) {
            double hours = n * 10.0 / 60 / k;
            if (hours < 2) break;
            if (hours > 48) continue;   // multi-day drift is not a rhythm
            total += p[k];
            if (hours >= 20 && hours <= 28) circadian += p[k];
        }
        b.rhythmShare = total == 0 ? Double.NaN : circadian / total;
        // Resample onto a log-period axis from 48 h to 3 h so 24 h and 12 h get room to read.
        int points = 96; double max = 0;
        b.spectrumPeriods = new double[points]; b.spectrumPower = new double[points];
        for (int i = 0; i < points; i++) {
            double hours = 48 * Math.pow(3 / 48.0, i / (double) (points - 1)), k = n * 10.0 / 60 / hours;
            int k0 = (int) Math.floor(k); double f = k - k0;
            double v = k0 + 1 < p.length ? p[k0] * (1 - f) + p[k0 + 1] * f : 0;
            b.spectrumPeriods[i] = hours; b.spectrumPower[i] = v; max = Math.max(max, v);
        }
        for (int i = 0; i < points; i++) b.spectrumPower[i] = max == 0 ? 0 : b.spectrumPower[i] / max;
    }

    static double clamp(double v) { return Math.max(0, Math.min(1, v)); }
    static double mean(List<Double> v) { double s = 0; for (double x : v) s += x; return s / v.size(); }
    static double sd(List<Double> v) {
        if (v.size() < 2) return Double.NaN;
        double m = mean(v), s = 0;
        for (double x : v) s += (x - m) * (x - m);
        return Math.sqrt(s / (v.size() - 1));
    }
}
