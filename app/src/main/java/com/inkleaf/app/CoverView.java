package com.inkleaf.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.View;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A book cover at 2:3, with a progress badge and bar when at least 2% has been read.
 * Books without a cover image get a plain title card.
 */
public class CoverView extends View {
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(
            (int) Math.min(Runtime.getRuntime().maxMemory() / 6, 48L * 1024 * 1024)) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private final float d;
    private String path = "", title = "", author = "";
    private Bitmap bitmap;
    private int percent = -1;
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG), img = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG), authorPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG), badgePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    public CoverView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        border.setStyle(Paint.Style.STROKE);
        border.setColor(Color.BLACK);
        border.setStrokeWidth(Math.max(1f, d * 1.2f));
        titlePaint.setColor(Color.BLACK);
        titlePaint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        authorPaint.setColor(0xFF333333);
        authorPaint.setTypeface(Typeface.SERIF);
        badgePaint.setColor(Color.WHITE);
        badgePaint.setTypeface(Typeface.DEFAULT_BOLD);
        badgePaint.setTextAlign(Paint.Align.CENTER);
    }

    /** Binds a book; the cover image loads in the background if it isn't cached yet. */
    public void bind(Context c, File book, String title, String author, int percent) {
        this.title = title == null ? "" : title;
        this.author = author == null ? "" : author;
        this.percent = percent;
        String p = book.getAbsolutePath();
        boolean same = p.equals(path);
        path = p;
        File cover = EpubMeta.coverFile(c, book);
        String key = cover.getAbsolutePath();
        Bitmap hit = CACHE.get(key);
        if (hit != null) bitmap = hit;
        else {
            if (!same) bitmap = null;
            if (cover.isFile()) {
                LOADER.execute(() -> {
                    Bitmap b = BitmapFactory.decodeFile(key);
                    if (b == null) return;
                    CACHE.put(key, b);
                    UI.post(() -> { if (p.equals(path)) { bitmap = b; invalidate(); } });
                });
            }
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        setMeasuredDimension(width, Math.round(width * 1.5f));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), s = border.getStrokeWidth();
        RectF box = new RectF(s / 2, s / 2, w - s / 2, h - s / 2);
        if (bitmap != null) {
            // Centre-crop to the 2:3 frame.
            float scale = Math.max(w / bitmap.getWidth(), h / bitmap.getHeight());
            float sw = w / scale, sh = h / scale;
            int sx = Math.round((bitmap.getWidth() - sw) / 2), sy = Math.round((bitmap.getHeight() - sh) / 2);
            c.drawBitmap(bitmap, new Rect(sx, sy, Math.round(sx + sw), Math.round(sy + sh)), new RectF(0, 0, w, h), img);
        } else {
            fill.setColor(Color.WHITE);
            c.drawRect(box, fill);
            float pad = w * 0.1f;
            // inner frame, like a printed title page
            Paint thin = new Paint(border);
            thin.setStrokeWidth(Math.max(1f, d * 0.6f));
            c.drawRect(pad * 0.5f, pad * 0.5f, w - pad * 0.5f, h - pad * 0.5f, thin);
            titlePaint.setTextSize(Math.max(10 * d, w * 0.11f));
            authorPaint.setTextSize(Math.max(8 * d, w * 0.075f));
            int tw = Math.max(1, Math.round(w - 2 * pad));
            StaticLayout tl = StaticLayout.Builder.obtain(title, 0, title.length(), titlePaint, tw)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(5).setEllipsize(TextUtils.TruncateAt.END).build();
            StaticLayout al = StaticLayout.Builder.obtain(author, 0, author.length(), authorPaint, tw)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build();
            c.save();
            c.translate(pad, h * 0.22f);
            tl.draw(c);
            c.restore();
            c.save();
            c.translate(pad, h * 0.78f - al.getHeight());
            al.draw(c);
            c.restore();
        }
        c.drawRect(box, border);

        if (percent >= 2) {
            // Progress bar along the bottom edge.
            float barH = Math.max(4 * d, h * 0.025f), inset = s;
            fill.setColor(Color.WHITE);
            c.drawRect(inset, h - barH - inset, w - inset, h - inset, fill);
            fill.setColor(Color.BLACK);
            c.drawRect(inset, h - barH - inset, inset + (w - 2 * inset) * Math.min(100, percent) / 100f, h - inset, fill);
            c.drawLine(inset, h - barH - inset, w - inset, h - barH - inset, border);
            // Percentage badge just above it.
            String label = percent >= 100 ? "Done" : percent + "%";
            badgePaint.setTextSize(Math.max(10 * d, Math.min(15 * d, w * 0.13f)));
            float tw = badgePaint.measureText(label), th = badgePaint.getTextSize();
            float bw = tw + th * 0.9f, bh = th * 1.45f, m = w * 0.05f;
            RectF badge = new RectF(w - m - bw, h - barH - inset - m - bh, w - m, h - barH - inset - m);
            fill.setColor(Color.BLACK);
            c.drawRoundRect(badge, bh / 2, bh / 2, fill);
            Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setStyle(Paint.Style.STROKE);
            ring.setColor(Color.WHITE);
            ring.setStrokeWidth(Math.max(1f, d));
            c.drawRoundRect(badge, bh / 2, bh / 2, ring);
            c.drawText(label, badge.centerX(), badge.centerY() + th * 0.35f, badgePaint);
        }
    }
}
