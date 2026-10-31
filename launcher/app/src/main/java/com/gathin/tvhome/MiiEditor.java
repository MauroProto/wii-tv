package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * The Mii maker: nine tabs along the top, the character on the left over a mint backdrop, and on the right
 * a striped panel with the options of the open tab: a two-by-four grid of parts with page arrows, colour
 * swatches, and buttons that nudge, resize, rotate or space the part. Moving along the tabs opens them.
 */
final class MiiEditor {
    static final int T_INFO = 0, T_BODY = 1, T_FACE = 2, T_HAIR = 3, T_BROW = 4, T_EYE = 5, T_NOSE = 6,
            T_MOUTH = 7, T_EXTRA = 8;
    private static final int K_TAB = 1, K_SUB = 2, K_TILE = 3, K_PAGE = 4, K_SWATCH = 5, K_ADJ = 6, K_FLIP = 7,
            K_QUIT = 8, K_FIELD = 9, K_GENDER = 10, K_FAV = 11, K_STEP = 12, K_FAVCOLOR = 13, K_SLIDER = 14;

    private static final float TAB_X0 = 30f, TAB_PITCH = 206f, TAB_W = 196f, TAB_Y = 12f, TAB_H = 138f;
    private static final RectF PANEL = new RectF(656f, 192f, 1904f, 1068f);
    private static final RectF QUIT = new RectF(92f, 902f, 534f, 1006f);
    private static final float TILE_W = 232f, TILE_H = 158f, COL_GAP = 22f, ROW_GAP = 18f;
    private static final float GRID_CX = 1050f;
    private static final float SIDE_L = 1440f, SIDE_R = 1880f;

    private final MiiChannelView host;
    private final MiiUi ui;
    private final MiiArt art;
    final Mii mii;
    final boolean isNew;
    private int tab = T_FACE;
    private int sub;
    private int page;
    private final List<MiiUi.Item> els = new ArrayList<>();
    private int focus;
    private float zoom;
    private float blinkT = 2.5f, blinkLeft;
    private float pop;
    private float time;
    private final MiiArt.Pose pose = new MiiArt.Pose();
    private final RectF r = new RectF();
    private final Path path = new Path();

    MiiEditor(MiiChannelView host, MiiUi ui, MiiArt art, Mii mii, boolean isNew) {
        this.host = host;
        this.ui = ui;
        this.art = art;
        this.mii = mii;
        this.isNew = isNew;
        page = pageOf(gridProp());
        build(null);
        focus = indexOf(K_TAB, tab, 0);
    }

    // ---- what each tab holds ----------------------------------------------------------------------------

    private int subCount() {
        return tab == T_FACE ? 2 : tab == T_EXTRA ? 4 : 0;
    }

    private int gridProp() {
        switch (tab) {
            case T_FACE:
                return sub == 0 ? Mii.FACE : Mii.FEATURE;
            case T_HAIR:
                return Mii.HAIR;
            case T_BROW:
                return Mii.BROW;
            case T_EYE:
                return Mii.EYE;
            case T_NOSE:
                return Mii.NOSE;
            case T_MOUTH:
                return Mii.MOUTH;
            case T_EXTRA:
                return sub == 0 ? Mii.GLASSES : sub == 1 ? Mii.MUSTACHE : sub == 2 ? Mii.BEARD : Mii.MOLE;
            default:
                return -1;
        }
    }

    private int colorProp() {
        switch (tab) {
            case T_FACE:
                return sub == 0 ? Mii.SKIN : -1;
            case T_HAIR:
                return Mii.HAIR_COLOR;
            case T_BROW:
                return Mii.BROW_COLOR;
            case T_EYE:
                return Mii.EYE_COLOR;
            case T_MOUTH:
                return Mii.MOUTH_COLOR;
            case T_EXTRA:
                return sub == 0 ? Mii.GLASSES_COLOR : sub <= 2 ? Mii.BEARD_COLOR : -1;
            default:
                return -1;
        }
    }

    private int[] adjProps() {
        switch (tab) {
            case T_BROW:
                return new int[] {Mii.BROW_Y, Mii.BROW_SPACE, Mii.BROW_SIZE, Mii.BROW_ROT};
            case T_EYE:
                return new int[] {Mii.EYE_Y, Mii.EYE_SPACE, Mii.EYE_SIZE, Mii.EYE_ROT};
            case T_NOSE:
                return new int[] {Mii.NOSE_Y, Mii.NOSE_SIZE};
            case T_MOUTH:
                return new int[] {Mii.MOUTH_Y, Mii.MOUTH_SIZE};
            case T_EXTRA:
                if (sub == 0) return new int[] {Mii.GLASSES_Y, Mii.GLASSES_SIZE};
                if (sub == 1) return new int[] {Mii.MUSTACHE_Y, Mii.MUSTACHE_SIZE};
                if (sub == 3) return new int[] {Mii.MOLE_Y, Mii.MOLE_X, Mii.MOLE_SIZE};
                return new int[0];
            default:
                return new int[0];
        }
    }

    private static int[] palette(int prop) {
        switch (prop) {
            case Mii.SKIN:
                return Mii.SKIN_COLORS;
            case Mii.EYE_COLOR:
                return Mii.EYE_COLORS;
            case Mii.MOUTH_COLOR:
                return Mii.LIP_COLORS;
            case Mii.GLASSES_COLOR:
                return Mii.GLASSES_COLORS;
            default:
                return Mii.HAIR_COLORS;
        }
    }

    private int pageOf(int prop) {
        return prop < 0 ? 0 : mii.v[prop] / 8;
    }

    private int pages() {
        int prop = gridProp();
        return prop < 0 ? 1 : (Mii.MAX[prop] + 8) / 8;
    }

    // ---- layout ------------------------------------------------------------------------------------------

    private MiiUi.Item add(int kind, int a, int b, float l, float t, float rr, float bb) {
        MiiUi.Item it = new MiiUi.Item().set(kind, a, b, l, t, rr, bb);
        els.add(it);
        return it;
    }

    private float innerTop() {
        return subCount() > 0 ? 318f : 214f;
    }

    private void build(MiiUi.Item keep) {
        els.clear();
        for (int t = 0; t < 9; t++) add(K_TAB, t, 0, TAB_X0 + t * TAB_PITCH, TAB_Y, TAB_X0 + t * TAB_PITCH + TAB_W, TAB_Y + TAB_H);
        add(K_QUIT, 0, 0, QUIT.left, QUIT.top, QUIT.right, QUIT.bottom);
        if (tab == T_INFO) {
            add(K_FIELD, 0, 0, 1010f, 236f, 1840f, 336f);
            add(K_FIELD, 1, 0, 1010f, 358f, 1840f, 458f);
            add(K_GENDER, 0, 0, 1010f, 480f, 1290f, 580f);
            add(K_GENDER, 1, 0, 1310f, 480f, 1590f, 580f);
            add(K_FAV, 0, 0, 1630f, 480f, 1840f, 580f);
            add(K_STEP, Mii.DAY, -1, 1010f, 602f, 1096f, 702f);
            add(K_STEP, Mii.DAY, 1, 1272f, 602f, 1358f, 702f);
            add(K_STEP, Mii.MONTH, -1, 1440f, 602f, 1526f, 702f);
            add(K_STEP, Mii.MONTH, 1, 1702f, 602f, 1788f, 702f);
            for (int i = 0; i < 12; i++) {
                float x = 1010f + (i % 6) * 140f, y = 744f + (i / 6) * 132f;
                add(K_FAVCOLOR, i, 0, x, y, x + 122f, y + 112f);
            }
        } else if (tab == T_BODY) {
            add(K_SLIDER, Mii.HEIGHT, 0, 870f, 360f, 1790f, 500f);
            add(K_SLIDER, Mii.WEIGHT, 0, 870f, 720f, 1790f, 860f);
        } else {
            int n = subCount();
            for (int i = 0; i < n; i++) {
                float w = (716f - (n - 1) * 16f) / n, l = 692f + i * (w + 16f);
                add(K_SUB, i, 0, l, 214f, l + w, 300f);
            }
            int prop = gridProp();
            int count = Mii.MAX[prop] + 1;
            int pg = pages();
            page = Math.max(0, Math.min(pg - 1, page));
            float gw = 2 * TILE_W + COL_GAP, gh = 4 * TILE_H + 3 * ROW_GAP;
            float top = innerTop(), gx = GRID_CX - gw / 2f, gy = top + (1046f - top - gh) / 2f;
            for (int k = 0; k < 8; k++) {
                int idx = page * 8 + k;
                if (idx >= count) break;
                float l = gx + (k % 2) * (TILE_W + COL_GAP), t = gy + (k / 2) * (TILE_H + ROW_GAP);
                add(K_TILE, idx, prop, l, t, l + TILE_W, t + TILE_H);
            }
            if (pg > 1) {
                float my = gy + gh / 2f;
                add(K_PAGE, -1, 0, 702f, my - 64f, 782f, my + 64f);
                add(K_PAGE, 1, 0, 1318f, my - 64f, 1398f, my + 64f);
            }
            int cp = colorProp();
            if (cp >= 0) {
                int nc = palette(cp).length;
                int cols = nc == 8 ? 4 : 3;
                float sw = cols == 4 ? 88f : 116f, sh = 76f, gap = cols == 4 ? 14f : 22f;
                float total = cols * sw + (cols - 1) * gap, x0 = (SIDE_L + SIDE_R) / 2f - total / 2f;
                for (int i = 0; i < nc; i++) {
                    float l = x0 + (i % cols) * (sw + gap), t = 236f + (i / cols) * (sh + 24f);
                    add(K_SWATCH, i, cp, l, t, l + sw, t + sh);
                }
            }
            int[] adj = adjProps();
            float y0 = cp >= 0 ? 462f : 236f;
            for (int j = 0; j < adj.length; j++) {
                float t = y0 + j * 140f;
                add(K_ADJ, adj[j], -1, 1452f, t, 1652f, t + 122f);
                add(K_ADJ, adj[j], 1, 1668f, t, 1868f, t + 122f);
            }
            if (tab == T_HAIR) add(K_FLIP, 0, 0, 1452f, 462f, 1868f, 584f);
        }
        if (keep != null) {
            for (int i = 0; i < els.size(); i++) {
                if (els.get(i).same(keep)) {
                    focus = i;
                    return;
                }
            }
        }
        focus = Math.min(focus, els.size() - 1);
    }

    private int indexOf(int kind, int a, int b) {
        for (int i = 0; i < els.size(); i++) {
            MiiUi.Item it = els.get(i);
            if (it.kind == kind && it.a == a && (kind != K_ADJ && kind != K_STEP && kind != K_PAGE || it.b == b)) return i;
        }
        return -1;
    }

    private void setTab(int t) {
        if (t == tab) return;
        tab = t;
        sub = 0;
        page = pageOf(gridProp());
        build(null);
        focus = indexOf(K_TAB, t, 0);
        host.sound(WiiSounds.PAGE);
    }

    /** Down from the tabs: the selected option of the open tab, else its first control. */
    private int entryFocus() {
        int prop = gridProp();
        if (prop >= 0) {
            int i = indexOf(K_TILE, mii.v[prop], prop);
            if (i >= 0) return i;
        }
        for (int i = 0; i < els.size(); i++) {
            int k = els.get(i).kind;
            if (k != K_TAB && k != K_QUIT && k != K_SUB) return i;
        }
        return focus;
    }

    // ---- keys -------------------------------------------------------------------------------------------

    void key(int code) {
        if (code == KeyEvent.KEYCODE_BACK) {
            host.sound(WiiSounds.BACK);
            host.editorQuit();
            return;
        }
        MiiUi.Item cur = els.get(focus);
        int dx = MiiUi.dx(code), dy = MiiUi.dy(code);
        if (cur.kind == K_SLIDER && dx != 0) {
            int before = mii.v[cur.a];
            mii.set(cur.a, before + dx * 4);
            if (mii.v[cur.a] != before) {
                pop = Math.max(pop, 0.4f);
                host.sound(WiiSounds.MOVE);
            }
            return;
        }
        if (code == KeyEvent.KEYCODE_PAGE_UP || code == KeyEvent.KEYCODE_CHANNEL_UP
                || code == KeyEvent.KEYCODE_PAGE_DOWN || code == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            if (pages() > 1) {
                int d = code == KeyEvent.KEYCODE_PAGE_UP || code == KeyEvent.KEYCODE_CHANNEL_UP ? -1 : 1;
                turnPage(d);
            }
            return;
        }
        if (dx != 0 || dy != 0) {
            if (cur.kind == K_TAB && dy > 0) {
                focus = entryFocus();
                host.sound(WiiSounds.MOVE);
                return;
            }
            int n = MiiUi.nav(els, focus, dx, dy);
            if (n < 0) return;
            focus = n;
            MiiUi.Item it = els.get(n);
            if (it.kind == K_TAB) setTab(it.a);
            else host.sound(WiiSounds.MOVE);
            return;
        }
        if (MiiUi.isOk(code)) activate(cur);
    }

    private void turnPage(int d) {
        int pg = pages();
        MiiUi.Item keep = els.get(focus);
        int slot = keep.kind == K_TILE ? keep.a % 8 : -1;
        page = (page + d + pg) % pg;
        build(keep.kind == K_TILE ? null : keep);
        if (slot >= 0) {
            int prop = gridProp(), count = Mii.MAX[prop] + 1;
            int idx = Math.min(count - 1, page * 8 + slot);
            int i = indexOf(K_TILE, idx, prop);
            if (i >= 0) focus = i;
        }
        host.sound(WiiSounds.PAGE);
    }

    private void activate(MiiUi.Item it) {
        switch (it.kind) {
            case K_TAB:
                focus = entryFocus();
                host.sound(WiiSounds.SELECT);
                break;
            case K_SUB:
                if (sub != it.a) {
                    sub = it.a;
                    page = pageOf(gridProp());
                    build(it);
                }
                host.sound(WiiSounds.SELECT);
                break;
            case K_TILE:
                change(it.b, it.a);
                break;
            case K_PAGE:
                turnPage(it.a);
                break;
            case K_SWATCH:
                change(it.b, it.a);
                break;
            case K_ADJ: {
                int before = mii.v[it.a];
                mii.set(it.a, before + it.b);
                if (mii.v[it.a] == before) host.sound(WiiSounds.BACK);
                else {
                    host.sound(WiiSounds.MOVE);
                    pop = Math.max(pop, 0.5f);
                }
                break;
            }
            case K_FLIP:
                change(Mii.HAIR_FLIP, 1 - mii.v[Mii.HAIR_FLIP]);
                break;
            case K_QUIT:
                host.sound(WiiSounds.SELECT);
                host.editorQuit();
                break;
            case K_FIELD: {
                host.sound(WiiSounds.SELECT);
                final boolean name = it.a == 0;
                host.keyboard(host.str(name ? R.string.mii_name_prompt : R.string.mii_creator_prompt),
                        name ? mii.name : mii.creator, text -> {
                            if (text == null) return;
                            if (name) mii.name = Mii.clip(text.trim());
                            else mii.creator = Mii.clip(text.trim());
                        });
                break;
            }
            case K_GENDER:
                change(Mii.FEMALE, it.a);
                break;
            case K_FAV:
                change(Mii.FAVORITE, 1 - mii.v[Mii.FAVORITE]);
                break;
            case K_STEP: {
                int prop = it.a;
                int v = mii.v[prop] + it.b;
                if (prop == Mii.MONTH) {
                    if (v < 1) v = 12;
                    if (v > 12) v = 1;
                } else {
                    int max = daysIn(mii.v[Mii.MONTH]);
                    if (v < 1) v = max;
                    if (v > max) v = 1;
                }
                mii.set(prop, v);
                if (prop == Mii.MONTH) mii.set(Mii.DAY, Math.min(mii.v[Mii.DAY], daysIn(v)));
                host.sound(WiiSounds.MOVE);
                break;
            }
            case K_FAVCOLOR:
                change(Mii.COLOR, it.a);
                break;
            default:
                break;
        }
    }

    private static int daysIn(int month) {
        switch (month) {
            case 2:
                return 29;
            case 4:
            case 6:
            case 9:
            case 11:
                return 30;
            default:
                return 31;
        }
    }

    private void change(int prop, int value) {
        boolean same = mii.v[prop] == value;
        mii.set(prop, value);
        if (prop == Mii.HAIR_COLOR && !same && mii.v[Mii.BROW_COLOR] == mii.v[Mii.BEARD_COLOR]) {
            // Brows and beard follow the hair until the user gives them a colour of their own.
            mii.set(Mii.BROW_COLOR, value);
            mii.set(Mii.BEARD_COLOR, value);
        }
        host.sound(WiiSounds.SELECT);
        if (!same) pop = 1f;
    }

    void handTarget(float[] out) {
        RectF b = els.get(focus).r;
        out[0] = b.left + b.width() * (b.width() > 400f ? 0.85f : 0.7f);
        out[1] = b.top + b.height() * 0.72f;
    }

    // ---- animation and drawing --------------------------------------------------------------------------

    void step(float dt) {
        time += dt;
        float k = 1f - (float) Math.exp(-dt / 0.07f);
        for (int i = 0; i < els.size(); i++) {
            MiiUi.Item it = els.get(i);
            it.lift = MiiUi.approach(it.lift, i == focus ? 1f : 0f, k, 0.003f);
        }
        zoom = MiiUi.approach(zoom, tab == T_BODY || tab == T_INFO ? 1f : 0f, 1f - (float) Math.exp(-dt / 0.12f), 0.002f);
        pop = Math.max(0f, pop - dt * 3f);
        blinkT -= dt;
        if (blinkLeft > 0f) blinkLeft -= dt;
        if (blinkT <= 0f) {
            blinkLeft = 0.13f;
            blinkT = 2.5f + (float) Math.random() * 3f;
        }
    }

    void draw(Canvas c) {
        float s = ui.s;
        MiiArt.opaque(ui.fill).setShader(ui.gradient(0f, 1080f * s, 0xFFF4FFFC, 0xFFCEFFF1));
        c.drawRect(0, 0, 1920f * s, 1080f * s, ui.fill);
        ui.fill.setShader(null);
        drawPreview(c);
        drawPanel(c);
        drawTabs(c);
        for (MiiUi.Item it : els) {
            if (it.kind != K_TAB) drawItem(c, it);
        }
        if (tab == T_INFO) drawInfoLabels(c);
        if (tab == T_BODY) drawBodyLabels(c);
        if (pages() > 1 && tab != T_INFO && tab != T_BODY) {
            int i = indexOf(K_PAGE, 1, 1);
            if (i >= 0) {
                RectF a = els.get(i).r;
                ui.label(c, (page + 1) + "/" + pages(), (702f + 782f) / 2f, a.bottom + 54f, 32f, 0xFF5E7473,
                        Paint.Align.CENTER, true);
            }
        }
    }

    private void drawPreview(Canvas c) {
        float s = ui.s;
        MiiArt.Pose p = pose.reset();
        p.closed = blinkLeft > 0f;
        MiiArt.place(mii, p);
        float top = -MiiArt.figureTop(mii);
        float bodyU = 760f / top, bodyEye = 1022f + p.headY * bodyU;
        float u = 330f + (bodyU - 330f) * zoom;
        float eyeY = 548f + (bodyEye - 548f) * zoom;
        float feetY = eyeY - p.headY * u;
        float sc = u * s * (1f + 0.035f * (float) Math.sin(pop * Math.PI));
        int save = c.save();
        c.clipRect(0, 160f * s, 640f * s, 1080f * s);
        if (zoom > 0.02f) {
            ui.fill.setColor(MiiArt.alpha(0x22000000, (int) (0x22 * zoom)));
            r.set((345f - 0.7f * u) * s, (feetY - 0.16f * u) * s, (345f + 0.7f * u) * s, (feetY + 0.16f * u) * s);
            c.drawOval(r, ui.fill);
        }
        c.translate(345f * s, feetY * s);
        c.scale(sc, sc);
        float bob = (float) Math.sin(time * 1.3f) * 1.4f;
        int hs = c.save();
        c.translate(p.headX, p.headY);
        c.rotate(bob, 0f, 0.6f);
        art.head(c, mii, MiiArt.FRONT, true, false);
        c.restoreToCount(hs);
        art.body(c, mii, p);
        hs = c.save();
        c.translate(p.headX, p.headY);
        c.rotate(bob, 0f, 0.6f);
        art.head(c, mii, MiiArt.FRONT, false, p.closed);
        c.restoreToCount(hs);
        c.restoreToCount(save);
    }

    private void drawPanel(Canvas c) {
        float s = ui.s;
        RectF p = new RectF(ui.sr(PANEL));
        float rad = 34f * s;
        ui.fill.setColor(0xFFBECBCB);
        c.drawRoundRect(p, rad, rad, ui.fill);
        int save = c.save();
        ui.path.reset();
        ui.path.addRoundRect(p, rad, rad, Path.Direction.CW);
        c.clipPath(ui.path);
        ui.fill.setColor(0xFFC6D5D5);
        for (float y = p.top; y < p.bottom; y += 4f * s) c.drawRect(p.left, y + 2f * s, p.right, y + 3.4f * s, ui.fill);
        c.restoreToCount(save);
        ui.stroke.setColor(0xFF8FA2A1);
        ui.stroke.setStrokeWidth(5f * s);
        c.drawRoundRect(p, rad, rad, ui.stroke);
        if (tab == T_INFO || tab == T_BODY) {
            RectF in = new RectF(ui.sr(684f, 214f, 1880f, 1046f));
            ui.fill.setColor(0xFFEAF4F4);
            c.drawRoundRect(in, 30f * s, 30f * s, ui.fill);
            return;
        }
        RectF in = new RectF(ui.sr(684f, innerTop(), 1416f, 1046f));
        ui.fill.setColor(0xFFEAF4F4);
        c.drawRoundRect(in, 30f * s, 30f * s, ui.fill);
        if (colorProp() >= 0) {
            int nc = palette(colorProp()).length;
            float bottom = nc > 4 && nc != 3 ? 236f + 2 * 76f + 24f + 22f : 236f + 76f + 22f;
            RectF b = new RectF(ui.sr(SIDE_L, 214f, SIDE_R, bottom));
            ui.fill.setColor(0xFFEEF7F7);
            c.drawRoundRect(b, 26f * s, 26f * s, ui.fill);
        }
    }

    private void drawTabs(Canvas c) {
        float s = ui.s;
        RectF frame = new RectF(ui.sr(14f, -40f, 1906f, 168f));
        ui.box(c, frame, 30f * s, 0xFFF4FAF9, 0xFFE3EDEC, 0xFFB4C4C3, 4f * s);
        for (MiiUi.Item it : els) {
            if (it.kind != K_TAB) continue;
            boolean sel = it.a == tab;
            RectF t = new RectF(ui.sr(it.r));
            float sc = 1f + 0.04f * it.lift;
            int save = c.save();
            c.scale(sc, sc, t.centerX(), t.centerY());
            if (sel) ui.box(c, t, 24f * s, 0xFF007E5E, 0xFF009A73, 0xFF00553F, 4.5f * s);
            else ui.box(c, t, 24f * s, 0xFF93A3A3, 0xFFB1C0C0, 0xFF6F8180, 4.5f * s);
            ui.glow(c, t, 24f * s, it.lift);
            tabIcon(c, it.a, t.centerX(), t.centerY(), t.height());
            c.restoreToCount(save);
        }
    }

    /** White line icons for the tabs and sub-tabs, centred on (cx, cy), size h (screen px). */
    private void tabIcon(Canvas c, int which, float cx, float cy, float h) {
        Paint f = ui.fill, st = ui.stroke;
        f.setShader(null);
        f.setColor(0xFFFFFFFF);
        st.setColor(0xFFFFFFFF);
        st.setStrokeWidth(h * 0.055f);
        float u = h * 0.5f;
        switch (which) {
            case T_INFO: {
                r.set(cx - u * 0.75f, cy - u * 0.72f, cx + u * 0.75f, cy - u * 0.28f);
                c.drawRoundRect(r, u * 0.22f, u * 0.22f, f);
                f.setColor(0xFF93A3A3);
                for (int k = 0; k < 4; k++) c.drawCircle(cx - u * 0.38f + k * u * 0.25f, cy - u * 0.5f, u * 0.06f, f);
                f.setColor(0xFFFFFFFF);
                c.drawCircle(cx, cy + u * 0.3f, u * 0.38f, f);
                break;
            }
            case T_BODY:
                ui.figure(c, cx, cy, h * 0.72f, 0xFFFFFFFF, false);
                break;
            case T_FACE:
            case 100: {
                path.reset();
                r.set(cx - u * 0.48f, cy - u * 0.62f, cx + u * 0.48f, cy + u * 0.3f);
                path.addArc(r, 180f, 180f);
                path.cubicTo(cx + u * 0.48f, cy + u * 0.5f, cx + u * 0.2f, cy + u * 0.68f, cx, cy + u * 0.7f);
                path.cubicTo(cx - u * 0.2f, cy + u * 0.68f, cx - u * 0.48f, cy + u * 0.5f, cx - u * 0.48f, cy - u * 0.16f);
                c.drawPath(path, st);
                c.drawCircle(cx - u * 0.18f, cy, u * 0.06f, f);
                c.drawCircle(cx + u * 0.18f, cy, u * 0.06f, f);
                r.set(cx - u * 0.2f, cy + u * 0.05f, cx + u * 0.2f, cy + u * 0.38f);
                c.drawArc(r, 30f, 120f, false, st);
                break;
            }
            case T_HAIR: {
                path.reset();
                r.set(cx - u * 0.52f, cy - u * 0.7f, cx + u * 0.52f, cy + u * 0.36f);
                path.addArc(r, 180f, 180f);
                path.lineTo(cx + u * 0.52f, cy + u * 0.3f);
                path.lineTo(cx + u * 0.36f, cy + u * 0.3f);
                path.lineTo(cx + u * 0.36f, cy - u * 0.12f);
                path.quadTo(cx, cy - u * 0.32f, cx - u * 0.36f, cy - u * 0.12f);
                path.lineTo(cx - u * 0.36f, cy + u * 0.3f);
                path.lineTo(cx - u * 0.52f, cy + u * 0.3f);
                path.close();
                c.drawPath(path, f);
                st.setStrokeWidth(h * 0.04f);
                path.reset();
                path.moveTo(cx - u * 0.36f, cy + u * 0.1f);
                path.cubicTo(cx - u * 0.36f, cy + u * 0.6f, cx + u * 0.36f, cy + u * 0.6f, cx + u * 0.36f, cy + u * 0.1f);
                c.drawPath(path, st);
                break;
            }
            case T_BROW:
                st.setStrokeWidth(h * 0.09f);
                for (int sgn = -1; sgn <= 1; sgn += 2) {
                    path.reset();
                    path.moveTo(cx + sgn * u * 0.12f, cy + u * 0.06f);
                    path.quadTo(cx + sgn * u * 0.4f, cy - u * 0.16f, cx + sgn * u * 0.7f, cy);
                    c.drawPath(path, st);
                }
                break;
            case T_EYE:
                for (int sgn = -1; sgn <= 1; sgn += 2) {
                    r.set(cx + sgn * u * 0.42f - u * 0.3f, cy - u * 0.2f, cx + sgn * u * 0.42f + u * 0.3f, cy + u * 0.2f);
                    c.drawOval(r, f);
                    f.setColor(0xFF93A3A3);
                    c.drawCircle(cx + sgn * u * 0.42f, cy, u * 0.12f, f);
                    f.setColor(0xFFFFFFFF);
                }
                break;
            case T_NOSE:
                st.setStrokeWidth(h * 0.07f);
                path.reset();
                path.moveTo(cx + u * 0.05f, cy - u * 0.55f);
                path.cubicTo(cx, cy - u * 0.1f, cx - u * 0.35f, cy + u * 0.15f, cx - u * 0.2f, cy + u * 0.38f);
                path.quadTo(cx, cy + u * 0.5f, cx + u * 0.25f, cy + u * 0.36f);
                c.drawPath(path, st);
                break;
            case T_MOUTH:
                path.reset();
                path.moveTo(cx - u * 0.62f, cy);
                path.quadTo(cx - u * 0.3f, cy - u * 0.34f, cx, cy - u * 0.16f);
                path.quadTo(cx + u * 0.3f, cy - u * 0.34f, cx + u * 0.62f, cy);
                path.quadTo(cx, cy + u * 0.5f, cx - u * 0.62f, cy);
                path.close();
                c.drawPath(path, f);
                st.setColor(0xFF93A3A3);
                st.setStrokeWidth(h * 0.03f);
                c.drawLine(cx - u * 0.5f, cy, cx + u * 0.5f, cy, st);
                break;
            case T_EXTRA:
            case 101:
                st.setStrokeWidth(h * 0.05f);
                c.drawCircle(cx - u * 0.36f, cy - u * 0.18f, u * 0.26f, st);
                c.drawCircle(cx + u * 0.36f, cy - u * 0.18f, u * 0.26f, st);
                c.drawLine(cx - u * 0.1f, cy - u * 0.22f, cx + u * 0.1f, cy - u * 0.22f, st);
                if (which == T_EXTRA) {
                    path.reset();
                    path.moveTo(cx, cy + u * 0.3f);
                    path.quadTo(cx - u * 0.3f, cy + u * 0.16f, cx - u * 0.5f, cy + u * 0.48f);
                    path.quadTo(cx - u * 0.2f, cy + u * 0.4f, cx, cy + u * 0.44f);
                    path.quadTo(cx + u * 0.2f, cy + u * 0.4f, cx + u * 0.5f, cy + u * 0.48f);
                    path.quadTo(cx + u * 0.3f, cy + u * 0.16f, cx, cy + u * 0.3f);
                    c.drawPath(path, f);
                }
                break;
            case 102: {
                path.reset();
                path.moveTo(cx, cy - u * 0.05f);
                path.quadTo(cx - u * 0.45f, cy - u * 0.3f, cx - u * 0.75f, cy + u * 0.2f);
                path.quadTo(cx - u * 0.35f, cy + u * 0.08f, cx, cy + u * 0.12f);
                path.quadTo(cx + u * 0.35f, cy + u * 0.08f, cx + u * 0.75f, cy + u * 0.2f);
                path.quadTo(cx + u * 0.45f, cy - u * 0.3f, cx, cy - u * 0.05f);
                c.drawPath(path, f);
                break;
            }
            case 103: {
                path.reset();
                path.moveTo(cx - u * 0.55f, cy - u * 0.4f);
                path.lineTo(cx - u * 0.5f, cy + u * 0.05f);
                path.quadTo(cx, cy + u * 0.8f, cx + u * 0.5f, cy + u * 0.05f);
                path.lineTo(cx + u * 0.55f, cy - u * 0.4f);
                path.lineTo(cx + u * 0.3f, cy - u * 0.05f);
                path.quadTo(cx, cy + u * 0.15f, cx - u * 0.3f, cy - u * 0.05f);
                path.close();
                c.drawPath(path, f);
                break;
            }
            case 104:
                tabIcon(c, 100, cx, cy, h);
                f.setColor(0xFFFFFFFF);
                c.drawCircle(cx + u * 0.28f, cy + u * 0.3f, u * 0.09f, f);
                break;
            case 105:
                tabIcon(c, 100, cx, cy, h);
                f.setColor(0xFFFFB0BC);
                c.drawCircle(cx - u * 0.28f, cy + u * 0.22f, u * 0.1f, f);
                c.drawCircle(cx + u * 0.28f, cy + u * 0.22f, u * 0.1f, f);
                break;
            default:
                break;
        }
    }

    private int subIcon(int i) {
        if (tab == T_FACE) return i == 0 ? 100 : 105;
        return i == 0 ? 101 : i == 1 ? 102 : i == 2 ? 103 : 104;
    }

    private void drawItem(Canvas c, MiiUi.Item it) {
        float s = ui.s;
        RectF b = new RectF(ui.sr(it.r));
        float lf = it.lift;
        float sc = 1f + 0.05f * lf;
        int save = c.save();
        c.scale(sc, sc, b.centerX(), b.centerY());
        switch (it.kind) {
            case K_SUB: {
                boolean sel = it.a == sub;
                float rad = b.height() * 0.32f;
                if (sel) ui.box(c, b, rad, 0xFF00A57C, 0xFF008A68, 0xFF4E6463, 4f * s);
                else ui.box(c, b, rad, 0xFFBCD3D3, 0xFFA2BDBD, 0xFF5E7473, 4f * s);
                ui.glow(c, b, rad, lf);
                tabIcon(c, subIcon(it.a), b.centerX(), b.centerY(), b.height() * 1.05f);
                break;
            }
            case K_TILE: {
                boolean sel = mii.v[it.b] == it.a;
                float rad = 22f * s;
                if (sel) ui.box(c, b, rad, 0xFF66F2BB, 0xFF12D68A, 0xFF4D6664, 5f * s);
                else ui.box(c, b, rad, 0xFFFFFFFF, 0xFFF1F6F6, 0xFF647B79, 5f * s);
                ui.glow(c, b, rad, lf);
                int cs = c.save();
                c.clipRect(b);
                art.icon(c, it.b, it.a, mii, b.centerX(), b.centerY(), b.height() * 0.8f);
                c.restoreToCount(cs);
                break;
            }
            case K_PAGE: {
                float cx = b.centerX(), cy = b.centerY(), w = b.width() * 0.42f, hh = b.height() * 0.38f;
                ui.path.reset();
                ui.path.moveTo(cx + it.a * w, cy);
                ui.path.lineTo(cx - it.a * w, cy - hh);
                ui.path.lineTo(cx - it.a * w, cy + hh);
                ui.path.close();
                MiiArt.opaque(ui.fill).setShader(new LinearGradient(0, cy - hh, 0, cy + hh,
                        new int[] {0xFF6FCDF3, 0xFFDDF8FF, 0xFF62C5F0}, null, Shader.TileMode.CLAMP));
                c.drawPath(ui.path, ui.fill);
                ui.fill.setShader(null);
                ui.stroke.setColor(lf > 0.5f ? MiiUi.BLUE : 0xFF2E66C2);
                ui.stroke.setStrokeWidth((3.5f + 3f * lf) * s);
                c.drawPath(ui.path, ui.stroke);
                break;
            }
            case K_SWATCH:
            case K_FAVCOLOR: {
                int prop = it.kind == K_FAVCOLOR ? Mii.COLOR : it.b;
                int col = it.kind == K_FAVCOLOR ? Mii.FAVORITE_COLORS[it.a] : palette(prop)[it.a];
                boolean sel = mii.v[prop] == it.a;
                float rad = 14f * s;
                if (sel) {
                    RectF o = new RectF(b);
                    o.inset(-7f * s, -7f * s);
                    ui.fill.setColor(0xFF00C896);
                    c.drawRoundRect(o, rad + 6f * s, rad + 6f * s, ui.fill);
                }
                ui.box(c, b, rad, MiiArt.shade(col, 0.12f), MiiArt.shade(col, -0.1f), 0xFF5E7473, 3f * s);
                ui.glow(c, b, rad, lf);
                break;
            }
            case K_ADJ:
            case K_FLIP:
            case K_STEP: {
                float rad = 20f * s;
                ui.box(c, b, rad, 0xFFF2F8F8, 0xFFBFCDCD, 0xFF647B79, 4.5f * s);
                ui.glow(c, b, rad, lf);
                if (it.kind == K_FLIP) flipIcon(c, b);
                else if (it.kind == K_STEP) arrow(c, b.centerX(), b.centerY(), b.height() * 0.24f, it.b < 0 ? 180f : 0f);
                else adjIcon(c, it.a, it.b, b);
                break;
            }
            case K_QUIT: {
                float rad = 30f * s;
                ui.box(c, b, rad, 0xFFA2B8B6, 0xFF7C9694, 0xFF4E6463, 6f * s);
                ui.glow(c, b, rad, lf);
                ui.rimmed(c, host.str(R.string.mii_quit), b.centerX() / s, b.centerY() / s + 22f, 62f, 0xFFFFFFFF,
                        0xFF3E5250);
                break;
            }
            case K_FIELD: {
                float rad = 22f * s;
                ui.box(c, b, rad, 0xFFFFFFFF, 0xFFF0F5F5, 0xFF647B79, 4.5f * s);
                ui.glow(c, b, rad, lf);
                String v = it.a == 0 ? mii.name : mii.creator;
                boolean empty = v.isEmpty();
                ui.label(c, empty ? host.str(R.string.mii_no_name) : v, b.left / s + 30f, b.centerY() / s + 15f, 44f,
                        empty ? 0xFFA0AEAE : 0xFF3A4446, Paint.Align.LEFT, true);
                break;
            }
            case K_GENDER:
            case K_FAV: {
                boolean sel = it.kind == K_FAV ? mii.favorite() : mii.v[Mii.FEMALE] == it.a;
                float rad = b.height() / 2f;
                if (sel) ui.box(c, b, rad, 0xFF00A57C, 0xFF008A68, 0xFF4E6463, 4.5f * s);
                else ui.box(c, b, rad, 0xFFF2F8F8, 0xFFBFCDCD, 0xFF647B79, 4.5f * s);
                ui.glow(c, b, rad, lf);
                if (it.kind == K_FAV) {
                    star(c, b.centerX(), b.centerY(), b.height() * 0.32f, sel ? 0xFFFFD43A : 0xFF9AAAAA);
                } else {
                    int ink = sel ? 0xFFFFFFFF : 0xFF4E6262;
                    ui.figure(c, b.left + 56f * s, b.centerY(), b.height() * 0.7f, it.a == 1 ? 0xFFFF9AC0 : 0xFF7CC4FF,
                            it.a == 1);
                    ui.label(c, host.str(it.a == 1 ? R.string.mii_girl : R.string.mii_boy), b.centerX() / s + 30f,
                            b.centerY() / s + 15f, 42f, ink, Paint.Align.CENTER, true);
                }
                break;
            }
            case K_SLIDER:
                drawSlider(c, it);
                break;
            default:
                break;
        }
        c.restoreToCount(save);
    }

    private void star(Canvas c, float cx, float cy, float rr, int color) {
        ui.path.reset();
        for (int k = 0; k < 10; k++) {
            double a = -Math.PI / 2 + k * Math.PI / 5;
            float rad = k % 2 == 0 ? rr : rr * 0.45f;
            float x = cx + (float) Math.cos(a) * rad, y = cy + (float) Math.sin(a) * rad;
            if (k == 0) ui.path.moveTo(x, y);
            else ui.path.lineTo(x, y);
        }
        ui.path.close();
        ui.fill.setColor(color);
        c.drawPath(ui.path, ui.fill);
    }

    private void arrow(Canvas c, float cx, float cy, float size, float deg) {
        int save = c.save();
        c.rotate(deg, cx, cy);
        ui.path.reset();
        ui.path.moveTo(cx + size, cy);
        ui.path.lineTo(cx - size * 0.6f, cy - size);
        ui.path.lineTo(cx - size * 0.6f, cy + size);
        ui.path.close();
        ui.fill.setColor(0xFF4E6262);
        c.drawPath(ui.path, ui.fill);
        c.restoreToCount(save);
    }

    private void adjIcon(Canvas c, int prop, int dir, RectF b) {
        float cx = b.centerX(), cy = b.centerY(), u = b.height() * 0.28f;
        switch (prop) {
            case Mii.BROW_Y:
            case Mii.EYE_Y:
            case Mii.NOSE_Y:
            case Mii.MOUTH_Y:
            case Mii.GLASSES_Y:
            case Mii.MUSTACHE_Y:
            case Mii.MOLE_Y:
                arrow(c, cx, cy, u, dir < 0 ? -90f : 90f);
                break;
            case Mii.MOLE_X:
                arrow(c, cx, cy, u, dir < 0 ? 180f : 0f);
                break;
            case Mii.BROW_SPACE:
            case Mii.EYE_SPACE:
                if (dir < 0) {
                    arrow(c, cx - u * 0.9f, cy, u * 0.7f, 0f);
                    arrow(c, cx + u * 0.9f, cy, u * 0.7f, 180f);
                } else {
                    arrow(c, cx - u * 0.9f, cy, u * 0.7f, 180f);
                    arrow(c, cx + u * 0.9f, cy, u * 0.7f, 0f);
                }
                ui.fill.setColor(0xFF4E6262);
                c.drawRect(cx - u * 0.08f, cy - u * 0.8f, cx + u * 0.08f, cy + u * 0.8f, ui.fill);
                break;
            case Mii.BROW_ROT:
            case Mii.EYE_ROT: {
                ui.stroke.setColor(0xFF4E6262);
                ui.stroke.setStrokeWidth(u * 0.28f);
                r.set(cx - u, cy - u, cx + u, cy + u);
                c.drawArc(r, dir < 0 ? 200f : -20f, dir < 0 ? -150f : 150f, false, ui.stroke);
                double end = Math.toRadians(dir < 0 ? 50 : 130);
                float ex = cx + (float) Math.cos(end) * u, ey = cy + (float) Math.sin(end) * u;
                arrow(c, ex, ey, u * 0.45f, dir < 0 ? 140f : 40f);
                break;
            }
            default: {
                float sq = dir < 0 ? u * 0.55f : u * 1.0f;
                ui.stroke.setColor(0xFF4E6262);
                ui.stroke.setStrokeWidth(u * 0.2f);
                r.set(cx - sq, cy - sq, cx + sq, cy + sq);
                c.drawRoundRect(r, sq * 0.2f, sq * 0.2f, ui.stroke);
                ui.fill.setColor(0xFF4E6262);
                c.drawRect(cx - u * 0.35f, cy - u * 0.08f, cx + u * 0.35f, cy + u * 0.08f, ui.fill);
                if (dir > 0) c.drawRect(cx - u * 0.08f, cy - u * 0.35f, cx + u * 0.08f, cy + u * 0.35f, ui.fill);
                break;
            }
        }
    }

    private void flipIcon(Canvas c, RectF b) {
        float cx = b.centerX(), cy = b.centerY(), u = b.height() * 0.3f;
        arrow(c, cx - u * 1.3f, cy, u * 0.7f, 180f);
        arrow(c, cx + u * 1.3f, cy, u * 0.7f, 0f);
        ui.fill.setColor(0xFF4E6262);
        c.drawRect(cx - u * 0.07f, cy - u, cx + u * 0.07f, cy + u, ui.fill);
        ui.label(c, host.str(R.string.mii_flip), cx / ui.s, b.bottom / ui.s - 10f, 24f, 0xFF4E6262,
                Paint.Align.CENTER, true);
    }

    private void drawSlider(Canvas c, MiiUi.Item it) {
        float s = ui.s;
        RectF b = new RectF(ui.sr(it.r));
        ui.glow(c, b, 30f * s, it.lift);
        float l = b.left + 110f * s, rr = b.right - 110f * s, cy = b.centerY();
        RectF track = new RectF(l, cy - 16f * s, rr, cy + 16f * s);
        ui.box(c, track, 16f * s, 0xFF9FB2B1, 0xFFC4D2D1, 0xFF647B79, 3f * s);
        float t = mii.v[it.a] / 127f;
        float kx = l + (rr - l) * t;
        ui.fill.setColor(0x30000000);
        c.drawCircle(kx + 3f * s, cy + 5f * s, 34f * s, ui.fill);
        RectF knob = new RectF(kx - 34f * s, cy - 34f * s, kx + 34f * s, cy + 34f * s);
        ui.box(c, knob, 34f * s, 0xFF29C795, 0xFF00936D, 0xFFFFFFFF, 5f * s);
        boolean tall = it.a == Mii.HEIGHT;
        float small = b.height() * 0.42f, big = b.height() * 0.62f;
        if (tall) {
            ui.figure(c, b.left + 50f * s, cy, small, 0xFF5E7473, false);
            ui.figure(c, b.right - 50f * s, cy - 6f * s, big, 0xFF5E7473, false);
        } else {
            int save = c.save();
            c.scale(0.7f, 1f, b.left + 50f * s, cy);
            ui.figure(c, b.left + 50f * s, cy, big * 0.9f, 0xFF5E7473, false);
            c.restoreToCount(save);
            save = c.save();
            c.scale(1.6f, 1f, b.right - 50f * s, cy);
            ui.figure(c, b.right - 50f * s, cy, big * 0.9f, 0xFF5E7473, false);
            c.restoreToCount(save);
        }
    }

    private void drawInfoLabels(Canvas c) {
        int ink = 0xFF4E6261;
        ui.label(c, host.str(R.string.mii_nickname), 720f, 300f, 40f, ink, Paint.Align.LEFT, true);
        ui.label(c, host.str(R.string.mii_creator), 720f, 422f, 40f, ink, Paint.Align.LEFT, true);
        ui.label(c, host.str(R.string.mii_gender), 720f, 544f, 40f, ink, Paint.Align.LEFT, true);
        ui.label(c, host.str(R.string.mii_birthday), 720f, 666f, 40f, ink, Paint.Align.LEFT, true);
        ui.label(c, host.str(R.string.mii_fav_color), 720f, 820f, 40f, ink, Paint.Align.LEFT, true);
        float s = ui.s;
        int day = mii.v[Mii.DAY], month = mii.v[Mii.MONTH];
        RectF d = new RectF(ui.sr(1106f, 602f, 1262f, 702f));
        ui.box(c, d, 20f * s, 0xFFFFFFFF, 0xFFF0F5F5, 0xFF647B79, 4f * s);
        ui.label(c, day == 0 ? "--" : String.valueOf(day), 1184f, 668f, 46f, 0xFF3A4446, Paint.Align.CENTER, true);
        RectF m = new RectF(ui.sr(1536f, 602f, 1692f, 702f));
        ui.box(c, m, 20f * s, 0xFFFFFFFF, 0xFFF0F5F5, 0xFF647B79, 4f * s);
        ui.label(c, month == 0 ? "--" : String.valueOf(month), 1614f, 668f, 46f, 0xFF3A4446, Paint.Align.CENTER, true);
        ui.label(c, host.str(R.string.mii_day), 1184f, 594f, 24f, 0xFF7A8C8B, Paint.Align.CENTER, true);
        ui.label(c, host.str(R.string.mii_month), 1614f, 594f, 24f, 0xFF7A8C8B, Paint.Align.CENTER, true);
    }

    private void drawBodyLabels(Canvas c) {
        int ink = 0xFF4E6261;
        ui.label(c, host.str(R.string.mii_height), 980f, 330f, 44f, ink, Paint.Align.LEFT, true);
        ui.label(c, host.str(R.string.mii_weight), 980f, 690f, 44f, ink, Paint.Align.LEFT, true);
    }
}
