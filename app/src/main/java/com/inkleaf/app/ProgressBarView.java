package com.inkleaf.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** Thin e-ink friendly progress bar: outlined track, solid fill, optional chapter ticks. */
public class ProgressBarView extends View {
    private float progress = 0f;
    private float[] ticks = new float[0];
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ProgressBarView(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        fill.setColor(Color.BLACK);
        track.setColor(0xFF777777);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(Math.max(1f, d * 0.75f));
        tick.setColor(0xFF555555);
        tick.setStrokeWidth(Math.max(1f, d * 0.75f));
    }

    /** progress 0..1; ticks are chapter starts as 0..1 positions (empty for none). */
    public void set(float progress, float[] ticks) {
        this.progress = Math.max(0f, Math.min(1f, progress));
        this.ticks = ticks == null ? new float[0] : ticks;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float half = track.getStrokeWidth() / 2f;
        float top = h * 0.2f, bottom = h * 0.8f;
        c.drawRect(half, top, w - half, bottom, track);
        c.drawRect(0, top, w * progress, bottom, fill);
        // Chapter ticks: drawn above and below the bar so they stay visible over the fill.
        for (float t : ticks) {
            if (t <= 0f || t >= 1f) continue;
            float x = w * t;
            c.drawLine(x, 0, x, top, tick);
            c.drawLine(x, bottom, x, h, tick);
        }
    }
}
