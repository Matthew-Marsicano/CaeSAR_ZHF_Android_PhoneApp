package org.forgesilicon.ceasar;

import org.json.JSONObject;

/**
 * One zero-heat-flux reading. {@code core} is the deep-tissue estimate under the insulator,
 * {@code top} the sensor above it, {@code heaterMw} the power the loop needs to null the flux.
 */
public final class ThermalSample {
    public static final double MISSING = Double.NaN;
    public final double core, top, heaterMw;
    public final boolean locked;
    public final long sensorMillis, epochMillis, receivedNanos;

    public ThermalSample(double core, double top, double heaterMw, boolean locked, long sensorMillis, long epochMillis, long receivedNanos) {
        this.core = core; this.top = top; this.heaterMw = heaterMw; this.locked = locked;
        this.sensorMillis = sensorMillis; this.epochMillis = epochMillis; this.receivedNanos = receivedNanos;
    }

    /** Parses {"core":36.94,"top":36.91,"heater_mw":182.5,"lock":true,"t_ms":1234,"epoch":1729550000}. Returns null when invalid. */
    public static ThermalSample parse(String line, long receivedNanos) {
        try {
            JSONObject json = new JSONObject(line);
            double core = number(json, "core", 25, 45);
            if (Double.isNaN(core)) return null;
            double top = number(json, "top", 0, 50);
            double heater = number(json, "heater_mw", 0, 2000);
            boolean locked = !json.has("lock") || json.getBoolean("lock");
            long t = whole(json, "t_ms");
            long epoch = whole(json, "epoch");
            // Buffered history is stamped by the module RTC; reject stamps before 2020 or in the future.
            if (epoch >= 0 && (epoch < 1_577_836_800L || epoch * 1000 > System.currentTimeMillis() + 300_000)) return null;
            return new ThermalSample(core, top, heater, locked, t, epoch < 0 ? -1 : epoch * 1000, receivedNanos);
        } catch (Exception malformed) { return null; }
    }
    private static double number(JSONObject json, String key, double min, double max) throws org.json.JSONException {
        if (!json.has(key)) return MISSING;
        Object raw = json.get(key);
        if (!(raw instanceof Number)) throw new IllegalArgumentException(key + " must be numeric");
        double v = ((Number) raw).doubleValue();
        if (!Double.isFinite(v) || v < min || v > max) throw new IllegalArgumentException(key + " out of range");
        return v;
    }
    private static long whole(JSONObject json, String key) throws org.json.JSONException {
        if (!json.has(key)) return -1;
        Object raw = json.get(key);
        if (!(raw instanceof Number)) throw new IllegalArgumentException(key + " must be numeric");
        double v = ((Number) raw).doubleValue();
        if (!Double.isFinite(v) || v < 0 || v != Math.rint(v) || v > 9_007_199_254_740_991L) throw new IllegalArgumentException(key + " invalid");
        return ((Number) raw).longValue();
    }
}
