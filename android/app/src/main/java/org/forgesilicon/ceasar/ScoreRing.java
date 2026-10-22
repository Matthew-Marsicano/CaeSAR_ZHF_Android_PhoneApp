package org.forgesilicon.ceasar;

import android.content.Context;
import android.graphics.*;
import android.view.View;

public final class ScoreRing extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int score = -1;
    private Palette palette = new Palette(true);
    public ScoreRing(Context context) { super(context); setContentDescription("Rhythm score"); }
    public void setScore(int score, Palette palette) {
        this.score = score; this.palette = palette;
        setContentDescription(score < 0 ? "Rhythm score needs a few nights of data" : "Rhythm score " + score + " of 100"); invalidate();
    }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float density = getResources().getDisplayMetrics().density, stroke = 12 * density;
        float size = Math.min(getWidth(), getHeight()) - stroke * 2, left = (getWidth() - size) / 2, top = (getHeight() - size) / 2;
        RectF bounds = new RectF(left, top, left + size, top + size);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(stroke); paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(palette.chip); c.drawArc(bounds, 0, 360, false, paint);
        paint.setColor(palette.fill()); if (score > 0) c.drawArc(bounds, -90, 3.6f * score, false, paint);
        paint.setStyle(Paint.Style.FILL); paint.setTextAlign(Paint.Align.CENTER); paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); paint.setTextSize(36 * density);
        paint.setColor(palette.text); c.drawText(score < 0 ? "—" : String.valueOf(score), getWidth() / 2f, getHeight() / 2f + 7 * density, paint);
        paint.setTextSize(11 * density); paint.setColor(palette.muted); c.drawText("OF 100", getWidth() / 2f, getHeight() / 2f + 28 * density, paint);
    }
}
