package org.forgesilicon.ceasar;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Opt-in menstrual-cycle insights from the biphasic nightly temperature pattern.
 * A thermal shift is confirmed with the three-over-six rule: three nights above the
 * highest of the previous six, the third at least 0.15 °C above it. Ovulation is the
 * night before the shift. This confirms ovulation after the fact; it is not contraception.
 */
public final class CycleTracker {
    public static final int DEFAULT_LUTEAL = 13, DEFAULT_LENGTH = 28;

    public static final class Cycle {
        public final LocalDate start; public LocalDate end, shift; public double coverline = Double.NaN;
        Cycle(LocalDate start) { this.start = start; }
        public int length() { return end == null ? -1 : (int) ChronoUnit.DAYS.between(start, end); }
        public int luteal() { return end == null || shift == null ? -1 : (int) ChronoUnit.DAYS.between(shift, end); }
        public LocalDate ovulation() { return shift == null ? null : shift.minusDays(1); }
    }

    public final List<Cycle> cycles = new ArrayList<>();
    public LocalDate today, predictedNext;
    public int averageLength = DEFAULT_LENGTH, averageLuteal = DEFAULT_LUTEAL;

    public static CycleTracker analyze(List<Night> nights, List<LocalDate> starts, LocalDate today) {
        CycleTracker t = new CycleTracker(); t.today = today;
        List<LocalDate> sorted = new ArrayList<>(new TreeSet<>(starts));
        Map<LocalDate, Double> temps = new HashMap<>();
        for (Night n : nights) if (n.valid() && !n.fever && Double.isFinite(n.mean)) temps.put(n.date, n.mean);
        for (int i = 0; i < sorted.size(); i++) {
            Cycle c = new Cycle(sorted.get(i));
            c.end = i + 1 < sorted.size() ? sorted.get(i + 1) : null;
            findShift(c, temps, today);
            t.cycles.add(c);
        }
        int lengthSum = 0, lengthN = 0, lutealSum = 0, lutealN = 0;
        for (Cycle c : t.cycles) {
            if (c.length() >= 18 && c.length() <= 45) { lengthSum += c.length(); lengthN++; }
            if (c.luteal() >= 5 && c.luteal() <= 20) { lutealSum += c.luteal(); lutealN++; }
        }
        if (lengthN > 0) t.averageLength = Math.round(lengthSum / (float) lengthN);
        if (lutealN > 0) t.averageLuteal = Math.round(lutealSum / (float) lutealN);
        Cycle current = t.current();
        if (current != null) t.predictedNext = current.shift != null ? current.shift.plusDays(t.averageLuteal) : current.start.plusDays(t.averageLength);
        return t;
    }

    private static void findShift(Cycle c, Map<LocalDate, Double> temps, LocalDate today) {
        LocalDate stop = c.end != null ? c.end : today.plusDays(1);
        for (LocalDate d = c.start.plusDays(6); d.plusDays(2).isBefore(stop); d = d.plusDays(1)) {
            double coverline = Double.NEGATIVE_INFINITY; int previous = 0;
            for (int k = 1; k <= 8 && previous < 6; k++) {
                Double v = temps.get(d.minusDays(k));
                if (v != null && !d.minusDays(k).isBefore(c.start)) { coverline = Math.max(coverline, v); previous++; }
            }
            if (previous < 5) continue;
            Double a = temps.get(d), b = temps.get(d.plusDays(1)), e = temps.get(d.plusDays(2));
            if (a == null || b == null || e == null) continue;
            if (a > coverline && b > coverline && e >= coverline + 0.15) { c.shift = d; c.coverline = coverline; return; }
        }
    }

    public Cycle current() {
        if (cycles.isEmpty()) return null;
        Cycle c = cycles.get(cycles.size() - 1);
        return ChronoUnit.DAYS.between(c.start, today) <= 60 ? c : null;
    }
    public int cycleDay() { Cycle c = current(); return c == null ? -1 : (int) ChronoUnit.DAYS.between(c.start, today) + 1; }
    /** "Luteal", "Follicular", or null when the date falls outside a tracked cycle. */
    public String phase(LocalDate date) {
        for (Cycle c : cycles) {
            if (date.isBefore(c.start) || (c.end != null && !date.isBefore(c.end))) continue;
            if (c.end == null && ChronoUnit.DAYS.between(c.start, date) > 60) return null;
            return c.shift != null && !date.isBefore(c.shift) ? "Luteal" : "Follicular";
        }
        return null;
    }
    /** Most recent completed cycle with a luteal phase under ten days, a pattern worth raising with a clinician. */
    public Cycle shortLuteal() {
        for (int i = cycles.size() - 1; i >= 0; i--) { int l = cycles.get(i).luteal(); if (l > 0) return l < 10 ? cycles.get(i) : null; }
        return null;
    }
    /** Most recent completed cycle where no thermal shift was found. */
    public Cycle noShift() {
        for (int i = cycles.size() - 1; i >= 0; i--) { Cycle c = cycles.get(i); if (c.end != null) return c.shift == null ? c : null; }
        return null;
    }
}
