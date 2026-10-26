package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

/**
 * The Grok channel's picture: a white speech bubble whose three dots bounce as if someone were typing, under a
 * night sky of twinkling stars, over the channel's name. Laid out in a 16:9 box that scales with the space, so the
 * same picture is the menu tile and the screen the channel shows while its page loads.
 */
final class GrokBanner extends Drawable implements WiiMenuView.LiveArt {
    static final int NIGHT = 0xFF070B18;
    private static final int STARS = 44;
    /** Seconds for the three dots to bounce once, one after the other. */
    private static final float DOTS_LOOP = 1.4f;
    private static final int DOT = 0xFF8995AA, DOT_UP = 0xFF3FC0EC;

    private final String label;
    /** x, y, size and twinkle speed of each star, all 0 to 1. */
    private final float[] stars = new float[STARS * 4];
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path bubble = new Path();
    private Shader sky, glow, white;
    private float laidW = -1f, laidH = -1f, u, cx, cy;

    GrokBanner(String label, Typeface face) {
        this.label = label;
        text.setTypeface(face);
        text.setTextAlign(Paint.Align.CENTER);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeJoin(Paint.Join.ROUND);
        long seed = 0x2545F4914F6CDD1DL;
        for (int i = 0; i < stars.length; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            stars[i] = (seed >>> 40) / (float) (1L << 24);
        }
    }

    /** The picture in a w x h box at (0, 0); typing makes the dots bounce. */
    void paint(Canvas c, float w, float h, long now, boolean typing) {
        if (w != laidW || h != laidH) layout(w, h);
        fill.setShader(sky);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(glow);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);

        float t = (now % 3_600_000L) / 1000f;
        // Stars grow slower than the picture: full screen, they would be snowflakes.
        float star = (float) Math.pow(h / 205f, 0.4f);
        fill.setColor(0xFFFFFFFF);
        for (int i = 0; i < STARS; i++) {
            float twinkle = 0.5f + 0.5f * (float) Math.sin(t * (0.6f + 1.4f * stars[i * 4 + 3]) + i * 2.4f);
            fill.setAlpha(Math.round(70 + 185 * twinkle));
            c.drawCircle(stars[i * 4] * w, stars[i * 4 + 1] * h * 0.92f, (1.1f + 1.6f * stars[i * 4 + 2]) * star, fill);
        }
        fill.setAlpha(255);

        line.setColor(0x403FC0EC);
        line.setStrokeWidth(0.5f * u);
        c.drawPath(bubble, line);
        fill.setShader(white);
        c.drawPath(bubble, fill);
        fill.setShader(null);
        line.setColor(0xFFB4C2D6);
        line.setStrokeWidth(0.09f * u);
        c.drawPath(bubble, line);

        for (int i = 0; i < 3; i++) {
            float lift = 0f;
            if (typing) {
                float p = (((t - i * 0.17f) % DOTS_LOOP) + DOTS_LOOP) % DOTS_LOOP / DOTS_LOOP;
                if (p < 0.36f) lift = (float) Math.sin(p / 0.36f * Math.PI);
            }
            fill.setColor(PixelArt.mix(DOT, DOT_UP, lift));
            c.drawCircle(cx + (i - 1) * 1.3f * u, cy - lift * 0.42f * u, 0.4f * u, fill);
        }

        text.setTextSize(1.55f * u);
        text.setColor(0xF2FFFFFF);
        c.drawText(label, cx, h / 2f + 3.3f * u, text);
    }

    private void layout(float w, float h) {
        laidW = w;
        laidH = h;
        u = Math.min(w / 16f, h / 9f);
        cx = w / 2f;
        cy = h / 2f - 1f * u;
        float bw = 6f * u, bh = 3f * u;
        RectF r = new RectF(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f);
        bubble.reset();
        bubble.addRoundRect(r, bh / 2f, bh / 2f, Path.Direction.CW);
        Path tail = new Path();
        tail.moveTo(cx - bw * 0.30f, r.bottom - 0.4f * u);
        tail.lineTo(cx - bw * 0.36f, r.bottom + 0.85f * u);
        tail.lineTo(cx - bw * 0.10f, r.bottom - 0.3f * u);
        tail.close();
        bubble.op(tail, Path.Op.UNION);
        sky = new LinearGradient(0, 0, 0, h, new int[] {0xFF03050C, NIGHT, 0xFF15204A}, new float[] {0f, 0.55f, 1f},
                Shader.TileMode.CLAMP);
        glow = new RadialGradient(w / 2f, h * 1.05f, Math.max(w, h) * 0.6f, 0x553FC0EC, 0x003FC0EC,
                Shader.TileMode.CLAMP);
        white = new LinearGradient(0, r.top, 0, r.bottom + 0.85f * u, 0xFFFFFFFF, 0xFFDCE4EF, Shader.TileMode.CLAMP);
    }

    @Override
    public boolean animating() {
        return true;
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        int save = c.save();
        c.translate(b.left, b.top);
        paint(c, b.width(), b.height(), SystemClock.uptimeMillis(), true);
        c.restoreToCount(save);
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        fill.setColorFilter(colorFilter);
        line.setColorFilter(colorFilter);
        text.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }
}
