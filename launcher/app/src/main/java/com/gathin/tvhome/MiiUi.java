package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.KeyEvent;

import java.util.List;

/**
 * Shared look of the Mii channel: slate-grey round buttons, dialogs, pills, the blue focus glow and text,
 * plus D-pad navigation between rectangles. Everything takes reference pixels (1920 x 1080) and scales
 * by s itself.
 */
final class MiiUi {
    static final int BLUE = 0xFF3FC0EC;
    static final int SLATE = 0xFF5F7373;
    static final int SLATE_ICON = 0xFF7E9090;

    static final int ICON_EDIT = 0, ICON_NEW = 1, ICON_SORT = 2, ICON_HELP = 3, ICON_PARADE = 4, ICON_MENU = 5,
            ICON_TRASH = 6, ICON_ABC = 7, ICON_FAV = 8, ICON_COLOR = 9, ICON_GENDER = 10,
            ICON_BOY = 11, ICON_GIRL = 12, ICON_REMOTE = 13, ICON_MAIL = 14;

    /** Something drawn over the current screen that takes the keys while it is open. */
    interface Overlay {
        void draw(Canvas c);

        /** Handles a key; Back arrives here too. */
        void key(int keyCode);

        /** Where the hand's fingertip goes, in reference px. */
        void handTarget(float[] out);

        default void step(float dt) {}
    }

    /** One thing the hand can point at. */
    static class Item {
        final RectF r = new RectF();
        int kind, a, b;
        float lift;

        Item set(int kind, int a, int b, float l, float t, float rr, float bb) {
            this.kind = kind;
            this.a = a;
            this.b = b;
            r.set(l, t, rr, bb);
            return this;
        }

        boolean same(Item o) {
            return o != null && o.kind == kind && o.a == a && o.b == b;
        }
    }

    /**
     * The nearest item from rects[cur] in direction (dx, dy): along the direction first, sideways counting
     * double, and sideways not at all when the two overlap across the direction.
     */
    static int nav(List<? extends Item> items, int cur, int dx, int dy) {
        if (cur < 0 || cur >= items.size()) return items.isEmpty() ? -1 : 0;
        return navFrom(items, items.get(cur).r, cur, dx, dy);
    }

    static int navFrom(List<? extends Item> items, RectF a, int skip, int dx, int dy) {
        float ax = a.centerX(), ay = a.centerY();
        int best = -1;
        float bestScore = Float.MAX_VALUE;
        for (int i = 0; i < items.size(); i++) {
            if (i == skip) continue;
            RectF b = items.get(i).r;
            float bx = b.centerX(), by = b.centerY();
            float primary, perp;
            if (dx != 0) {
                primary = (bx - ax) * dx;
                float ov = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
                perp = ov > 0 ? 0f : Math.abs(by - ay);
            } else {
                primary = (by - ay) * dy;
                float ov = Math.min(a.right, b.right) - Math.max(a.left, b.left);
                perp = ov > 0 ? 0f : Math.abs(bx - ax);
            }
            if (primary <= 2f) continue;
            float score = primary + perp * 2.2f;
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    static boolean isOk(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A;
    }

    static int dx(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
    }

    static int dy(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
    }

    static float approach(float v, float target, float k, float eps) {
        float n = v + (target - v) * k;
        return Math.abs(target - n) < eps ? target : n;
    }

    final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final Typeface bold;
    final Typeface regular;
    final WiiHand hand;
    final RectF rect = new RectF();
    final RectF tmp = new RectF();
    final Path path = new Path();
    float s = 1f;
    /** The original's interface sprites, when a pack brought them. */
    MiiUiSprites sprites;

    /** Kept vertical gradients: most boxes stay put from frame to frame and can draw with the one they had. */
    private static final int GRADIENTS = 64;
    private final LinearGradient[] gradients = new LinearGradient[GRADIENTS];
    private final int[] gradientColors = new int[GRADIENTS * 2];
    private final float[] gradientEnds = new float[GRADIENTS * 2];
    /** What darkPanel and darkPill draw into, in screen px, so they make no rectangles of their own each frame. */
    private final RectF panelBody = new RectF(), panelRim = new RectF(), panelBar = new RectF(), pillBody = new RectF();

    MiiUi(Typeface bold, Typeface regular) {
        this.bold = bold;
        this.regular = regular;
        hand = new WiiHand(bold);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
    }

    void setScale(float scale) {
        s = scale;
        hand.setScale(scale);
    }

    void useSprites(MiiUiSprites u) {
        sprites = u;
        if (u != null) hand.useSprites(u.bitmap("hand"), u.bitmap("hand_blur"), u.bitmap("grab"), u.bitmap("grab_blur"));
    }

    /** rect in screen px from reference px. */
    RectF sr(RectF ref) {
        rect.set(ref.left * s, ref.top * s, ref.right * s, ref.bottom * s);
        return rect;
    }

    RectF sr(float l, float t, float r, float b) {
        rect.set(l * s, t * s, r * s, b * s);
        return rect;
    }

    // ---- text -----------------------------------------------------------------------------------------

    void label(Canvas c, CharSequence str, float x, float baseline, float size, int color, Paint.Align align,
               boolean heavy) {
        text.setTypeface(heavy ? bold : regular);
        text.setTextSize(size * s);
        text.setTextAlign(align);
        text.setColor(color);
        text.setShader(null);
        c.drawText(str, 0, str.length(), x * s, baseline * s, text);
    }

    /** White text with a dark rim, as on the original's grey buttons. */
    void rimmed(Canvas c, String str, float x, float baseline, float size, int fillColor, int rimColor) {
        text.setTypeface(bold);
        text.setTextSize(size * s);
        text.setTextAlign(Paint.Align.CENTER);
        text.setStyle(Paint.Style.STROKE);
        text.setStrokeWidth(size * 0.16f * s);
        text.setStrokeJoin(Paint.Join.ROUND);
        text.setColor(rimColor);
        c.drawText(str, x * s, baseline * s, text);
        text.setStyle(Paint.Style.FILL);
        text.setColor(fillColor);
        c.drawText(str, x * s, baseline * s, text);
    }

    StaticLayout layout(String str, float size, int color, float width, boolean heavy) {
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setTypeface(heavy ? bold : regular);
        tp.setTextSize(size * s);
        tp.setColor(color);
        return StaticLayout.Builder.obtain(str, 0, str.length(), tp, Math.max(1, Math.round(width * s)))
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.18f)
                .build();
    }

    // ---- shapes ----------------------------------------------------------------------------------------

    /** The blue glow the focused thing gets, around a rounded rect in screen px. */
    void glow(Canvas c, RectF r, float rad, float lift) {
        if (lift <= 0.01f) return;
        for (int k = 3; k >= 1; k--) {
            stroke.setColor(MiiArt.alpha(BLUE, (int) (lift * 72 / k)));
            stroke.setStrokeWidth((4f + k * 6f) * s);
            c.drawRoundRect(r, rad, rad, stroke);
        }
        stroke.setColor(MiiArt.alpha(BLUE, (int) (lift * 255)));
        stroke.setStrokeWidth(4f * s);
        c.drawRoundRect(r, rad, rad, stroke);
    }

    /** A vertical gradient from top at y0 to bottom at y1, in screen px; the same one as last time when it can. */
    LinearGradient gradient(float y0, float y1, int top, int bottom) {
        int h = ((top * 31 + bottom) * 31 + Float.floatToIntBits(y0)) * 31 + Float.floatToIntBits(y1);
        int i = (h ^ (h >>> 16)) & (GRADIENTS - 1);
        LinearGradient g = gradients[i];
        if (g == null || gradientColors[2 * i] != top || gradientColors[2 * i + 1] != bottom
                || gradientEnds[2 * i] != y0 || gradientEnds[2 * i + 1] != y1) {
            g = new LinearGradient(0, y0, 0, y1, top, bottom, Shader.TileMode.CLAMP);
            gradients[i] = g;
            gradientColors[2 * i] = top;
            gradientColors[2 * i + 1] = bottom;
            gradientEnds[2 * i] = y0;
            gradientEnds[2 * i + 1] = y1;
        }
        return g;
    }

    /** A rounded rect with a vertical gradient and an outline, all in screen px. */
    void box(Canvas c, RectF r, float rad, int top, int bottom, int outline, float outlineW) {
        MiiArt.opaque(fill).setShader(gradient(r.top, r.bottom, top, bottom));
        c.drawRoundRect(r, rad, rad, fill);
        fill.setShader(null);
        if (outlineW > 0f) {
            stroke.setColor(outline);
            stroke.setStrokeWidth(outlineW);
            c.drawRoundRect(r, rad, rad, stroke);
        }
    }

    /** A plaza button: round (or rounded-square) silver body in a slate ring, or a bare glyph. */
    void plazaButton(Canvas c, int icon, float cxRef, float cyRef, float rRef, float lift, boolean active) {
        if (sprites != null) {
            spriteButton(c, icon, cxRef * s, cyRef * s, lift, active);
            return;
        }
        float cx = cxRef * s, cy = cyRef * s;
        float sc = 1f + 0.08f * lift;
        float r = rRef * s * sc;
        int save = c.save();
        if (icon == ICON_HELP || icon == ICON_TRASH) {
            if (lift > 0.01f) {
                MiiArt.opaque(fill).setShader(new RadialGradient(cx, cy, r * 1.25f, MiiArt.alpha(BLUE, (int) (lift * 110)),
                        0x003FC0EC, Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, r * 1.25f, fill);
                fill.setShader(null);
            }
            c.scale(sc, sc, cx, cy);
            if (icon == ICON_HELP) helpGlyph(c, cx, cy, rRef * s);
            else trash(c, cx, cy, rRef * s);
            c.restoreToCount(save);
            return;
        }
        boolean square = icon == ICON_MENU;
        boolean dark = icon == ICON_PARADE || active;
        tmp.set(cx - r, cy - r, cx + r, cy + r);
        float rad = square ? r * 0.36f : r;
        fill.setColor(0x30000000);
        tmp.offset(4f * s, 6f * s);
        c.drawRoundRect(tmp, rad, rad, fill);
        tmp.offset(-4f * s, -6f * s);
        if (dark) {
            MiiArt.opaque(fill).setShader(new RadialGradient(cx - r * 0.3f, cy - r * 0.35f, r * 1.4f,
                    new int[] {0xFF95A6A6, 0xFF738585, 0xFF5D6E6E}, new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        } else {
            MiiArt.opaque(fill).setShader(new RadialGradient(cx - r * 0.3f, cy - r * 0.35f, r * 1.4f,
                    new int[] {0xFFFFFFFF, 0xFFE4EBEB, 0xFFC0CBCB}, new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        }
        c.drawRoundRect(tmp, rad, rad, fill);
        fill.setShader(null);
        stroke.setColor(SLATE);
        stroke.setStrokeWidth(r * 0.11f);
        tmp.inset(r * 0.055f, r * 0.055f);
        c.drawRoundRect(tmp, rad, rad, stroke);
        if (lift > 0.01f) {
            tmp.inset(-r * 0.08f, -r * 0.08f);
            glow(c, tmp, rad * 1.05f, lift);
        }
        icon(c, icon, cx, cy, r, dark);
        c.restoreToCount(save);
    }

    /**
     * A plaza button from the sprites, centred on (cx, cy) screen px: a light disc or square in a slate rim, the
     * dark ball of the parade (and of a button that is on), or a bare silver glyph.
     */
    private void spriteButton(Canvas c, int icon, float cx, float cy, float lift, boolean active) {
        MiiUiSprites u = sprites;
        float sc = 1f + 0.08f * lift;
        float k = MiiUiSprites.K * s * sc;
        if (icon == ICON_MENU || icon == ICON_HELP || icon == ICON_SORT) {
            bareGlow(c, cx, cy, 40f * k, lift);
            if (icon == ICON_MENU) u.draw(c, "arrow_back", cx, cy, k, MiiUiSprites.MUL, MiiUiSprites.SILVER);
            else if (icon == ICON_HELP) u.draw(c, "help", cx, cy, k, MiiUiSprites.MUL, MiiUiSprites.SILVER);
            else u.draw(c, "whistle", cx, cy, k, MiiUiSprites.MUL, MiiUiSprites.WHISTLE);
            return;
        }
        boolean square = icon == ICON_REMOTE || icon == ICON_MAIL;
        boolean dark = icon == ICON_PARADE || active;
        if (dark) {
            u.draw(c, "ball", cx, cy, k * 85f / 74f, MiiUiSprites.MUL, MiiUiSprites.DARK);
        } else {
            u.draw(c, square ? "sq_fill" : "disc", cx, cy, k, MiiUiSprites.MUL, MiiUiSprites.FILL);
            u.draw(c, square ? "sq_ring" : "ring", cx, cy, k, MiiUiSprites.FLAT, MiiUiSprites.RIM);
        }
        if (lift > 0.01f) {
            float h = 41.5f * k;
            tmp.set(cx - h, cy - h, cx + h, cy + h);
            glow(c, tmp, square ? h * 0.3f : h, lift);
        }
        int ink = dark ? 0xFFFFFF : MiiUiSprites.INK;
        switch (icon) {
            case ICON_EDIT:
            case ICON_NEW: {
                float fx = cx - 3f * k, fy = cy + 4f * k;
                u.draw(c, "face", fx, fy, k, MiiUiSprites.FLAT, dark ? MiiUiSprites.FACE_LIGHT : MiiUiSprites.FACE);
                u.draw(c, "face_feat", fx, fy + 3f * k, k, MiiUiSprites.FLAT, dark ? 0x5D6E6E : MiiUiSprites.FACE_LIGHT);
                float bx = cx + 21f * k, by = cy - 19f * k;
                u.draw(c, "badge", bx, by, k, k, MiiUiSprites.DUO, MiiUiSprites.BADGE, MiiUiSprites.BADGE_INK, 255);
                u.draw(c, icon == ICON_EDIT ? "eye" : "plus", bx + 1.5f * k, by - 1.5f * k, k, MiiUiSprites.FLAT,
                        MiiUiSprites.BADGE_INK);
                break;
            }
            case ICON_TRASH:
                u.draw(c, "dash_ring", cx, cy, k, MiiUiSprites.FLAT, ink);
                break;
            case ICON_PARADE:
                u.draw(c, "parade", cx, cy, k, MiiUiSprites.NONE, 0);
                break;
            case ICON_REMOTE:
                u.draw(c, "remote", cx, cy, k, MiiUiSprites.FLAT, ink);
                break;
            case ICON_MAIL:
                u.draw(c, "mail", cx, cy, k, MiiUiSprites.FLAT, ink);
                break;
            default:
                icon(c, icon, cx, cy, 41.5f * k, dark);
                break;
        }
    }

    private void bareGlow(Canvas c, float cx, float cy, float r, float lift) {
        if (lift <= 0.01f) return;
        MiiArt.opaque(fill).setShader(new RadialGradient(cx, cy, r * 1.25f, MiiArt.alpha(BLUE, (int) (lift * 110)),
                0x003FC0EC, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r * 1.25f, fill);
        fill.setShader(null);
    }

    /** One of the channel's icons centred on (cx, cy), r the button radius, screen px. */
    void icon(Canvas c, int icon, float cx, float cy, float r, boolean onDark) {
        int ink = onDark ? 0xFFFFFFFF : SLATE_ICON;
        int soft = onDark ? 0xFF5D6E6E : 0xFFD6E0E0;
        switch (icon) {
            case ICON_EDIT:
            case ICON_NEW: {
                float fr = r * 0.5f, fx = cx - r * 0.08f, fy = cy + r * 0.06f;
                fill.setColor(ink);
                c.drawCircle(fx, fy, fr, fill);
                fill.setColor(soft);
                c.drawCircle(fx - fr * 0.36f, fy - fr * 0.15f, fr * 0.11f, fill);
                c.drawCircle(fx + fr * 0.36f, fy - fr * 0.15f, fr * 0.11f, fill);
                stroke.setColor(soft);
                stroke.setStrokeWidth(fr * 0.13f);
                tmp.set(fx - fr * 0.42f, fy - fr * 0.25f, fx + fr * 0.42f, fy + fr * 0.5f);
                c.drawArc(tmp, 30f, 120f, false, stroke);
                float bx = cx + r * 0.42f, by = cy - r * 0.42f, br = r * 0.3f;
                fill.setColor(0xFFFFFFFF);
                c.drawCircle(bx, by, br, fill);
                stroke.setColor(ink);
                stroke.setStrokeWidth(r * 0.06f);
                c.drawCircle(bx, by, br, stroke);
                if (icon == ICON_NEW) {
                    stroke.setStrokeWidth(br * 0.3f);
                    c.drawLine(bx - br * 0.5f, by, bx + br * 0.5f, by, stroke);
                    c.drawLine(bx, by - br * 0.5f, bx, by + br * 0.5f, stroke);
                } else {
                    int sv = c.save();
                    c.rotate(45f, bx, by);
                    fill.setColor(ink);
                    tmp.set(bx - br * 0.18f, by - br * 0.6f, bx + br * 0.18f, by + br * 0.35f);
                    c.drawRect(tmp, fill);
                    path.reset();
                    path.moveTo(bx - br * 0.18f, by + br * 0.38f);
                    path.lineTo(bx + br * 0.18f, by + br * 0.38f);
                    path.lineTo(bx, by + br * 0.7f);
                    path.close();
                    c.drawPath(path, fill);
                    c.restoreToCount(sv);
                }
                break;
            }
            case ICON_SORT: {
                // A referee's whistle on its cord.
                fill.setColor(ink);
                float wx = cx + r * 0.05f, wy = cy + r * 0.1f;
                c.drawCircle(wx - r * 0.12f, wy, r * 0.34f, fill);
                tmp.set(wx - r * 0.12f, wy - r * 0.34f, wx + r * 0.52f, wy + r * 0.02f);
                c.drawRoundRect(tmp, r * 0.08f, r * 0.08f, fill);
                fill.setColor(onDark ? 0xFF5D6E6E : 0xFFFFFFFF);
                c.drawCircle(wx - r * 0.12f, wy, r * 0.13f, fill);
                stroke.setColor(ink);
                stroke.setStrokeWidth(r * 0.07f);
                tmp.set(cx - r * 0.62f, cy - r * 0.62f, cx + r * 0.05f, cy + r * 0.02f);
                c.drawArc(tmp, 120f, 210f, false, stroke);
                break;
            }
            case ICON_PARADE: {
                for (int k = -1; k <= 1; k++) figure(c, cx + k * r * 0.42f, cy + r * 0.05f, r * 0.62f, ink, k == -1);
                stroke.setColor(ink);
                stroke.setStrokeWidth(r * 0.07f);
                c.drawLine(cx - r * 0.3f, cy + r * 0.02f, cx + r * 0.3f, cy + r * 0.02f, stroke);
                break;
            }
            case ICON_MENU: {
                fill.setColor(ink);
                float g = r * 0.07f, cw = r * 0.4f, chh = r * 0.27f;
                for (int i = 0; i < 2; i++) {
                    for (int j = 0; j < 2; j++) {
                        float l = cx - cw - g / 2f + i * (cw + g), t = cy - chh - g / 2f + j * (chh + g);
                        tmp.set(l, t, l + cw, t + chh);
                        c.drawRoundRect(tmp, chh * 0.3f, chh * 0.3f, fill);
                    }
                }
                break;
            }
            case ICON_ABC:
                text.setTypeface(bold);
                text.setTextAlign(Paint.Align.CENTER);
                text.setTextSize(r * 0.62f);
                text.setColor(ink);
                c.drawText("ABC", cx, cy + r * 0.22f, text);
                break;
            case ICON_FAV: {
                fill.setColor(0xFFFFD43A);
                path.reset();
                float w = r * 0.62f, h = r * 0.45f, by = cy + r * 0.28f;
                path.moveTo(cx - w, by);
                path.lineTo(cx - w, cy - h * 0.6f);
                path.lineTo(cx - w * 0.45f, cy);
                path.lineTo(cx, cy - h);
                path.lineTo(cx + w * 0.45f, cy);
                path.lineTo(cx + w, cy - h * 0.6f);
                path.lineTo(cx + w, by);
                path.close();
                c.drawPath(path, fill);
                stroke.setColor(0xFFB08A10);
                stroke.setStrokeWidth(r * 0.06f);
                c.drawPath(path, stroke);
                break;
            }
            case ICON_COLOR: {
                int[] cols = {0xFFD21E14, 0xFFFF6E19, 0xFFFFD820, 0xFF78D220, 0xFF3CAADE, 0xFF7328AD};
                for (int k = 0; k < cols.length; k++) {
                    double a = -Math.PI / 2 + k * Math.PI * 2 / cols.length;
                    fill.setColor(cols[k]);
                    c.drawCircle(cx + (float) Math.cos(a) * r * 0.36f, cy + (float) Math.sin(a) * r * 0.36f,
                            r * 0.17f, fill);
                }
                break;
            }
            case ICON_GENDER:
                figure(c, cx - r * 0.3f, cy + r * 0.05f, r * 0.7f, 0xFF7CC4FF, false);
                figure(c, cx + r * 0.3f, cy + r * 0.05f, r * 0.7f, 0xFFFF9AC0, true);
                break;
            case ICON_BOY:
            case ICON_GIRL:
                figure(c, cx, cy, r, icon == ICON_BOY ? 0xFF4C9BE8 : 0xFFF06C9B, icon == ICON_GIRL);
                break;
            case ICON_REMOTE: {
                int sv = c.save();
                c.rotate(-45f, cx, cy);
                fill.setColor(ink);
                tmp.set(cx - r * 0.2f, cy - r * 0.62f, cx + r * 0.2f, cy + r * 0.62f);
                c.drawRoundRect(tmp, r * 0.1f, r * 0.1f, fill);
                fill.setColor(soft);
                c.drawCircle(cx, cy - r * 0.36f, r * 0.08f, fill);
                c.drawCircle(cx, cy - r * 0.05f, r * 0.06f, fill);
                c.drawCircle(cx, cy + r * 0.3f, r * 0.05f, fill);
                c.drawCircle(cx, cy + r * 0.44f, r * 0.05f, fill);
                c.restoreToCount(sv);
                break;
            }
            case ICON_MAIL: {
                fill.setColor(ink);
                tmp.set(cx - r * 0.6f, cy - r * 0.38f, cx + r * 0.6f, cy + r * 0.38f);
                c.drawRoundRect(tmp, r * 0.08f, r * 0.08f, fill);
                stroke.setColor(soft);
                stroke.setStrokeWidth(r * 0.07f);
                c.drawLine(cx - r * 0.56f, cy - r * 0.32f, cx, cy + r * 0.08f, stroke);
                c.drawLine(cx + r * 0.56f, cy - r * 0.32f, cx, cy + r * 0.08f, stroke);
                break;
            }
            default:
                break;
        }
    }

    /** A little standing person, h tall around (cx, cy); a skirt for girls. */
    void figure(Canvas c, float cx, float cy, float h, int color, boolean skirt) {
        fill.setColor(color);
        c.drawCircle(cx, cy - h * 0.3f, h * 0.17f, fill);
        path.reset();
        float tw = h * 0.16f, bw = skirt ? h * 0.27f : h * 0.17f, ty = cy - h * 0.1f, by = cy + h * 0.2f;
        path.moveTo(cx - tw, ty);
        path.lineTo(cx + tw, ty);
        path.lineTo(cx + bw, by);
        path.lineTo(cx - bw, by);
        path.close();
        c.drawPath(path, fill);
        tmp.set(cx - h * 0.13f, by - h * 0.02f, cx - h * 0.03f, cy + h * 0.45f);
        c.drawRoundRect(tmp, h * 0.05f, h * 0.05f, fill);
        tmp.set(cx + h * 0.03f, by - h * 0.02f, cx + h * 0.13f, cy + h * 0.45f);
        c.drawRoundRect(tmp, h * 0.05f, h * 0.05f, fill);
        stroke.setColor(color);
        stroke.setStrokeWidth(h * 0.08f);
        c.drawLine(cx - tw, ty + h * 0.04f, cx - h * 0.3f, cy + h * 0.12f, stroke);
        c.drawLine(cx + tw, ty + h * 0.04f, cx + h * 0.3f, cy + h * 0.12f, stroke);
    }

    private void helpGlyph(Canvas c, float cx, float cy, float r) {
        text.setTypeface(bold);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(r * 2.1f);
        float base = cy + r * 0.75f;
        text.setStyle(Paint.Style.FILL);
        text.setColor(0x30000000);
        c.drawText("?", cx + r * 0.06f, base + r * 0.08f, text);
        text.setStyle(Paint.Style.STROKE);
        text.setStrokeWidth(r * 0.16f);
        text.setStrokeJoin(Paint.Join.ROUND);
        text.setColor(0xFFFFFFFF);
        c.drawText("?", cx, base, text);
        text.setStyle(Paint.Style.FILL);
        MiiArt.opaque(text).setShader(gradient(cy - r, cy + r, 0xFF95A6A6, 0xFF5D6E6E));
        c.drawText("?", cx, base, text);
        text.setShader(null);
    }

    private void trash(Canvas c, float cx, float cy, float r) {
        float w = r * 0.62f, top = cy - r * 0.42f, bottom = cy + r * 0.78f;
        fill.setColor(0x30000000);
        tmp.set(cx - w * 1.05f, bottom - r * 0.1f, cx + w * 1.05f, bottom + r * 0.14f);
        c.drawOval(tmp, fill);
        path.reset();
        path.moveTo(cx - w, top);
        path.lineTo(cx + w, top);
        path.lineTo(cx + w * 0.8f, bottom);
        path.quadTo(cx, bottom + r * 0.08f, cx - w * 0.8f, bottom);
        path.close();
        MiiArt.opaque(fill).setShader(new LinearGradient(cx - w, 0, cx + w, 0, new int[] {0xFF5D6E6E, 0xFF8FA0A0, 0xFF6C7D7D},
                null, Shader.TileMode.CLAMP));
        c.drawPath(path, fill);
        fill.setShader(null);
        stroke.setColor(0xFF4A5858);
        stroke.setStrokeWidth(r * 0.05f);
        c.drawPath(path, stroke);
        stroke.setColor(0xFFB9C6C6);
        stroke.setStrokeWidth(r * 0.06f);
        for (int k = -1; k <= 1; k++) {
            c.drawLine(cx + k * w * 0.42f, top + r * 0.18f, cx + k * w * 0.36f, bottom - r * 0.14f, stroke);
        }
        tmp.set(cx - w * 1.12f, top - r * 0.2f, cx + w * 1.12f, top + r * 0.04f);
        box(c, tmp, r * 0.1f, 0xFF9AABAB, 0xFF6C7D7D, 0xFF4A5858, r * 0.05f);
        tmp.set(cx - w * 0.3f, top - r * 0.36f, cx + w * 0.3f, top - r * 0.16f);
        stroke.setColor(0xFF4A5858);
        stroke.setStrokeWidth(r * 0.07f);
        c.drawRoundRect(tmp, r * 0.08f, r * 0.08f, stroke);
    }

    /** The dark slate pills of the sort bar. */
    void darkPill(Canvas c, RectF refRect, int icon, float lift) {
        if (sprites != null) {
            spritePill(c, refRect, icon, lift);
            return;
        }
        float sc = 1f + 0.06f * lift;
        RectF r = sr(refRect);
        int save = c.save();
        c.scale(sc, sc, r.centerX(), r.centerY());
        float rad = r.height() / 2f;
        RectF rr = pillBody;
        rr.set(r);
        box(c, rr, rad, 0xFF8A9C9C, 0xFF566767, 0xFFE8F0F0, 4f * s);
        glow(c, rr, rad, lift);
        icon(c, icon, rr.centerX(), rr.centerY(), rr.height() * 0.5f, true);
        c.restoreToCount(save);
    }

    /**
     * The sort bar's head count, centred on (cxRef, cyRef) reference px: a speech bubble with a face and a cross,
     * and the number in the channel's bold digits under them. Drawn only from the sprites.
     */
    void countBubble(Canvas c, float cxRef, float cyRef, int count) {
        MiiUiSprites u = sprites;
        if (u == null) return;
        float k = MiiUiSprites.K * s, cx = cxRef * s, cy = cyRef * s;
        String n = Integer.toString(count);
        float w = DIGIT_OVERLAP * k;
        for (int i = 0; i < n.length(); i++) {
            Bitmap b = u.bitmap("n" + n.charAt(i));
            if (b == null) return;
            w += (b.getWidth() - DIGIT_OVERLAP) * k;
        }
        if (!u.draw(c, "count", cx, cy, k, k, MiiUiSprites.DUO, MiiUiSprites.FILL, MiiUiSprites.INK, 255)) return;
        // The round part of the bubble sits 7 texels left of the sprite's middle (the tail takes the rest).
        float x = cx - 7f * k - w / 2f, y = cy + 13.5f * k;
        for (int i = 0; i < n.length(); i++) {
            String name = "n" + n.charAt(i);
            float bw = u.bitmap(name).getWidth() * k;
            // The glyphs are a white stroke in a black outline: the stroke takes the ink, the outline the bubble.
            u.draw(c, name, x + bw / 2f, y, k, k, MiiUiSprites.DUO, MiiUiSprites.RIM, MiiUiSprites.FILL, 255);
            x += bw - DIGIT_OVERLAP * k;
        }
    }

    /** Texels of their outlines (and padding) by which neighbouring digits overlap. */
    private static final float DIGIT_OVERLAP = 4f;

    /** A sort pill from the sprites: the dark ball with its tail, stretched, and a white glyph; or the whistle. */
    private void spritePill(Canvas c, RectF refRect, int icon, float lift) {
        MiiUiSprites u = sprites;
        float sc = 1f + 0.06f * lift;
        float cx = refRect.centerX() * s, cy = refRect.centerY() * s;
        float k = MiiUiSprites.K * s * sc;
        if (icon == ICON_SORT) {
            bareGlow(c, cx, cy, 40f * k, lift);
            u.draw(c, "whistle", cx, cy, k, MiiUiSprites.MUL, MiiUiSprites.WHISTLE);
            return;
        }
        Bitmap ball = u.bitmap("ball_tip");
        if (ball == null) return;
        float hw = refRect.width() * s * sc / 2f, hh = refRect.height() * s * sc / 2f;
        tmp.set(cx - hw, cy - hh, cx + hw, cy + hh);
        u.drawInto(c, ball, tmp, MiiUiSprites.MUL, MiiUiSprites.DARK, 0, 255);
        if (lift > 0.01f) {
            tmp.inset(hw * 0.06f, hh * 0.1f);
            glow(c, tmp, tmp.height() / 2f, lift);
        }
        String name = icon == ICON_ABC ? "talk" : icon == ICON_FAV ? "crown" : icon == ICON_COLOR ? "palette" : "boygirl";
        u.draw(c, name, cx + 4f * k, cy, k, MiiUiSprites.NONE, 0);
    }

    // ---- panels ---------------------------------------------------------------------------------------------

    /**
     * Darkens the screen for something opening over it and slides what follows up into place; returns the
     * save count to restore. A layer only while fading, as a full-screen layer costs a frame on the TV.
     */
    int dim(Canvas c, float appear) {
        int save = appear < 0.995f ? c.saveLayerAlpha(0, 0, c.getWidth(), c.getHeight(), Math.round(255 * appear))
                : c.save();
        fill.setShader(null);
        fill.setColor(0x88000000);
        c.drawRect(0, 0, c.getWidth(), c.getHeight(), fill);
        c.translate(0f, (1f - appear) * 40f * s);
        return save;
    }

    /** The dark panel of the dialogs with its title bar; r in screen px. */
    void darkPanel(Canvas c, RectF r, String title) {
        float rad = 38f * s;
        RectF pr = panelBody;
        pr.set(r);
        fill.setColor(0x50000000);
        pr.offset(0, 10f * s);
        c.drawRoundRect(pr, rad, rad, fill);
        pr.offset(0, -10f * s);
        box(c, pr, rad, 0xFF3A4749, 0xFF222C2E, 0xFFDCE4E4, 7f * s);
        stroke.setColor(0xFF131A1B);
        stroke.setStrokeWidth(2.5f * s);
        RectF in = panelRim;
        in.set(pr);
        in.inset(6f * s, 6f * s);
        c.drawRoundRect(in, rad * 0.85f, rad * 0.85f, stroke);
        RectF bar = panelBar;
        bar.set(pr.left + 26f * s, pr.top + 24f * s, pr.right - 26f * s, pr.top + 118f * s);
        box(c, bar, 26f * s, 0xFF526163, 0xFF3F4C4E, 0xFF2A3537, 3f * s);
        label(c, title, pr.centerX() / s, (bar.centerY() / s) + 16f, 46f, 0xFF6FE3D2, Paint.Align.CENTER, true);
    }

    /** The dialogs' white pill button; r in screen px. */
    void pill(Canvas c, RectF r, String str, float lift, float size) {
        float sc = 1f + 0.05f * lift;
        int sv = c.save();
        c.scale(sc, sc, r.centerX(), r.centerY());
        float br = Math.min(r.height(), r.width()) / 2f;
        box(c, r, br, 0xFFF6FBFB, 0xFFB9C7C7, 0xFF2A3436, 5f * s);
        glow(c, r, br, lift);
        label(c, str, r.centerX() / s, r.centerY() / s + size * 0.36f, size, 0xFF3A4446, Paint.Align.CENTER, true);
        c.restoreToCount(sv);
    }

    // ---- dialogs -------------------------------------------------------------------------------------------

    interface Choice {
        void chose(int index);
    }

    /** The channel's dark message box: a title bar, a message and one to three buttons. */
    final class Dialog implements Overlay {
        final String title;
        final String body;
        final String[] buttons;
        final int[] icons;
        final int cancel;
        final Choice choice;
        int focus;
        private final float[] lift;
        private StaticLayout layout;
        private float appear;
        private final RectF panel = new RectF();
        private final RectF[] btn;
        /** The panel and a button in screen px, filled in again on every frame. */
        private final RectF panelPx = new RectF(), buttonPx = new RectF();

        Dialog(String title, String body, String[] buttons, int[] icons, int cancel, Choice choice) {
            this.title = title;
            this.body = body;
            this.buttons = buttons;
            this.icons = icons;
            this.cancel = cancel;
            this.choice = choice;
            lift = new float[buttons.length];
            btn = new RectF[buttons.length];
            for (int i = 0; i < btn.length; i++) btn[i] = new RectF();
        }

        private void layoutIfNeeded() {
            if (layout != null) return;
            boolean big = icons != null;
            float pw = big ? 1240f : 1200f;
            layout = body == null || body.isEmpty() ? null : MiiUi.this.layout(body, 44f, 0xFFE9EFEF, pw - 160f, true);
            float bodyH = layout == null ? 0f : layout.getHeight() / s;
            int n = buttons.length;
            text.setTypeface(bold);
            text.setTextSize(44f * s);
            float widest = 0f;
            for (String b : buttons) widest = Math.max(widest, text.measureText(b) / s);
            boolean vertical = !big && (n > 2 || widest > 380f);
            float btnArea = big ? 380f : vertical ? n * 104f + (n - 1) * 20f : 104f;
            float ph = 150f + (bodyH > 0 ? bodyH + 60f : 10f) + btnArea + 70f;
            float pl = (1920f - pw) / 2f, pt = (1080f - ph) / 2f;
            panel.set(pl, pt, pl + pw, pt + ph);
            float by = pt + 150f + (bodyH > 0 ? bodyH + 60f : 10f);
            if (big) {
                float bw = 330f, gap = 90f, total = n * bw + (n - 1) * gap, bx = (1920f - total) / 2f;
                for (int i = 0; i < n; i++) btn[i].set(bx + i * (bw + gap), by, bx + i * (bw + gap) + bw, by + 340f);
            } else if (vertical) {
                for (int i = 0; i < n; i++) {
                    float t = by + i * 124f;
                    btn[i].set(960f - 330f, t, 960f + 330f, t + 104f);
                }
            } else {
                float bw = n == 1 ? 480f : 440f, gap = 70f, total = n * bw + (n - 1) * gap, bx = (1920f - total) / 2f;
                for (int i = 0; i < n; i++) btn[i].set(bx + i * (bw + gap), by, bx + i * (bw + gap) + bw, by + 104f);
            }
        }

        @Override
        public void step(float dt) {
            float k = 1f - (float) Math.exp(-dt / 0.07f);
            appear = approach(appear, 1f, 1f - (float) Math.exp(-dt / 0.09f), 0.003f);
            for (int i = 0; i < lift.length; i++) lift[i] = approach(lift[i], i == focus ? 1f : 0f, k, 0.003f);
        }

        @Override
        public void draw(Canvas c) {
            layoutIfNeeded();
            int save = dim(c, appear);
            RectF pr = panelPx;
            pr.set(sr(panel));
            darkPanel(c, pr, title);
            if (layout != null) {
                int sv = c.save();
                c.translate(pr.centerX() - layout.getWidth() / 2f, pr.top + 160f * s);
                layout.draw(c);
                c.restoreToCount(sv);
            }
            for (int i = 0; i < buttons.length; i++) {
                RectF b = buttonPx;
                b.set(sr(btn[i]));
                float lf = lift[i], sc = 1f + 0.05f * lf;
                int sv = c.save();
                c.scale(sc, sc, b.centerX(), b.centerY());
                if (icons != null) {
                    box(c, b, 40f * s, 0xFFF6FBFB, 0xFFBCCACA, 0xFF2A3436, 5f * s);
                    glow(c, b, 40f * s, lf);
                    icon(c, icons[i], b.centerX(), b.top + 140f * s, 120f * s, false);
                    label(c, buttons[i], b.centerX() / s, b.bottom / s - 40f, 48f, 0xFF3A4446, Paint.Align.CENTER, true);
                } else {
                    pill(c, b, buttons[i], lf, 44f);
                }
                c.restoreToCount(sv);
            }
            c.restoreToCount(save);
        }

        @Override
        public void key(int keyCode) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (cancel >= 0) choice.chose(cancel);
                return;
            }
            int d = dx(keyCode) + dy(keyCode);
            if (d != 0) {
                int nf = Math.max(0, Math.min(buttons.length - 1, focus + d));
                if (nf != focus) {
                    focus = nf;
                    moved = true;
                }
                return;
            }
            if (isOk(keyCode)) choice.chose(focus);
        }

        /** Set when a key moved the focus, for the host's move sound. */
        boolean moved;

        @Override
        public void handTarget(float[] out) {
            layoutIfNeeded();
            RectF b = btn[Math.max(0, Math.min(btn.length - 1, focus))];
            out[0] = b.left + b.width() * 0.72f;
            out[1] = b.top + b.height() * 0.7f;
        }
    }
}
