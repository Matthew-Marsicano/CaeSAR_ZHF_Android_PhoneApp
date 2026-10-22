package org.forgesilicon.caesar;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Core temperature over time: bucket averages with low/high whiskers, optional heater-power
 * overlay on its own scale, shaded ranges (sleep), a fever reference line, and one marker.
 * Missing buckets are gaps.
 */
public final class TempChart extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<Trend.Bucket> buckets = new ArrayList<>();
    private final List<long[]> shades = new ArrayList<>();
    private long start, end, markerTime = -1;
    private double markerValue = Double.NaN;
    private boolean whiskers, heater, fahrenheit;
    private String format = "ha", emptyTitle = "Your temperature history starts here", emptyDetail = "Connect your wearable to record readings";
    private Palette palette = new Palette(true);

    public TempChart(Context context) { super(context); setContentDescription("Core temperature chart"); }

    public void setData(List<Trend.Bucket> buckets, long start, long end, String format, boolean whiskers, boolean heater, boolean fahrenheit, Palette palette) {
        this.buckets = buckets == null ? new ArrayList<>() : buckets; this.start = start; this.end = end; this.format = format;
        this.whiskers = whiskers; this.heater = heater; this.fahrenheit = fahrenheit; this.palette = palette;
        shades.clear(); markerTime = -1; invalidate();
    }
    public void shade(long from, long to) { shades.add(new long[]{from, to}); invalidate(); }
    public void marker(long time, double value) { markerTime = time; markerValue = value; invalidate(); }
    public void empty(String title, String detail) { emptyTitle = title; emptyDetail = detail; }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    private double unit(double c) { return fahrenheit ? c * 9 / 5 + 32 : c; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = dp(8), right = getWidth() - dp(40), top = dp(18), bottom = getHeight() - dp(30);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); paint.setTextSize(dp(11)); paint.setStyle(Paint.Style.FILL);
        double lo = Double.NaN, hi = Double.NaN, hLo = Double.NaN, hHi = Double.NaN;
        for (Trend.Bucket b : buckets) {
            if (b.count == 0) continue;
            double l = whiskers ? b.low : b.avg, h = whiskers ? b.high : b.avg;
            lo = Double.isNaN(lo) ? l : Math.min(lo, l); hi = Double.isNaN(hi) ? h : Math.max(hi, h);
            if (!Double.isNaN(b.heater)) { hLo = Double.isNaN(hLo) ? b.heater : Math.min(hLo, b.heater); hHi = Double.isNaN(hHi) ? b.heater : Math.max(hHi, b.heater); }
        }
        if (Double.isNaN(lo) || end <= start) {
            paint.setColor(palette.muted); paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(emptyTitle, getWidth() / 2f, getHeight() / 2f, paint);
            canvas.drawText(emptyDetail, getWidth() / 2f, getHeight() / 2f + dp(20), paint);
            paint.setTextAlign(Paint.Align.LEFT); return;
        }
        // Round tick steps so grid labels land on readable values.
        double span = Math.max(unit(hi) - unit(lo), fahrenheit ? 0.8 : 0.4), step = 1;
        for (double s : new double[]{0.1, 0.2, 0.25, 0.5, 1, 2}) if (span / s <= 4) { step = s; break; }
        double min = Math.floor(unit(lo) / step) * step, max = Math.ceil(unit(hi) / step) * step;
        if (max - unit(hi) < step * 0.25) max += step;
        if (unit(lo) - min < step * 0.25) min -= step;
        final double fMin = min, fMax = max;
        java.util.function.DoubleUnaryOperator y = v -> bottom - (bottom - top) * (unit(v) - fMin) / (fMax - fMin);
        java.util.function.LongFunction<Float> x = t -> left + (right - left) * (float) ((t - start) / (double) (end - start));

        paint.setColor(Palette.alpha(palette.sleep, palette.dark ? 46 : 34));
        for (long[] s : shades) canvas.drawRect(Math.max(left, x.apply(s[0])), top, Math.min(right, x.apply(s[1])), bottom, paint);
        int lines = (int) Math.round((max - min) / step);
        for (int i = 0; i <= lines; i++) {
            float gy = top + (bottom - top) * i / lines;
            paint.setColor(palette.grid); paint.setStrokeWidth(dp(1)); canvas.drawLine(left, gy, right, gy, paint);
            paint.setColor(palette.muted); canvas.drawText(String.format(Locale.getDefault(), step < 0.25 || step == 0.25 ? "%.2f°" : "%.1f°", max - (max - min) * i / lines), right + dp(5), gy + dp(4), paint);
        }
        double fever = IllnessWatch.FEVER;
        if (unit(fever) <= max && unit(fever) >= min) {
            float fy = (float) y.applyAsDouble(fever);
            paint.setColor(palette.alert); paint.setStrokeWidth(dp(1.2f)); paint.setPathEffect(new DashPathEffect(new float[]{dp(5), dp(4)}, 0));
            canvas.drawLine(left, fy, right, fy, paint); paint.setPathEffect(null);
            paint.setTextAlign(Paint.Align.RIGHT); canvas.drawText("Fever " + (fahrenheit ? "100.4°" : "38.0°"), right - dp(2), fy - dp(4), paint); paint.setTextAlign(Paint.Align.LEFT);
        }
        if (heater && !Double.isNaN(hLo)) {
            double hMin = Math.max(0, hLo - 10), hMax = hHi + 10;
            Path hp = new Path(); boolean drawing = false;
            for (Trend.Bucket b : buckets) {
                if (Double.isNaN(b.heater)) { drawing = false; continue; }
                float px = x.apply((b.start + b.end) / 2), py = (float) (bottom - (bottom - top) * (b.heater - hMin) / (hMax - hMin));
                if (drawing) hp.lineTo(px, py); else { hp.moveTo(px, py); drawing = true; }
            }
            paint.setColor(palette.heater); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1.5f)); paint.setAlpha(200);
            canvas.drawPath(hp, paint); paint.setStyle(Paint.Style.FILL); paint.setAlpha(255);
            canvas.drawText(String.format(Locale.getDefault(), "Heater %.0f–%.0f mW", hMin, hMax), left + dp(4), top - dp(5), paint);
        }
        Path path = new Path(); boolean drawing = false;
        float dot = buckets.size() > 150 ? 0 : dp(2.6f);
        for (Trend.Bucket b : buckets) {
            if (b.count == 0) { drawing = false; continue; }
            float px = x.apply((b.start + b.end) / 2), py = (float) y.applyAsDouble(b.avg);
            if (whiskers) {
                paint.setColor(palette.accent); paint.setAlpha(60); paint.setStrokeWidth(dp(3));
                canvas.drawLine(px, (float) y.applyAsDouble(b.high), px, (float) y.applyAsDouble(b.low), paint); paint.setAlpha(255);
            }
            if (dot > 0) { paint.setColor(palette.accent); canvas.drawCircle(px, py, dot, paint); }
            if (drawing) path.lineTo(px, py); else { path.moveTo(px, py); drawing = true; }
        }
        paint.setColor(palette.accent); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2.2f)); paint.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawPath(path, paint); paint.setStyle(Paint.Style.FILL);
        if (markerTime > 0 && !Double.isNaN(markerValue)) {
            float mx = x.apply(markerTime), my = (float) y.applyAsDouble(markerValue);
            paint.setColor(palette.card); canvas.drawCircle(mx, my, dp(6), paint);
            paint.setColor(palette.sleep); canvas.drawCircle(mx, my, dp(4.5f), paint);
            paint.setTextAlign(Paint.Align.CENTER); canvas.drawText("Low", mx, my + dp(18), paint); paint.setTextAlign(Paint.Align.LEFT);
        }
        DateTimeFormatter f = DateTimeFormatter.ofPattern(format, Locale.getDefault());
        paint.setColor(palette.muted);
        for (int i = 0; i < 3; i++) {
            long stamp = start + (end - start) * i / 2;
            if (i == 2) stamp = end - 1;
            paint.setTextAlign(i == 0 ? Paint.Align.LEFT : i == 2 ? Paint.Align.RIGHT : Paint.Align.CENTER);
            canvas.drawText(Instant.ofEpochMilli(stamp).atZone(ZoneId.systemDefault()).format(f), left + (right - left) * i / 2, bottom + dp(22), paint);
        }
        paint.setTextAlign(Paint.Align.LEFT);
    }
}
