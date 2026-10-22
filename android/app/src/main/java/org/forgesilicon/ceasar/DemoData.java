package org.forgesilicon.ceasar;

import java.time.*;
import java.util.*;

/**
 * Nine weeks of synthetic ZHF data for the demo. Nothing here is a measurement.
 * The fictional wearer sleeps later on weekends, trains Mon/Wed/Fri evenings and Saturday
 * mornings, has two tracked menstrual cycles, a fever five weeks ago, and an early
 * illness signal over the last two nights.
 */
public final class DemoData {
    public static final int DAYS = 63;
    public static final double R_OUT = 80;
    public final MinuteSeries series;
    public final List<LocalDate> periodStarts = new ArrayList<>();
    private final ZoneId zone;
    private final LocalDate today;
    private final Random random = new Random(26);
    private final double[] circadian, sleep, work, ambient, offset;

    public DemoData(long now, ZoneId zone) {
        this.zone = zone;
        today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        long start = today.minusDays(DAYS).atStartOfDay(zone).toInstant().toEpochMilli();
        int n = (int) ((now - start) / MinuteSeries.MINUTE) + 1;
        series = new MinuteSeries(start, n);
        circadian = new double[n]; sleep = new double[n]; work = new double[n]; ambient = new double[n]; offset = new double[n];
        periodStarts.add(today.minusDays(61)); periodStarts.add(today.minusDays(32)); periodStarts.add(today.minusDays(4));
        build(now);
    }

    private long at(LocalDate d, double hour) { return d.atStartOfDay(zone).toInstant().toEpochMilli() + Math.round(hour * 3.6e6); }
    private int idx(long t) { return series.clamp(series.index(t)); }
    private double gauss(double sd) { return random.nextGaussian() * sd; }

    private void build(long now) {
        int n = series.size();
        Arrays.fill(ambient, 21.8);
        // Sleep schedule, body-clock nadir, and sleep masking with ultradian ripple.
        double clock = 4.7;
        List<long[]> nadirs = new ArrayList<>();
        for (int d = -DAYS; d <= 0; d++) {
            LocalDate morning = today.plusDays(d);
            boolean weekend = morning.getDayOfWeek() == DayOfWeek.SATURDAY || morning.getDayOfWeek() == DayOfWeek.SUNDAY;
            double onsetH = weekend ? 0.75 + gauss(0.35) : -0.6 + gauss(0.3), wakeH = weekend ? 9.2 + gauss(0.35) : 7.0 + gauss(0.2);
            long onset = at(morning, onsetH), wake = at(morning, wakeH);
            clock = 0.6 * clock + 0.4 * (wakeH - 2.4);
            nadirs.add(new long[]{at(morning, clock)});
            if (d == -25) continue;   // forgot to wear it
            double period = 88 + random.nextInt(10), phase = random.nextDouble() * 6.28;
            for (int i = idx(onset - 3_600_000); i < idx(wake + 3_600_000); i++) {
                double t = series.time(i), w = sigmoid((t - onset) / 1.2e6) * sigmoid((wake - t) / 6e5);
                sleep[i] = w;
                offset[i] += w * (-0.12 + 0.04 * Math.sin(2 * Math.PI * (t - onset) / (period * 60000) + phase));
                ambient[i] = 21.8 + w * (30.0 - 21.8);
            }
            commute(morning, weekend);
        }
        // Stretch one cosine cycle between consecutive nightly nadirs so the low lands on each.
        int k = 0;
        for (int i = 0; i < n; i++) {
            long t = series.time(i);
            while (k + 2 < nadirs.size() && t >= nadirs.get(k + 1)[0]) k++;
            long a = nadirs.get(k)[0], b = nadirs.get(k + 1)[0];
            circadian[i] = -0.33 * Math.cos(2 * Math.PI * (t - a) / (double) (b - a));
        }
        workouts();
        cycles();
        illness();
        double wander = 0, amb = ambient[0];
        for (int i = 0; i < n; i++) {
            wander = 0.995 * wander + gauss(0.004);
            amb += (ambient[i] - amb) / 8;
            double core = 36.72 + circadian[i] + offset[i] + work[i] + wander * (1 - sleep[i] * 0.6) + gauss(0.012);
            series.core[i] = (float) core;
            series.top[i] = (float) (core - 0.02 + gauss(0.01));
            series.heater[i] = (float) Math.max(0, (core - amb) / R_OUT * 1000 + gauss(3));
        }
        // Daily charging gap and the missed night.
        for (int d = -DAYS; d <= 0; d++) gap(at(today.plusDays(d), 20.5), at(today.plusDays(d), 21.0));
        gap(at(today.minusDays(26), 21.5), at(today.minusDays(25), 8.5));
        for (int i = idx(now) + 1; i < n; i++) gap(series.time(i), series.time(i) + 1);
    }
    private void commute(LocalDate day, boolean weekend) {
        if (weekend) return;
        for (double[] w : new double[][]{{8.0, 8.4}, {17.2, 17.6}}) for (int i = idx(at(day, w[0])); i < idx(at(day, w[1])); i++) ambient[i] = 6 + gauss(0.3);
    }
    private void workouts() {
        for (int d = -DAYS; d <= 0; d++) {
            LocalDate day = today.plusDays(d);
            DayOfWeek w = day.getDayOfWeek();
            if (d == -39 || d == -38 || d >= -1) continue;   // sick days and today
            double progress = (d + DAYS) / (double) DAYS;     // heat adaptation over the nine weeks
            double startH, minutes, rise;
            if (w == DayOfWeek.MONDAY || w == DayOfWeek.WEDNESDAY || w == DayOfWeek.FRIDAY) { startH = 17.75; minutes = 50; rise = 1.45 - 0.3 * progress; }
            else if (w == DayOfWeek.SATURDAY) { startH = 10.5; minutes = 75; rise = 1.95 - 0.35 * progress; }
            else continue;
            long s = at(day, startH + gauss(0.15)), e = s + (long) (minutes * 60000);
            double atEnd = 0;
            for (int i = idx(s); i < idx(e + 4 * 3_600_000L); i++) {
                long t = series.time(i);
                double v = t < e ? rise * (1 - Math.exp(-(t - s) / 1.1e6)) : atEnd * Math.exp(-(t - e) / 1.4e6);
                if (t < e) atEnd = v;
                work[i] += v;
                if (t < e) ambient[i] = 17 + gauss(0.4);
            }
        }
    }
    private void cycles() {
        int[] shiftDay = {15, 14};
        for (int c = 0; c < 2; c++) {
            long shift = at(periodStarts.get(c).plusDays(shiftDay[c]), 0), end = at(periodStarts.get(c + 1), 0);
            for (int i = idx(shift - 86_400_000L); i < idx(end + 86_400_000L); i++) {
                long t = series.time(i);
                double up = sigmoid((t - shift) / 2.5e7), down = sigmoid((end - t) / 2.5e7);
                offset[i] += 0.30 * up * down;
            }
        }
    }
    private void illness() {
        // Past fever: evening of day -40 through day -37.
        bump(at(today.minusDays(40), 20), at(today.minusDays(37), 12), 0.75);
        bump(at(today.minusDays(39), 13), at(today.minusDays(38), 2), 0.55);
        // Current: rising over the last two nights.
        bump(at(today.minusDays(2), 21), at(today.plusDays(1), 0), 0.3);
        bump(at(today.minusDays(1), 21), at(today.plusDays(1), 0), 0.25);
    }
    private void bump(long from, long to, double size) {
        for (int i = idx(from - 7_200_000); i < idx(to + 7_200_000); i++) {
            long t = series.time(i);
            offset[i] += size * sigmoid((t - from) / 3.6e6) * sigmoid((to - t) / 3.6e6);
        }
    }
    private void gap(long from, long to) {
        for (int i = idx(from); i < idx(to); i++) { series.core[i] = series.top[i] = series.heater[i] = Float.NaN; }
    }
    private static double sigmoid(double x) { return 1 / (1 + Math.exp(-x)); }

    /** A live 1 Hz reading continuing the synthetic minute series. */
    public ThermalSample live(long wall, long elapsedNanos, long sensorMillis) {
        int i = series.latest();
        double core = series.core[Math.max(0, i)], heater = series.heater[Math.max(0, i)];
        double wobble = 0.006 * Math.sin(wall / 9000.0) + random.nextGaussian() * 0.004;
        return new ThermalSample(core + wobble, core + wobble - 0.02, heater + random.nextGaussian() * 1.5, true, sensorMillis, -1, elapsedNanos);
    }
}
