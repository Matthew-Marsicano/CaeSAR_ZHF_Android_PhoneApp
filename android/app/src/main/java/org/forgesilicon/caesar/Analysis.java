package org.forgesilicon.caesar;

import java.time.*;
import java.util.*;

/** Runs every analyzer over one minute series. Built off the main thread; read-only afterwards. */
public final class Analysis {
    public final MinuteSeries series;
    public final ZoneId zone;
    public final long now;
    public final double rOut;
    public final List<Night> nights;
    public final List<HeatStrain.Episode> episodes;
    public final CycleTracker cycle;
    public final IllnessWatch illness;
    public final BodyClock clock;

    public Analysis(MinuteSeries s, ZoneId zone, long now, double rOut, boolean cycleInsights, List<LocalDate> periodStarts) {
        series = s; this.zone = zone; this.now = now; this.rOut = rOut;
        nights = Night.analyze(s, zone, rOut, now);
        episodes = HeatStrain.detect(s, rOut);
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        cycle = cycleInsights && !periodStarts.isEmpty() ? CycleTracker.analyze(nights, periodStarts, today) : null;
        illness = IllnessWatch.evaluate(nights, cycle, s, episodes, now);
        clock = BodyClock.analyze(s, nights, zone, now);
    }

    public boolean empty() { return series.latest() < 0; }
    public Night lastNight() {
        for (int i = nights.size() - 1; i >= 0; i--) if (nights.get(i).valid()) return nights.get(i);
        return null;
    }
    public int nightIndex(Night n) { return nights.indexOf(n); }
    public long latestTime() { int i = series.latest(); return i < 0 ? -1 : series.time(i); }
    public double latestCore() { int i = series.latest(); return i < 0 ? Double.NaN : series.core[i]; }
    public double latestHeater() { int i = series.latest(); return i < 0 ? Double.NaN : series.heater[i]; }
    /** Mean core at this clock hour over the previous 14 days, for "vs your usual" comparisons. */
    public double usualAt(long t) {
        double sum = 0; int n = 0;
        for (int d = 1; d <= 14; d++) {
            int i = series.index(t - d * 86_400_000L);
            double v = MinuteSeries.mean(series.core, i - 15, i + 15);
            if (Double.isFinite(v)) { sum += v; n++; }
        }
        return n < 5 ? Double.NaN : sum / n;
    }
    public List<HeatStrain.Episode> episodesSince(long t) {
        List<HeatStrain.Episode> out = new ArrayList<>();
        for (HeatStrain.Episode e : episodes) if (e.peakTime >= t) out.add(e);
        return out;
    }
}
