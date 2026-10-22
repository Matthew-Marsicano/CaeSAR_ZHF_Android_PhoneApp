package org.forgesilicon.ceasar;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.util.Locale;
import java.util.function.DoubleFunction;

/** Nightly or per-event values as bars, signed bars around zero, or dots. NaN values are gaps. */
public final class BarChart extends View {
    public enum Mode { BARS, SIGNED, DOTS, AREA }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private double[] values = new double[0];
    private String[] labels = new String[0];
    private boolean[] highlight;
    private int[] tones;
    private Mode mode = Mode.BARS;
    private double threshold = Double.NaN, min = Double.NaN, max = Double.NaN;
    private String thresholdLabel = "", emptyText = "Not enough nights yet";
    private DoubleFunction<String> axis = v -> String.format(Locale.getDefault(), "%.1f", v);
    private Palette palette = new Palette(true);
    private int base, hot;

    public BarChart(Context context) { super(context); }

    public BarChart set(double[] values, String[] labels, Mode mode, Palette palette) {
        this.values = values; this.labels = labels; this.mode = mode; this.palette = palette;
        highlight = null; tones = null; threshold = min = max = Double.NaN; base = palette.accent; hot = palette.alert;
        invalidate(); return this;
    }
    public BarChart colors(int base, int hot) { this.base = base; this.hot = hot; invalidate(); return this; }
    public BarChart highlight(boolean[] h) { highlight = h; invalidate(); return this; }
    public BarChart tones(int[] t) { tones = t; invalidate(); return this; }
    public BarChart threshold(double v, String label) { threshold = v; thresholdLabel = label; invalidate(); return this; }
    public BarChart range(double min, double max) { this.min = min; this.max = max; invalidate(); return this; }
    public BarChart axis(DoubleFunction<String> f) { axis = f; invalidate(); return this; }
    public BarChart empty(String text) { emptyText = text; invalidate(); return this; }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float left = dp(4), right = getWidth() - dp(44), top = dp(14), bottom = getHeight() - dp(24);
        paint.setTextSize(dp(11)); paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); paint.setStyle(Paint.Style.FILL);
        double lo = Double.NaN, hi = Double.NaN;
        for (double v : values) if (Double.isFinite(v)) { lo = Double.isNaN(lo) ? v : Math.min(lo, v); hi = Double.isNaN(hi) ? v : Math.max(hi, v); }
        if (Double.isNaN(lo)) {
            paint.setColor(palette.muted); paint.setTextAlign(Paint.Align.CENTER); c.drawText(emptyText, getWidth() / 2f, getHeight() / 2f, paint); paint.setTextAlign(Paint.Align.LEFT); return;
        }
        if (Double.isFinite(threshold)) { lo = Math.min(lo, threshold); hi = Math.max(hi, threshold); }
        if (mode == Mode.BARS || mode == Mode.AREA) lo = Math.min(0, lo);
        if (mode == Mode.SIGNED) { double m = Math.max(Math.abs(lo), Math.abs(hi)) * 1.15; lo = -m; hi = m; }
        else { double pad = (hi - lo) * 0.12 + 1e-6; if (mode == Mode.DOTS) lo -= pad; hi += pad; }
        if (Double.isFinite(min)) lo = min;
        if (Double.isFinite(max)) hi = max;
        final double fLo = lo, fHi = hi;
        java.util.function.DoubleUnaryOperator y = v -> bottom - (bottom - top) * (v - fLo) / (fHi - fLo);
        for (int i = 0; i <= 2; i++) {
            double v = hi - (hi - lo) * i / 2; float gy = (float) y.applyAsDouble(v);
            paint.setColor(palette.grid); paint.setStrokeWidth(dp(1)); c.drawLine(left, gy, right, gy, paint);
            paint.setColor(palette.muted); c.drawText(axis.apply(v), right + dp(5), gy + dp(4), paint);
        }
        if (Double.isFinite(threshold)) {
            float ty = (float) y.applyAsDouble(threshold);
            paint.setColor(hot); paint.setStrokeWidth(dp(1.2f)); paint.setPathEffect(new DashPathEffect(new float[]{dp(5), dp(4)}, 0));
            c.drawLine(left, ty, right, ty, paint); paint.setPathEffect(null);
            if (!thresholdLabel.isEmpty()) { paint.setTextAlign(Paint.Align.RIGHT); c.drawText(thresholdLabel, right - dp(2), ty - dp(4), paint); paint.setTextAlign(Paint.Align.LEFT); }
        }
        int n = values.length;
        float slot = (right - left) / Math.max(1, n), width = Math.max(dp(1.5f), Math.min(dp(14), slot * 0.62f));
        Path area = new Path(); boolean drawing = false;
        for (int i = 0; i < n; i++) {
            if (!Double.isFinite(values[i])) { drawing = false; continue; }
            float cx = left + slot * (i + 0.5f);
            int color = tones != null && tones[i] != 0 ? tones[i] : highlight != null && highlight[i] ? hot : base;
            paint.setColor(color);
            float vy = (float) y.applyAsDouble(values[i]);
            if (mode == Mode.DOTS) c.drawCircle(cx, vy, dp(3.8f), paint);
            else if (mode == Mode.AREA) { if (drawing) area.lineTo(cx, vy); else { area.moveTo(cx, vy); drawing = true; } }
            else {
                float zero = (float) y.applyAsDouble(mode == Mode.SIGNED ? 0 : Math.max(0, lo));
                RectF r = new RectF(cx - width / 2, Math.min(zero, vy), cx + width / 2, Math.max(zero, vy));
                if (r.height() < dp(2)) { r.top -= dp(1); r.bottom += dp(1); }
                c.drawRoundRect(r, dp(3), dp(3), paint);
            }
        }
        if (mode == Mode.AREA) { paint.setColor(base); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2)); c.drawPath(area, paint); paint.setStyle(Paint.Style.FILL); }
        paint.setColor(palette.muted); paint.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < Math.min(n, labels.length); i++) {
            if (labels[i] == null) continue;
            float half = paint.measureText(labels[i]) / 2, lx = Math.max(left + half, Math.min(right - half, left + slot * (i + 0.5f)));
            c.drawText(labels[i], lx, bottom + dp(17), paint);
        }
        paint.setTextAlign(Paint.Align.LEFT);
    }
}
