package org.forgesilicon.ceasar;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** Calendar period of bucketed temperature history. Empty buckets stay gaps. */
public final class Trend {
    public enum Period {
        DAY(15), WEEK(120), MONTH(360), QUARTER(1440);
        public final int bucketMinutes;
        Period(int m) { bucketMinutes = m; }
    }
    public static final class Bucket {
        public final long start, end;
        public double avg = Double.NaN, low = Double.NaN, high = Double.NaN, heater = Double.NaN;
        public int count;
        Bucket(long start, long end) { this.start = start; this.end = end; }
    }

    public final Period period;
    public final long start, end;
    public final String title;
    public final List<Bucket> buckets = new ArrayList<>();
    public double avg = Double.NaN, low = Double.NaN, high = Double.NaN;
    public int minutes;
    public long lowTime = -1, highTime = -1;

    public Trend(Period period, int offset, long now, ZoneId zone) {
        this.period = period;
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), first, last;
        DateTimeFormatter day = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()), shortDay = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault());
        switch (period) {
            case DAY: first = today.plusDays(offset); last = first.plusDays(1);
                title = offset == 0 ? "Today" : offset == -1 ? "Yesterday" : first.format(day); break;
            case WEEK: first = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(offset); last = first.plusWeeks(1);
                title = offset == 0 ? "This week" : first.format(shortDay) + " – " + last.minusDays(1).format(shortDay); break;
            case MONTH: first = today.withDayOfMonth(1).plusMonths(offset); last = first.plusMonths(1);
                title = first.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())); break;
            default: last = today.plusDays(1).plusDays(90L * offset); first = last.minusDays(90);
                title = offset == 0 ? "Last 90 days" : first.format(shortDay) + " – " + last.minusDays(1).format(shortDay);
        }
        start = first.atStartOfDay(zone).toInstant().toEpochMilli();
        end = last.atStartOfDay(zone).toInstant().toEpochMilli();
        for (long t = start; t < end; ) {
            long next = period == Period.QUARTER ? Instant.ofEpochMilli(t).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    : t + period.bucketMinutes * MinuteSeries.MINUTE;
            buckets.add(new Bucket(t, Math.min(next, end))); t = next;
        }
    }

    public Trend fill(MinuteSeries s) {
        double sum = 0;
        for (Bucket b : buckets) {
            int a = s.clamp(s.index(b.start)), z = s.clamp(s.index(b.end));
            double bs = 0, hs = 0; int hc = 0;
            for (int i = a; i < z; i++) {
                float v = s.core[i];
                if (!Float.isNaN(s.heater[i])) { hs += s.heater[i]; hc++; }
                if (Float.isNaN(v)) continue;
                bs += v; b.count++;
                if (Double.isNaN(b.low) || v < b.low) b.low = v;
                if (Double.isNaN(b.high) || v > b.high) b.high = v;
                if (Double.isNaN(low) || v < low) { low = v; lowTime = s.time(i); }
                if (Double.isNaN(high) || v > high) { high = v; highTime = s.time(i); }
            }
            if (b.count > 0) { b.avg = bs / b.count; sum += bs; minutes += b.count; }
            if (hc > 0) b.heater = hs / hc;
        }
        if (minutes > 0) avg = sum / minutes;
        return this;
    }
}
