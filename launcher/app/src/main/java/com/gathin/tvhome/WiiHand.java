package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Typeface;

/**
 * The white pointing hand with the "1" badge that the console menus use as their cursor. Drawn at its
 * fingertip; s is screen px per 1920-wide reference px.
 */
final class WiiHand {
    /** The hand was first drawn in units of 1/1024 of the width. */
    private static final float UNIT = 1920f / 1024f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF badge = new RectF();
    private final RectF dst = new RectF();
    private final Matrix matrix = new Matrix();
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint blur = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private Path hand, lines;
    private float s = -1f;
    private Bitmap point, pointBlur, grab, grabBlur;
    /** Shows the closed hand that carries something, when there is a sprite for it. */
    boolean grabbing;

    WiiHand(Typeface bold) {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        text.setTypeface(bold);
        text.setTextAlign(Paint.Align.CENTER);
    }

    /** Index finger up, three curled fingers, palm and thumb, fingertip at (0, 0). */
    void setScale(float scale) {
        if (scale == s) return;
        s = scale;
        float u = UNIT * s;
        Path p = new Path();
        p.addRoundRect(new RectF(0f, 0f, 11f * u, 34f * u), 5.5f * u, 5.5f * u, Path.Direction.CW);
        union(p, u, 10f, 16f, 19f, 34f, 4.5f);
        union(p, u, 18f, 19f, 26f, 36f, 4f);
        union(p, u, 25f, 22f, 32f, 38f, 3.5f);
        union(p, u, -3f, 26f, 32f, 50f, 9f);
        union(p, u, -11f, 25f, 4f, 37f, 6f);
        Path l = new Path();
        l.moveTo(10.6f * u, 23f * u);
        l.lineTo(10.6f * u, 31f * u);
        l.moveTo(18.4f * u, 25f * u);
        l.lineTo(18.4f * u, 33f * u);
        l.moveTo(25.6f * u, 28f * u);
        l.lineTo(25.6f * u, 35f * u);
        l.moveTo(3f * u, 32f * u);
        l.lineTo(9f * u, 34f * u);
        Matrix m = new Matrix();
        m.setTranslate(-5.5f * u, 0f);
        p.transform(m);
        l.transform(m);
        hand = p;
        lines = l;
    }

    /** The original's pointing and grabbing hands, with their blurred shadows; null keeps the drawn hand. */
    void useSprites(Bitmap point, Bitmap pointBlur, Bitmap grab, Bitmap grabBlur) {
        this.point = point;
        this.pointBlur = pointBlur;
        this.grab = grab;
        this.grabBlur = grabBlur;
        blur.setColorFilter(new PorterDuffColorFilter(0xFF000000, PorterDuff.Mode.SRC_IN));
        blur.setAlpha(0x48);
    }

    private static void union(Path p, float u, float l, float t, float r, float b, float rad) {
        Path q = new Path();
        q.addRoundRect(new RectF(l * u, t * u, r * u, b * u), rad * u, rad * u, Path.Direction.CW);
        p.op(q, Path.Op.UNION);
    }

    void draw(Canvas c, float x, float y) {
        if (hand == null) return;
        float u = UNIT * s;
        if (point != null) {
            drawSprite(c, x, y, u);
            return;
        }
        int save = c.save();
        c.translate(x, y);
        c.rotate(-8f);
        c.translate(2.5f * u, 3.5f * u);
        fill.setColor(0x38000000);
        c.drawPath(hand, fill);
        c.translate(-2.5f * u, -3.5f * u);
        fill.setColor(0xFFFFFFFF);
        c.drawPath(hand, fill);
        stroke.setColor(0xFF3A3A3A);
        stroke.setStrokeWidth(2.2f * u);
        c.drawPath(hand, stroke);
        stroke.setStrokeWidth(1.5f * u);
        c.drawPath(lines, stroke);
        badge.set(-4.5f * u, 47f * u, 23.5f * u, 60f * u);
        drawBadge(c, u);
        c.restoreToCount(save);
    }

    /** The sprite hand, its fingertip (texel 14, 2) on (x, y); closed, it hangs centred under the point. */
    private void drawSprite(Canvas c, float x, float y, float u) {
        boolean closed = grabbing && grab != null;
        Bitmap b = closed ? grab : point, sh = closed ? grabBlur : pointBlur;
        float k = 1.5f * s;
        float ax = closed ? b.getWidth() / 2f : 14f, ay = closed ? b.getHeight() * 0.3f : 2f;
        int save = c.save();
        c.translate(x, y);
        if (sh != null) {
            dst.set(-ax * k + 3f * k, -ay * k + 5f * k, (sh.getWidth() - ax) * k + 3f * k, (sh.getHeight() - ay) * k + 5f * k);
            c.drawBitmap(sh, null, dst, blur);
        }
        dst.set(-ax * k, -ay * k, (b.getWidth() - ax) * k, (b.getHeight() - ay) * k);
        c.drawBitmap(b, null, dst, bmp);
        if (!closed) {
            float cx = 13f * s, top = 80f * s;
            badge.set(cx - 10.5f * u, top, cx + 10.5f * u, top + 11f * u);
            drawBadge(c, u * 0.85f);
        }
        c.restoreToCount(save);
    }

    /** What draw(x, y) covers, shadow and badge included. */
    void bounds(float x, float y, RectF out) {
        float u = UNIT * s;
        if (point == null) {
            matrix.setRotate(-8f);
            matrix.postTranslate(x, y);
            out.set(-20f * u, -3f * u, 32f * u, 64f * u);
            matrix.mapRect(out);
            return;
        }
        boolean closed = grabbing && grab != null;
        Bitmap b = closed ? grab : point, sh = closed ? grabBlur : pointBlur;
        float k = 1.5f * s;
        float ax = closed ? b.getWidth() / 2f : 14f, ay = closed ? b.getHeight() * 0.3f : 2f;
        float right = Math.max(b.getWidth(), sh == null ? 0f : sh.getWidth() + 3f) - ax;
        float bottom = Math.max(b.getHeight(), sh == null ? 0f : sh.getHeight() + 5f) - ay;
        out.set(x - ax * k, y - ay * k, x + right * k, y + bottom * k);
        if (!closed) out.bottom = Math.max(out.bottom, y + 80f * s + 13f * u);
    }

    private void drawBadge(Canvas c, float u) {        fill.setColor(0xFF47B6EA);
        c.drawRoundRect(badge, 4f * u, 4f * u, fill);
        stroke.setColor(0xFF2C86BA);
        stroke.setStrokeWidth(1.6f * u);
        c.drawRoundRect(badge, 4f * u, 4f * u, stroke);
        text.setTextSize(10.5f * u);
        text.setColor(0xFFFFFFFF);
        c.drawText("1", badge.centerX(), badge.centerY() + 3.8f * u, text);
    }
}
