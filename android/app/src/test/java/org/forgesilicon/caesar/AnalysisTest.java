package org.forgesilicon.caesar;

import org.junit.Test;
import java.time.*;
import java.util.*;
import static org.junit.Assert.*;

public class AnalysisTest {
    private static final ZoneId ZONE = ZoneId.of("America/New_York");
    private static final long NOW = ZonedDateTime.of(2026, 10, 22, 15, 0, 0, 0, ZONE).toInstant().toEpochMilli();

    private Analysis demo(boolean cycle) {
        DemoData d = new DemoData(NOW, ZONE);
        return new Analysis(d.series, ZONE, NOW, DemoData.R_OUT, cycle, d.periodStarts);
    }

    @Test public void demoSummary() {
        Analysis a = demo(true);
        int valid = 0, heater = 0, cycles = 0;
        for (Night n : a.nights) { if (n.valid()) valid++; if (n.fromHeater) heater++; if (n.cycles() > 0) cycles++; }
        Night last = a.lastNight();
        System.out.printf(Locale.US, "nights %d valid %d heater %d ultradian %d%n", a.nights.size(), valid, heater, cycles);
        System.out.printf(Locale.US, "last %s onset %.2f wake %.2f nadir %.2f@%.2f drop %.2f micro %.1f period %.0f strength %.2f%n", last.date,
                last.clockHours(last.onset, ZONE), last.clockHours(last.wake, ZONE), last.nadir, last.clockHours(last.nadirTime, ZONE), last.drop(), last.microclimate, last.ultradianMinutes, last.ultradianStrength);
        System.out.printf(Locale.US, "illness %s delta %.2f z %.1f nights %d matched %b base %d%n", a.illness.level, a.illness.lastDelta, a.illness.lastZ, a.illness.elevatedNights, a.illness.phaseMatched, a.illness.baselineNights);
        BodyClock c = a.clock;
        System.out.printf(Locale.US, "clock mesor %.2f amp %.2f peak %.1f nadir %.2f onset %.2f sd %.2f sjl %.2f drop %.2f share %.2f score %d%n", c.mesor, c.amplitude, c.peakHour, c.nadirHour, c.onsetHour, c.onsetSpread, c.socialJetLag, c.medianDrop, c.rhythmShare, c.score);
        for (CycleTracker.Cycle cy : a.cycle.cycles) System.out.printf("cycle %s end %s shift %s luteal %d%n", cy.start, cy.end, cy.shift, cy.luteal());
        System.out.printf("cycle day %d next %s%n", a.cycle.cycleDay(), a.cycle.predictedNext);
        System.out.printf(Locale.US, "episodes %d adaptation %.2f%n", a.episodes.size(), HeatStrain.adaptation(a.episodes));
        HeatStrain.Episode e = a.episodes.get(a.episodes.size() - 1);
        System.out.printf(Locale.US, "last ep base %.2f peak %.2f rate %.2f strain %.1f rec %.0f amb %.1f >38.5 %d%n", e.base, e.peak, e.maxRate, e.strain(), e.recoveryMinutes(), e.ambient, e.minutesAbove385);
        for (int i = 0; i < a.nights.size(); i++) if (a.illness.flagged[i] || a.nights.get(i).fever) System.out.printf(Locale.US, "flag %s %.2f fever %b%n", a.nights.get(i).date, a.illness.deltas[i], a.nights.get(i).fever);

        assertTrue(valid >= 55);
        assertEquals(IllnessWatch.Level.ELEVATED, a.illness.level);
        assertTrue(a.illness.phaseMatched);
        assertEquals(2, a.cycle.cycles.stream().filter(x -> x.luteal() >= 12 && x.luteal() <= 16).count());
        assertTrue(c.amplitude > 0.2 && c.amplitude < 0.5);
        assertTrue(c.nadirHour > 3.5 && c.nadirHour < 6.5);
        assertTrue(c.socialJetLag > 0.8);
        assertTrue(a.episodes.size() >= 25);
        assertTrue(HeatStrain.adaptation(a.episodes) < 0);
        assertTrue(cycles >= valid / 2);
    }

    @Test public void fluctuatingCycleIsNotIllness() {
        // Without cycle awareness the luteal rise can look like a signal; with it, past luteal nights are normal.
        Analysis a = demo(true);
        int lutealFlags = 0;
        for (int i = 0; i < a.nights.size(); i++) {
            Night n = a.nights.get(i);
            if (a.illness.flagged[i] && "Luteal".equals(a.cycle.phase(n.date)) && !n.fever && !nearFever(a, i)) lutealFlags++;
        }
        assertEquals(0, lutealFlags);
    }

    private boolean nearFever(Analysis a, int i) {
        for (int j = Math.max(0, i - 2); j <= Math.min(a.nights.size() - 1, i + 2); j++) if (a.nights.get(j).fever) return true;
        return false;
    }

    @Test public void feverIsSustainedAndNotExercise() {
        MinuteSeries s = new MinuteSeries(NOW - 10 * 3_600_000L, 10 * 60 + 1);
        Arrays.fill(s.core, 37.0f);
        for (int i = 60; i < 110; i++) s.core[i] = 37.0f + Math.min(1.4f, (i - 60) * 0.05f);   // fast workout climb
        for (int i = 110; i < 170; i++) s.core[i] = 37.0f + 1.4f * (float) Math.exp(-(i - 110) / 20.0);
        List<HeatStrain.Episode> e = HeatStrain.detect(s, 80);
        assertEquals(1, e.size());
        IllnessWatch w = IllnessWatch.evaluate(new ArrayList<>(), null, s, e, NOW);
        assertNotEquals(IllnessWatch.Level.FEVER, w.level);
        for (int i = 250; i < s.size(); i++) s.core[i] = 37.0f + Math.min(1.3f, (i - 250) * 0.008f);   // slow fever climb
        e = HeatStrain.detect(s, 80);
        w = IllnessWatch.evaluate(new ArrayList<>(), null, s, e, NOW);
        assertEquals(IllnessWatch.Level.FEVER, w.level);
    }

    @Test public void parsesAndRejectsSamples() {
        ThermalSample s = ThermalSample.parse("{\"core\":36.94,\"top\":36.91,\"heater_mw\":182.5,\"lock\":true,\"t_ms\":1234}", 7);
        assertEquals(36.94, s.core, 1e-9); assertEquals(182.5, s.heaterMw, 1e-9); assertTrue(s.locked); assertEquals(-1, s.epochMillis);
        assertNull(ThermalSample.parse("{\"core\":12}", 0));
        assertNull(ThermalSample.parse("{\"top\":36}", 0));
        assertNull(ThermalSample.parse("{\"core\":\"37\"}", 0));
        assertNull(ThermalSample.parse("{\"core\":37,\"epoch\":12}", 0));
        assertFalse(ThermalSample.parse("{\"core\":37,\"lock\":false}", 0).locked);
        assertEquals(1_760_000_000_000L, ThermalSample.parse("{\"core\":37,\"epoch\":1760000000}", 0).epochMillis);
    }

    @Test public void fftFindsKnownPeriod() {
        double[] x = new double[600];
        for (int i = 0; i < x.length; i++) x[i] = Math.sin(2 * Math.PI * i / 90.0);
        double[] p = Fft.power(x, 1024);
        int best = 1;
        for (int k = 2; k < p.length; k++) if (p[k] > p[best]) best = k;
        assertEquals(90, 1024.0 / best, 4);
    }
}
