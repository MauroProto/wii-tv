package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

/** The white remote drawn in code, for the Wiimote channel's tile and screen: upright, centred on (0, 0). */
final class RemoteArt {
    static final int BLUE = 0xFF3FC0EC;
    private static final int KEY = 0xFFC9CED3, BUTTON = 0xFFECEEF0, RIM = 0xFFC3C9CE, LED_OFF = 0xFFD3D9DE,
            LABEL = 0xFFA4ABB2;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private Shader body;
    private float bodyH = -1f;

    RemoteArt(Typeface bold) {
        line.setStyle(Paint.Style.STROKE);
        label.setTypeface(bold);
        label.setTextAlign(Paint.Align.CENTER);
        label.setColor(LABEL);
    }

    /** h px tall; lit has a bit for each player light that is on, bit 0 for light 1. */
    void draw(Canvas c, float h, int lit) {
        float w = h * 0.25f, top = -h / 2f, round = w * 0.46f;
        if (h != bodyH) {
            bodyH = h;
            body = new LinearGradient(-w / 2f, 0f, w / 2f, 0f, new int[] {0xFFD9DEE2, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFD9DEE2},
                    new float[] {0f, 0.28f, 0.72f, 1f}, Shader.TileMode.CLAMP);
        }
        r.set(-w / 2f, top, w / 2f, h / 2f);
        r.offset(w * 0.06f, w * 0.1f);
        fill.setColor(0x26000000);
        c.drawRoundRect(r, round, round, fill);
        r.set(-w / 2f, top, w / 2f, h / 2f);
        fill.setColor(0xFFFFFFFF);
        fill.setShader(body);
        c.drawRoundRect(r, round, round, fill);
        fill.setShader(null);
        line.setColor(RIM);
        line.setStrokeWidth(w * 0.025f);
        c.drawRoundRect(r, round, round, line);

        float padY = top + h * 0.16f, arm = w * 0.27f, half = w * 0.085f;
        fill.setColor(KEY);
        r.set(-arm, padY - half, arm, padY + half);
        c.drawRoundRect(r, half * 0.5f, half * 0.5f, fill);
        r.set(-half, padY - arm, half, padY + arm);
        c.drawRoundRect(r, half * 0.5f, half * 0.5f, fill);

        button(c, 0f, top + h * 0.33f, w * 0.2f, "A");
        float rowY = top + h * 0.56f, small = w * 0.085f;
        button(c, -w * 0.3f, rowY, small, null);
        button(c, 0f, rowY, small, null);
        button(c, w * 0.3f, rowY, small, null);
        fill.setColor(BLUE);
        c.drawCircle(0f, rowY, small * 0.45f, fill);
        button(c, 0f, top + h * 0.72f, w * 0.13f, "1");
        button(c, 0f, top + h * 0.83f, w * 0.13f, "2");

        float ledY = h / 2f - h * 0.045f, ledW = w * 0.11f, ledH = w * 0.045f;
        for (int i = 0; i < 4; i++) {
            float cx = (i - 1.5f) * w * 0.2f;
            r.set(cx - ledW / 2f, ledY - ledH / 2f, cx + ledW / 2f, ledY + ledH / 2f);
            fill.setColor((lit >> i & 1) != 0 ? BLUE : LED_OFF);
            c.drawRoundRect(r, ledH / 2f, ledH / 2f, fill);
        }
    }

    private void button(Canvas c, float x, float y, float radius, String text) {
        fill.setColor(BUTTON);
        c.drawCircle(x, y, radius, fill);
        line.setColor(RIM);
        line.setStrokeWidth(radius * 0.12f);
        c.drawCircle(x, y, radius, line);
        // Too small to read on the tile; on the channel's screen the letters show.
        if (text != null && radius > 14f) {
            label.setTextSize(radius * 0.95f);
            c.drawText(text, x, y + radius * 0.34f, label);
        }
    }
}
