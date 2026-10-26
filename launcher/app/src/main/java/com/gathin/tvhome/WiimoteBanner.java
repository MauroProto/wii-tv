package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

/** The Wiimote channel's tile: the white remote leaning toward its name, waves coming off its tip, on pale blue. */
final class WiimoteBanner extends Drawable {
    private final String label;
    private final RemoteArt remote;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private Shader sky;
    private float laidH = -1f;

    WiimoteBanner(String label, Typeface face) {
        this.label = label;
        remote = new RemoteArt(face);
        wave.setStyle(Paint.Style.STROKE);
        wave.setStrokeCap(Paint.Cap.ROUND);
        text.setTypeface(face);
        text.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float w = b.width(), h = b.height();
        int save = c.save();
        c.translate(b.left, b.top);
        if (h != laidH) {
            laidH = h;
            sky = new LinearGradient(0f, 0f, 0f, h, 0xFFFFFFFF, 0xFFCDEBF8, Shader.TileMode.CLAMP);
        }
        fill.setShader(sky);
        c.drawRect(0f, 0f, w, h, fill);
        fill.setShader(null);

        float u = Math.min(w / 16f, h / 9f), rh = 7.2f * u;
        int lean = c.save();
        c.translate(w * 0.24f, h * 0.56f);
        c.rotate(24f);
        float tip = -rh / 2f - 0.3f * u;
        for (int i = 0; i < 3; i++) {
            float radius = (0.7f + 0.6f * i) * u;
            wave.setStrokeWidth(0.24f * u);
            wave.setColor((RemoteArt.BLUE & 0x00FFFFFF) | (230 - 65 * i) << 24);
            oval.set(-radius, tip - radius, radius, tip + radius);
            c.drawArc(oval, 232f, 76f, false, wave);
        }
        remote.draw(c, rh, 1);
        c.restoreToCount(lean);

        text.setTextSize(1.85f * u);
        text.setColor(0xFF27A3DA);
        c.drawText(label, w * 0.66f, h * 0.5f + 0.65f * u, text);
        c.restoreToCount(save);
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        fill.setColorFilter(colorFilter);
        wave.setColorFilter(colorFilter);
        text.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }
}
