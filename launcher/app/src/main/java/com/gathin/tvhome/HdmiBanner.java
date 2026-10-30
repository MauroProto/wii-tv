package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

/**
 * Animated Wii Channel tile for an active HDMI input.
 * Displays the live input label, pulsing green EN VIVO indicator,
 * and a stylized TV/HDMI monitor graphic with scanlines and glow.
 */
final class HdmiBanner extends Drawable implements WiiMenuView.LiveArt {

    private String label;
    private final Typeface face;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path plug = new Path();

    HdmiBanner(String label, Typeface face) {
        this.label = (label != null && !label.isEmpty()) ? label : "HDMI";
        this.face = face;
        stroke.setStyle(Paint.Style.STROKE);
    }

    public void setLabel(String label) {
        this.label = (label != null && !label.isEmpty()) ? label : "HDMI";
        invalidateSelf();
    }

    @Override
    public boolean animating() {
        return true;
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        int w = b.width();
        int h = b.height();
        if (w <= 0 || h <= 0) return;

        int save = c.save();
        c.translate(b.left, b.top);

        long now = SystemClock.uptimeMillis();
        float t = (now % 3000L) / 3000f; // 0..1 in 3s
        float pulse = (float) (0.6f + 0.4f * Math.sin(t * Math.PI * 2));

        // 1. Deep tech background gradient
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, 0, 0, h,
                0xFF0F1E36, 0xFF080D18, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);

        // 2. Subtle scanning lines
        paint.setColor(0x0CFFFFFF);
        for (int y = 0; y < h; y += 6) {
            c.drawRect(0, y, w, y + 2, paint);
        }

        // 3. Ambient sweep beam moving down
        float sweepY = (now % 2400L) / 2400f * (h + 80) - 40;
        paint.setShader(new LinearGradient(0, sweepY - 30, 0, sweepY + 30,
                new int[] { 0x003FC0EC, 0x1E3FC0EC, 0x003FC0EC }, null, Shader.TileMode.CLAMP));
        c.drawRect(0, Math.max(0, sweepY - 30), w, Math.min(h, sweepY + 30), paint);
        paint.setShader(null);

        // 4. Center TV screen monitor graphic
        float tw = w * 0.48f;
        float th = tw * 0.58f;
        float cx = w * 0.5f;
        float cy = h * 0.42f;
        rect.set(cx - tw / 2f, cy - th / 2f, cx + tw / 2f, cy + th / 2f);

        // Monitor outer frame
        paint.setColor(0xFF1E2838);
        c.drawRoundRect(rect, 8f, 8f, paint);
        stroke.setColor(0xFF3FC0EC);
        stroke.setStrokeWidth(2.5f);
        c.drawRoundRect(rect, 8f, 8f, stroke);

        // Monitor inner display
        RectF inner = new RectF(rect.left + 5f, rect.top + 5f, rect.right - 5f, rect.bottom - 5f);
        paint.setColor(0xFF0A101C);
        c.drawRoundRect(inner, 4f, 4f, paint);

        // Signal icon or HDMI cable inside display
        stroke.setColor(0xFF3FC0EC);
        stroke.setStrokeWidth(2f);
        float iconY = inner.centerY();
        c.drawLine(inner.left + 12f, iconY, inner.right - 12f, iconY, stroke);
        plug.reset();
        plug.moveTo(cx - 10f, iconY - 8f);
        plug.lineTo(cx + 4f, iconY - 8f);
        plug.lineTo(cx + 10f, iconY);
        plug.lineTo(cx + 4f, iconY + 8f);
        plug.lineTo(cx - 10f, iconY + 8f);
        plug.close();
        paint.setColor(0xFF3FC0EC);
        c.drawPath(plug, paint);

        // Monitor stand neck & base
        paint.setColor(0xFF1E2838);
        c.drawRect(cx - 6f, rect.bottom, cx + 6f, rect.bottom + 8f, paint);
        rect.set(cx - 24f, rect.bottom + 6f, cx + 24f, rect.bottom + 12f);
        c.drawRoundRect(rect, 3f, 3f, paint);

        // 5. Top Live Badge: [ ● EN VIVO ]
        float badgeY = h * 0.16f;
        int greenAlpha = Math.round(255 * pulse);
        paint.setColor((greenAlpha << 24) | 0x0000E676);
        c.drawCircle(w * 0.16f, badgeY - 5f, 5f, paint);
        paint.setColor(0x3000E676);
        c.drawCircle(w * 0.16f, badgeY - 5f, 8f * pulse, paint);

        paint.setTypeface(face != null ? face : Typeface.SANS_SERIF);
        paint.setTextSize(h * 0.085f);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setColor(0xFF00E676);
        c.drawText("EN VIVO", w * 0.16f + 12f, badgeY, paint);

        // 6. Label (e.g. "HDMI 1", "PlayStation", etc.)
        paint.setTextSize(h * 0.16f);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(0x99000000);
        c.drawText(label, cx + 1f, h * 0.82f + 1f, paint);
        paint.setColor(0xFFFFFFFF);
        c.drawText(label, cx, h * 0.82f, paint);

        // 7. Subtext
        paint.setTextSize(h * 0.08f);
        paint.setColor(0xFF64B5F6);
        c.drawText("Presiona para ver", cx, h * 0.93f, paint);

        c.restoreToCount(save);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }
}
