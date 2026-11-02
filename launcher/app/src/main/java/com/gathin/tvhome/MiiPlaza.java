package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.KeyEvent;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The plaza: the channel's characters wandering a tiled floor, napping, chatting and waving; the hand
 * picks one, carries it about or takes it to the bin; the whistle lines them up and the parade marches
 * them toward the camera. Positions are ground points in head units (MiiFloor's camera); drawing is in
 * reference px scaled by ui.s.
 */
final class MiiPlaza {
    static final int B_EDIT = 0, B_NEW = 1, B_SORT = 2, B_HELP = 3, B_PARADE = 4, B_MENU = 5, B_TRASH = 6, B_REMOTE = 7,
            B_MAIL = 8;
    /** Centre and reach of each button in reference px, where the original has them. */
    private static final float[][] BUTTONS = {{202, 334, 96}, {202, 548, 96}, {1725, 962, 76}, {202, 949, 70},
            {1733, 147, 96}, {202, 120, 76}, {202, 767, 96}, {1733, 334, 96}, {1733, 548, 96}};
    private static final int[] ICONS = {MiiUi.ICON_EDIT, MiiUi.ICON_NEW, MiiUi.ICON_SORT, MiiUi.ICON_HELP,
            MiiUi.ICON_PARADE, MiiUi.ICON_MENU, MiiUi.ICON_TRASH, MiiUi.ICON_REMOTE, MiiUi.ICON_MAIL};
    static final int SORT_ABC = 0, SORT_FAV = 1, SORT_COLOR = 2, SORT_GENDER = 3, SORT_CLOSE = 4;
    private static final int[] SORT_ICONS = {MiiUi.ICON_ABC, MiiUi.ICON_FAV, MiiUi.ICON_COLOR, MiiUi.ICON_GENDER,
            MiiUi.ICON_SORT};

    private static final int K_BUTTON = 1, K_MII = 2, K_SORT = 3;

    static final int IDLE = 0, WALK = 1, SLEEP = 2, CHAT = 3, WAVE = 4, CARRIED = 5, FALL = 6, FORM = 7, FORMED = 8,
            PARADE = 9, POOF = 10, CHAT_GO = 11;
    private static final String[] CHAT_SIGNS = {"\u2026", "!", "?", "\u266A", "\u2665", "\u2606", "!!", "\u2026?"};

    /** Head bitmaps: pixels per head unit and the box they cover. */
    static final float RES = 80f;
    static float HB_L = -0.98f, HB_R = 0.98f, HB_T = -1.34f, HB_B = 1.0f;
    static float BB_T = -1.0f, BB_B = 1.62f;

    /** Fits the head bitmaps to a pack's sprites, which reach higher and wider than the drawn heads. */
    static void usePack(MiiPack p) {
        HB_L = Math.min(p.headBox[0], p.backBox[0]) - 0.02f;
        HB_R = -HB_L;
        HB_T = p.headBox[1] - 0.02f;
        HB_B = p.headBox[3] + 0.02f;
        BB_T = p.backBox[1] - 0.02f;
        BB_B = p.backBox[3] + 0.02f;
    }

    static final class Sprite {
        Mii mii;
        float x, z, tx, tz;
        int state = IDLE;
        float timer;
        int view = MiiArt.FRONT;
        float phase, walk, speed = 1.5f;
        float wave, wavePhase, waveT;
        float tilt;
        boolean closed;
        float blinkT = 2f, blinkLeft;
        float hover, select;
        float lift, vy;
        int bounces;
        float hop, hopV;
        Sprite partner;
        String bubble;
        float bubbleT;
        float appear = 1f;
        float poofT;
        float sx, sy, sc;
        final Bitmap[] heads = new Bitmap[5];
        /** Hair behind the body, by view (front, left, right). */
        final Bitmap[] backs = new Bitmap[3];
        boolean ready;
        final MiiArt.Pose pose = new MiiArt.Pose();
    }

    private final MiiChannelView host;
    private final MiiUi ui;
    private final MiiArt art;
    final List<Sprite> sprites = new ArrayList<>();
    private final List<Sprite> order = new ArrayList<>();
    private final List<MiiUi.Item> items = new ArrayList<>();
    private final Random rnd = new Random();
    private final float[] pt = new float[3];
    private final RectF r = new RectF();
    /** A name tag or a chat bubble in screen px, filled in again on every frame. */
    private final RectF tag = new RectF();
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final float[] btnLift = new float[BUTTONS.length];
    private final float[] sortLift = new float[5];
    private Bitmap floor;
    private Bitmap shadow;

    private int focusKind = K_BUTTON;
    private int focusIndex = B_NEW;
    private Sprite focusMii;
    Sprite selected;
    private Sprite carried;
    boolean sortOpen;
    int formation = -1;
    boolean parade;
    boolean editPick;
    private float handX = 960f, handY = 600f;
    private final boolean[] held = new boolean[4];
    private float holdT;
    private float uiAlpha = 1f;

    MiiPlaza(MiiChannelView host, MiiUi ui, MiiArt art) {
        this.host = host;
        this.ui = ui;
        this.art = art;
        Bitmap sh = Bitmap.createBitmap(96, 32, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(sh);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(48f, 16f, 48f, new int[] {0x55000000, 0x30000000, 0x00000000},
                new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.save();
        c.scale(1f, 1f / 3f, 48f, 16f);
        c.drawCircle(48f, 16f, 48f, p);
        c.restore();
        shadow = sh;
    }

    void setFloor(Bitmap b) {
        floor = b;
    }

    // ---- population -----------------------------------------------------------------------------------

    void setMiis(List<Mii> miis) {
        sprites.clear();
        for (Mii m : miis) {
            Sprite sp = new Sprite();
            sp.mii = m;
            randomSpot(pt);
            sp.x = pt[0];
            sp.z = pt[1];
            sp.tx = sp.x;
            sp.tz = sp.z;
            sp.timer = rnd.nextFloat() * 2.5f;
            sp.view = rnd.nextInt(3);
            sp.speed = 1.25f + rnd.nextFloat() * 0.55f;
            sp.blinkT = 1f + rnd.nextFloat() * 4f;
            sprites.add(sp);
        }
    }

    List<Mii> miis() {
        List<Mii> out = new ArrayList<>();
        for (Sprite sp : sprites) if (sp.state != POOF) out.add(sp.mii);
        return out;
    }

    /** Renders the head bitmaps of every sprite that lacks them; safe on a worker thread with its own art. */
    static void renderHeads(List<Sprite> list, MiiArt a) {
        for (Sprite sp : list) {
            if (!sp.ready) renderHeads(sp, a);
        }
    }

    static void renderHeads(Sprite sp, MiiArt a) {
        Mii m = sp.mii;
        sp.heads[0] = headBitmap(a, m, MiiArt.FRONT, false, false);
        sp.heads[1] = headBitmap(a, m, MiiArt.LEFT, false, false);
        sp.heads[2] = headBitmap(a, m, MiiArt.RIGHT, false, false);
        sp.heads[3] = headBitmap(a, m, MiiArt.BACK, false, false);
        sp.heads[4] = headBitmap(a, m, MiiArt.FRONT, false, true);
        boolean hb = hasBackHair(m);
        sp.backs[0] = hb ? headBitmap(a, m, MiiArt.FRONT, true, false) : null;
        boolean turned = MiiPack.peek() != null;
        sp.backs[1] = hb && turned ? headBitmap(a, m, MiiArt.LEFT, true, false) : sp.backs[0];
        sp.backs[2] = hb && turned ? headBitmap(a, m, MiiArt.RIGHT, true, false) : sp.backs[0];
        sp.ready = true;
    }

    static boolean hasBackHair(Mii m) {
        MiiPack pack = MiiPack.peek();
        if (pack != null) return MiiSprites.hasBack(pack, m);
        int[] st = MiiArt.HAIR[m.v[Mii.HAIR]];
        return st[0] != MiiArt.F_BALD && (st[1] >= MiiArt.S_CHIN || st[3] == MiiArt.X_PONY);
    }

    static Bitmap headBitmap(MiiArt a, Mii m, int view, boolean back, boolean closed) {
        float top = back ? BB_T : HB_T, bottom = back ? BB_B : HB_B;
        int w = (int) Math.ceil((HB_R - HB_L) * RES), h = (int) Math.ceil((bottom - top) * RES);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(RES, RES);
        c.translate(-HB_L, -top);
        a.head(c, m, view, back, closed);
        return b;
    }

    /** A new Mii drops into the middle of the plaza and waves. */
    void addNew(Mii m) {
        Sprite sp = new Sprite();
        sp.mii = m;
        MiiFloor.ground(960f, 760f, pt);
        sp.x = pt[0] + (rnd.nextFloat() - 0.5f) * 2f;
        sp.z = pt[1];
        sp.tx = sp.x;
        sp.tz = sp.z;
        sp.lift = 9f;
        sp.state = FALL;
        sp.bounces = 0;
        renderHeads(sp, art);
        sprites.add(sp);
        host.sfx(MiiAudio.LIFT);
    }

    void replace(Mii m) {
        for (Sprite sp : sprites) {
            if (sp.mii.id.equals(m.id)) {
                sp.mii = m;
                renderHeads(sp, art);
                sp.hop = 0.01f;
                sp.hopV = 3.2f;
                sp.wave = 0f;
                sp.waveT = 1.8f;
            }
        }
    }

    private void randomSpot(float[] out) {
        for (int i = 0; i < 8; i++) {
            float sx = 380f + rnd.nextFloat() * 1160f, sy = 340f + rnd.nextFloat() * 690f;
            if (MiiFloor.ground(sx, sy, out)) return;
        }
        out[0] = 0f;
        out[1] = 0f;
    }

    // ---- behaviour -----------------------------------------------------------------------------------------

    private boolean isHeld(Sprite sp) {
        return sp == focusMii || sp == selected;
    }

    void step(float dt) {
        float kLift = 1f - (float) Math.exp(-dt / 0.07f);
        for (int i = 0; i < btnLift.length; i++) {
            btnLift[i] = MiiUi.approach(btnLift[i], focusKind == K_BUTTON && focusIndex == i ? 1f : 0f, kLift, 0.003f);
        }
        for (int i = 0; i < sortLift.length; i++) {
            sortLift[i] = MiiUi.approach(sortLift[i], sortOpen && focusKind == K_SORT && focusIndex == i ? 1f : 0f,
                    kLift, 0.003f);
        }
        uiAlpha = MiiUi.approach(uiAlpha, parade ? 0f : 1f, 1f - (float) Math.exp(-dt / 0.15f), 0.004f);

        if (carried != null) {
            int dx = (held[1] ? 1 : 0) - (held[0] ? 1 : 0), dy = (held[3] ? 1 : 0) - (held[2] ? 1 : 0);
            if (dx != 0 || dy != 0) {
                holdT += dt;
                float v = 520f + Math.min(1f, holdT / 0.7f) * 900f;
                float len = (float) Math.hypot(dx, dy);
                handX = Math.max(40f, Math.min(1880f, handX + dx / len * v * dt));
                handY = Math.max(120f, Math.min(1040f, handY + dy / len * v * dt));
            } else {
                holdT = 0f;
            }
        }

        for (int i = sprites.size() - 1; i >= 0; i--) {
            Sprite sp = sprites.get(i);
            update(sp, dt);
            if (sp.state == POOF && sp.poofT >= 0.55f) {
                sprites.remove(i);
                if (sp == selected) selected = null;
                if (sp == focusMii) focusMii = null;
                host.plazaChanged();
            }
        }
        separate(dt);
    }

    private void update(Sprite sp, float dt) {
        float kHover = 1f - (float) Math.exp(-dt / 0.08f);
        boolean heldNow = isHeld(sp);
        sp.hover = MiiUi.approach(sp.hover, heldNow ? 1f : 0f, kHover, 0.003f);
        sp.select = MiiUi.approach(sp.select, sp == selected ? 1f : 0f, kHover, 0.003f);
        if (sp.appear < 1f && sp.state != POOF) sp.appear = Math.min(1f, sp.appear + dt * 3f);

        sp.blinkT -= dt;
        if (sp.blinkLeft > 0f) sp.blinkLeft -= dt;
        if (sp.blinkT <= 0f) {
            sp.blinkLeft = 0.13f;
            sp.blinkT = 2f + rnd.nextFloat() * 3.5f;
        }
        if (sp.hop > 0f) {
            sp.hop += sp.hopV * dt;
            sp.hopV -= 22f * dt;
            if (sp.hop <= 0f) {
                sp.hop = 0f;
                sp.hopV = 0f;
            }
        }
        if (sp.waveT > 0f) {
            sp.waveT -= dt;
            sp.wave = MiiUi.approach(sp.wave, sp.waveT > 0.3f ? 1f : 0f, 1f - (float) Math.exp(-dt / 0.08f), 0.003f);
            sp.wavePhase += dt * 11f;
        } else {
            sp.wave = MiiUi.approach(sp.wave, 0f, 1f - (float) Math.exp(-dt / 0.08f), 0.003f);
        }

        if (sp.state == POOF) {
            sp.poofT += dt;
            sp.appear = Math.max(0f, 1f - sp.poofT / 0.35f);
            return;
        }
        if (sp.state == CARRIED) {
            sp.view = MiiArt.FRONT;
            sp.walk = 0f;
            sp.phase += dt * 6f;
            float u = sp.sc > 0f ? sp.sc : 50f;
            sp.lift = 1.0f;
            float feetY = handY + 26f + (sp.lift - MiiArt.figureTop(sp.mii)) * u;
            if (MiiFloor.ground(handX, Math.min(feetY, 1400f), pt)) {
                sp.x = pt[0];
                sp.z = pt[1];
            }
            return;
        }
        if (sp.state == FALL) {
            sp.vy -= 32f * dt;
            sp.lift += sp.vy * dt;
            sp.walk = 0f;
            if (sp.lift <= 0f) {
                sp.lift = 0f;
                if (sp.bounces == 0 && sp.vy < -4f) {
                    sp.vy = 3.4f;
                    sp.bounces = 1;
                    host.sfx(MiiAudio.DROP);
                } else {
                    sp.vy = 0f;
                    sp.state = IDLE;
                    sp.timer = 1.2f;
                    sp.view = MiiArt.FRONT;
                    sp.waveT = 1.6f;
                    if (sp.bounces == 0) host.sfx(MiiAudio.POP);
                }
            }
            return;
        }

        if (heldNow && sp.state != FORM && sp.state != PARADE) {
            if (sp.state == CHAT || sp.state == CHAT_GO) endChat(sp);
            if (sp.state != FORMED) sp.state = IDLE;
            sp.timer = 0.8f;
            sp.view = MiiArt.FRONT;
            sp.walk = MiiUi.approach(sp.walk, 0f, 1f - (float) Math.exp(-dt / 0.08f), 0.01f);
            sp.closed = sp.blinkLeft > 0f;
            sp.tilt = 0f;
            sp.bubble = null;
            return;
        }

        boolean moving = false;
        switch (sp.state) {
            case WALK:
            case FORM:
            case CHAT_GO:
                moving = walkTo(sp, dt, sp.state == FORM ? 2.6f : sp.speed);
                if (!moving) arrive(sp);
                break;
            case PARADE: {
                float sp2 = 1.3f;
                sp.z -= sp2 * dt;
                sp.view = MiiArt.FRONT;
                moving = true;
                sp.phase += dt * sp2 * 3.4f;
                MiiFloor.project(sp.x, sp.z, pt);
                if (pt[1] > 1190f) {
                    float maxZ = sp.z;
                    for (Sprite o : sprites) if (o.state == PARADE && Math.abs(o.x - sp.x) < 0.5f) maxZ = Math.max(maxZ, o.z);
                    sp.z = maxZ + 2.6f;
                    sp.appear = 0f;
                }
                break;
            }
            case SLEEP:
                sp.timer -= dt;
                sp.closed = true;
                sp.tilt = MiiUi.approach(sp.tilt, 12f, 1f - (float) Math.exp(-dt / 0.4f), 0.05f);
                if (sp.timer <= 0f) {
                    sp.state = IDLE;
                    sp.timer = 1f + rnd.nextFloat() * 2f;
                    sp.hop = 0.01f;
                    sp.hopV = 2.6f;
                }
                break;
            case CHAT:
                sp.timer -= dt;
                sp.bubbleT -= dt;
                if (sp.partner != null) sp.view = sp.partner.x > sp.x ? MiiArt.RIGHT : MiiArt.LEFT;
                if (sp.bubbleT <= 0f && sp.partner != null) {
                    sp.bubble = null;
                    sp.partner.bubble = CHAT_SIGNS[rnd.nextInt(CHAT_SIGNS.length)];
                    sp.partner.bubbleT = 1.1f + rnd.nextFloat() * 0.5f;
                    sp.bubbleT = 99f;
                }
                if (sp.timer <= 0f) endChat(sp);
                break;
            case FORMED:
                sp.view = MiiArt.FRONT;
                break;
            default:
                sp.timer -= dt;
                if (sp.timer <= 0f) decide(sp);
                break;
        }
        if (sp.state != SLEEP) {
            sp.closed = sp.blinkLeft > 0f;
            sp.tilt = MiiUi.approach(sp.tilt, 0f, 1f - (float) Math.exp(-dt / 0.2f), 0.05f);
        }
        sp.walk = MiiUi.approach(sp.walk, moving ? 1f : 0f, 1f - (float) Math.exp(-dt / 0.1f), 0.01f);
    }

    /** Steps toward the target; false once there. */
    private boolean walkTo(Sprite sp, float dt, float speed) {
        float dx = sp.tx - sp.x, dz = sp.tz - sp.z;
        float d = (float) Math.hypot(dx, dz);
        if (d < 0.08f) return false;
        float stepLen = Math.min(d, speed * dt);
        sp.x += dx / d * stepLen;
        sp.z += dz / d * stepLen;
        sp.phase += dt * speed * 3.4f;
        double ang = Math.toDegrees(Math.atan2(dx, -dz));
        if (Math.abs(ang) < 32) sp.view = MiiArt.FRONT;
        else if (Math.abs(ang) > 148) sp.view = MiiArt.BACK;
        else sp.view = ang > 0 ? MiiArt.RIGHT : MiiArt.LEFT;
        return true;
    }

    private void arrive(Sprite sp) {
        if (sp.state == FORM) {
            sp.state = FORMED;
            sp.view = MiiArt.FRONT;
            return;
        }
        if (sp.state == CHAT_GO) {
            Sprite o = sp.partner;
            if (o == null || o.partner != sp) {
                sp.state = IDLE;
                sp.timer = 1f;
                return;
            }
            sp.state = CHAT;
            sp.timer = 5f + rnd.nextFloat() * 2f;
            if (o.state == CHAT) {
                sp.bubble = CHAT_SIGNS[rnd.nextInt(CHAT_SIGNS.length)];
                sp.bubbleT = 1.2f;
                o.timer = sp.timer;
            } else {
                sp.bubbleT = 99f;
            }
            return;
        }
        sp.state = IDLE;
        sp.timer = 1f + rnd.nextFloat() * 3f;
        if (rnd.nextFloat() < 0.5f) sp.view = MiiArt.FRONT;
    }

    private void endChat(Sprite sp) {
        Sprite o = sp.partner;
        sp.partner = null;
        sp.bubble = null;
        if (sp.state == CHAT || sp.state == CHAT_GO) {
            sp.state = IDLE;
            sp.timer = 0.6f + rnd.nextFloat();
        }
        if (o != null && o.partner == sp) endChat(o);
    }

    private void decide(Sprite sp) {
        if (formation >= 0 || parade) return;
        float x = rnd.nextFloat();
        if (x < 0.56f) {
            randomSpot(pt);
            float dx = pt[0] - sp.x, dz = pt[1] - sp.z, d = (float) Math.hypot(dx, dz);
            if (d > 9f) {
                pt[0] = sp.x + dx / d * 9f;
                pt[1] = sp.z + dz / d * 9f;
            }
            sp.tx = pt[0];
            sp.tz = pt[1];
            sp.state = WALK;
        } else if (x < 0.68f) {
            sp.waveT = 1.6f;
            sp.view = MiiArt.FRONT;
            sp.timer = 2f;
        } else if (x < 0.77f) {
            sp.state = SLEEP;
            sp.view = MiiArt.FRONT;
            sp.timer = 5f + rnd.nextFloat() * 4f;
        } else if (x < 0.92f && startChat(sp)) {
            return;
        } else if (x < 0.96f) {
            sp.hop = 0.01f;
            sp.hopV = 3.6f;
            host.sfxQuiet();
            sp.timer = 1.5f;
        } else {
            sp.view = rnd.nextInt(3);
            sp.timer = 1.5f + rnd.nextFloat() * 2f;
        }
    }

    private boolean startChat(Sprite sp) {
        Sprite best = null;
        float bd = 8f;
        for (Sprite o : sprites) {
            if (o == sp || o.state != IDLE || isHeld(o) || o.partner != null) continue;
            float d = (float) Math.hypot(o.x - sp.x, o.z - sp.z);
            if (d < bd) {
                bd = d;
                best = o;
            }
        }
        if (best == null) return false;
        float mx = (sp.x + best.x) / 2f, mz = (sp.z + best.z) / 2f;
        boolean left = sp.x < best.x;
        sp.partner = best;
        best.partner = sp;
        sp.tx = mx + (left ? -0.8f : 0.8f);
        sp.tz = mz;
        best.tx = mx + (left ? 0.8f : -0.8f);
        best.tz = mz;
        sp.state = CHAT_GO;
        best.state = CHAT_GO;
        return true;
    }

    /** Keeps walkers from standing inside each other. */
    private void separate(float dt) {
        int n = sprites.size();
        for (int i = 0; i < n; i++) {
            Sprite a = sprites.get(i);
            if (!free(a)) continue;
            for (int j = i + 1; j < n; j++) {
                Sprite b = sprites.get(j);
                if (!free(b)) continue;
                float dx = b.x - a.x, dz = b.z - a.z, d = (float) Math.hypot(dx, dz);
                float min = (a.partner == b) ? 1.4f : 1.05f;
                if (d >= min || d < 1e-4f) continue;
                float push = (min - d) * Math.min(1f, dt * 6f) * 0.5f;
                dx /= d;
                dz /= d;
                if (a.state == WALK || a.state == IDLE || a.state == CHAT_GO) {
                    a.x -= dx * push;
                    a.z -= dz * push;
                }
                if (b.state == WALK || b.state == IDLE || b.state == CHAT_GO) {
                    b.x += dx * push;
                    b.z += dz * push;
                }
            }
        }
    }

    private static boolean free(Sprite s) {
        return s.state == WALK || s.state == IDLE || s.state == CHAT_GO || s.state == CHAT || s.state == SLEEP;
    }

    // ---- whistle and parade ---------------------------------------------------------------------------------

    void lineUp(int mode) {
        List<Sprite> list = new ArrayList<>();
        for (Sprite sp : sprites) if (sp.state != POOF && sp.state != CARRIED) list.add(sp);
        if (list.isEmpty()) return;
        final Collator col = Collator.getInstance(Locale.getDefault());
        col.setStrength(Collator.PRIMARY);
        Comparator<Sprite> byName = (a, b) -> col.compare(a.mii.label(), b.mii.label());
        for (Sprite sp : list) endChat(sp);
        MiiFloor.ground(960f, 930f, pt);
        float front = pt[1];
        if (mode == SORT_COLOR) {
            Collections.sort(list, (a, b) -> a.mii.v[Mii.COLOR] != b.mii.v[Mii.COLOR]
                    ? Integer.compare(a.mii.v[Mii.COLOR], b.mii.v[Mii.COLOR]) : byName.compare(a, b));
            List<Integer> colors = new ArrayList<>();
            for (Sprite sp : list) if (!colors.contains(sp.mii.v[Mii.COLOR])) colors.add(sp.mii.v[Mii.COLOR]);
            int[] depth = new int[12];
            for (Sprite sp : list) {
                int ci = colors.indexOf(sp.mii.v[Mii.COLOR]);
                assign(sp, (ci - (colors.size() - 1) / 2f) * 2.0f, front + depth[sp.mii.v[Mii.COLOR]]++ * 2.3f);
            }
        } else if (mode == SORT_GENDER) {
            List<Sprite> boys = new ArrayList<>(), girls = new ArrayList<>();
            for (Sprite sp : list) (sp.mii.female() ? girls : boys).add(sp);
            Collections.sort(boys, byName);
            Collections.sort(girls, byName);
            block(boys, -1, front);
            block(girls, 1, front);
        } else {
            if (mode == SORT_FAV) {
                Collections.sort(list, (a, b) -> a.mii.favorite() != b.mii.favorite()
                        ? (a.mii.favorite() ? -1 : 1) : byName.compare(a, b));
            } else {
                Collections.sort(list, byName);
            }
            int cols = Math.min(8, Math.max(1, (int) Math.ceil(Math.sqrt(list.size() * 1.6))));
            int rows = (list.size() + cols - 1) / cols;
            for (int i = 0; i < list.size(); i++) {
                int rowI = i / cols, c = i % cols;
                int inRow = Math.min(cols, list.size() - rowI * cols);
                float x = (c - (inRow - 1) / 2f) * 2.0f;
                // Alphabetical reads from the back row; favourites stand at the front.
                int depthRow = mode == SORT_FAV ? rowI : rows - 1 - rowI;
                assign(list.get(i), x, front + depthRow * 2.4f);
            }
        }
        formation = mode;
        host.sfx(MiiAudio.WHISTLE);
    }

    private void block(List<Sprite> l, int side, float front) {
        if (l.isEmpty()) return;
        int cols = Math.max(1, Math.min(5, (int) Math.ceil(Math.sqrt(l.size()))));
        for (int i = 0; i < l.size(); i++) {
            int rr = i / cols, c = i % cols;
            float x = side * (1.6f + (side < 0 ? (cols - 1 - c) : c) * 1.9f);
            assign(l.get(i), x, front + rr * 2.3f);
        }
    }

    private void assign(Sprite sp, float x, float z) {
        sp.tx = x;
        sp.tz = z;
        sp.state = FORM;
        sp.closed = false;
        sp.tilt = 0f;
    }

    void scatter() {
        formation = -1;
        for (Sprite sp : sprites) {
            if (sp.state == FORM || sp.state == FORMED) {
                sp.state = IDLE;
                sp.timer = rnd.nextFloat() * 1.4f;
            }
        }
        host.sfx(MiiAudio.WHISTLE);
    }

    void toggleParade() {
        parade = !parade;
        if (parade) {
            formation = -1;
            sortOpen = false;
            editPick = false;
            selected = null;
            focusMii = null;
            focusKind = K_BUTTON;
            focusIndex = B_PARADE;
            List<Sprite> list = new ArrayList<>();
            for (Sprite sp : sprites) if (sp.state != POOF) list.add(sp);
            Collections.shuffle(list, rnd);
            int cols = Math.min(5, Math.max(1, list.size()));
            MiiFloor.ground(960f, 1000f, pt);
            float z0 = pt[1];
            for (int i = 0; i < list.size(); i++) {
                Sprite sp = list.get(i);
                endChat(sp);
                sp.state = PARADE;
                sp.x = (i % cols - (cols - 1) / 2f) * 2.3f;
                sp.z = z0 + (i / cols) * 2.6f;
                sp.appear = 0f;
                sp.closed = false;
                sp.tilt = 0f;
            }
        } else {
            for (Sprite sp : sprites) {
                if (sp.state == PARADE) {
                    sp.state = IDLE;
                    sp.timer = rnd.nextFloat() * 1.5f;
                }
            }
        }
    }

    // ---- keys -------------------------------------------------------------------------------------------

    private void buildItems() {
        items.clear();
        if (sortOpen) {
            for (int i = 0; i < 5; i++) {
                RectF rr = sortRect(i);
                items.add(new MiiUi.Item().set(K_SORT, i, 0, rr.left, rr.top, rr.right, rr.bottom));
            }
        }
        for (int i = 0; i < BUTTONS.length; i++) {
            if (parade && i != B_PARADE) continue;
            if (sortOpen && i == B_SORT) continue;
            float[] b = BUTTONS[i];
            items.add(new MiiUi.Item().set(K_BUTTON, i, 0, b[0] - b[2], b[1] - b[2], b[0] + b[2], b[1] + b[2]));
        }
        if (parade) return;
        for (int i = 0; i < sprites.size(); i++) {
            Sprite sp = sprites.get(i);
            if (sp.state == POOF || sp.state == CARRIED || sp.sc <= 0f) continue;
            float u = sp.sc;
            float top = sp.sy + MiiArt.figureTop(sp.mii) * u, half = 0.55f * u;
            if (sp.sy < 60f || top > 1080f) continue;
            items.add(new MiiUi.Item().set(K_MII, i, 0, sp.sx - half, top, sp.sx + half, sp.sy));
        }
    }

    /** The sort bar along the bottom: the whistle that sends everyone off again, then the four orders. */
    private RectF sortRect(int i) {
        if (i == SORT_CLOSE) {
            r.set(726f - 76f, 949f - 64f, 726f + 76f, 949f + 64f);
        } else {
            float cx = 986f + i * 255f;
            r.set(cx - 97f, 949f - 58f, cx + 97f, 949f + 58f);
        }
        return r;
    }

    private int currentItem() {
        for (int i = 0; i < items.size(); i++) {
            MiiUi.Item it = items.get(i);
            if (it.kind != focusKind) continue;
            if (focusKind == K_MII ? sprites.get(it.a) == focusMii : it.a == focusIndex) return i;
        }
        return -1;
    }

    private void navigate(int dx, int dy) {
        buildItems();
        int cur = currentItem();
        int next;
        if (cur >= 0 && focusKind == K_SORT && dx != 0) {
            next = sortNeighbour(cur, dx);
        } else if (cur >= 0) {
            next = MiiUi.nav(items, cur, dx, dy);
        } else {
            RectF from = new RectF(handX - 20f, handY - 20f, handX + 20f, handY + 20f);
            next = MiiUi.navFrom(items, from, -1, dx, dy);
        }
        if (next < 0) return;
        MiiUi.Item it = items.get(next);
        focusKind = it.kind;
        focusIndex = it.a;
        focusMii = it.kind == K_MII ? sprites.get(it.a) : null;
        if (focusMii != null) focusIndex = -1;
        host.sound(WiiSounds.MOVE);
    }

    /** Left and right along the sort bar stay on the bar, in its drawn order; past its ends, the usual search. */
    private int sortNeighbour(int cur, int dx) {
        float x = items.get(cur).r.centerX();
        int best = -1;
        for (int i = 0; i < items.size(); i++) {
            MiiUi.Item it = items.get(i);
            if (it.kind != K_SORT || i == cur) continue;
            float d = (it.r.centerX() - x) * dx;
            if (d > 0 && (best < 0 || d < (items.get(best).r.centerX() - x) * dx)) best = i;
        }
        return best >= 0 ? best : MiiUi.nav(items, cur, dx, 0);
    }

    boolean carrying() {
        return carried != null;
    }

    void key(int code) {
        if (code == KeyEvent.KEYCODE_BACK) {
            back();
            return;
        }
        int dx = MiiUi.dx(code), dy = MiiUi.dy(code);
        if (carried != null) {
            if (dx < 0) held[0] = true;
            if (dx > 0) held[1] = true;
            if (dy < 0) held[2] = true;
            if (dy > 0) held[3] = true;
            if (MiiUi.isOk(code)) drop();
            return;
        }
        if (dx != 0 || dy != 0) {
            navigate(dx, dy);
            return;
        }
        if (MiiUi.isOk(code)) activate();
    }

    void keyUp(int code) {
        int dx = MiiUi.dx(code), dy = MiiUi.dy(code);
        if (dx < 0) held[0] = false;
        if (dx > 0) held[1] = false;
        if (dy < 0) held[2] = false;
        if (dy > 0) held[3] = false;
    }

    private void activate() {
        if (focusKind == K_SORT) {
            host.sound(WiiSounds.SELECT);
            if (focusIndex == SORT_CLOSE) closeSort();
            else if (focusIndex != formation) lineUp(focusIndex);
            return;
        }
        if (focusKind == K_MII && focusMii != null) {
            Sprite sp = focusMii;
            if (editPick) {
                editPick = false;
                host.hint(null);
                host.sound(WiiSounds.SELECT);
                host.editMii(sp.mii);
            } else if (selected == sp) {
                pickUp(sp);
            } else {
                selected = sp;
                host.sound(WiiSounds.SELECT);
                host.sfxQuiet();
                sp.hop = 0.01f;
                sp.hopV = 3.2f;
            }
            return;
        }
        if (focusKind != K_BUTTON) return;
        switch (focusIndex) {
            case B_EDIT:
                host.sound(WiiSounds.SELECT);
                if (selected != null) {
                    host.editMii(selected.mii);
                } else {
                    editPick = !editPick;
                    host.hint(editPick ? host.str(R.string.mii_edit_pick) : null);
                }
                break;
            case B_NEW:
                host.sound(WiiSounds.SELECT);
                if (sprites.size() >= MiiStore.MAX) host.message(host.str(R.string.mii_full));
                else host.newMii();
                break;
            case B_SORT:
                host.sound(WiiSounds.MENU);
                sortOpen = true;
                focusKind = K_SORT;
                focusIndex = SORT_ABC;
                lineUp(SORT_ABC);
                break;
            case B_REMOTE:
                host.sound(WiiSounds.SELECT);
                host.message(host.str(R.string.mii_remote_none));
                break;
            case B_MAIL:
                host.sound(WiiSounds.SELECT);
                host.message(host.str(R.string.mii_mail_none));
                break;
            case B_HELP:
                host.sound(WiiSounds.MENU);
                host.help();
                break;
            case B_PARADE:
                host.sound(WiiSounds.SELECT);
                toggleParade();
                break;
            case B_MENU:
                host.sound(WiiSounds.MENU);
                host.askExit();
                break;
            case B_TRASH:
                host.sound(WiiSounds.SELECT);
                if (selected != null) askDelete(selected);
                else host.message(host.str(R.string.mii_trash_hint));
                break;
            default:
                break;
        }
    }

    private void pickUp(Sprite sp) {
        endChat(sp);
        carried = sp;
        sp.state = CARRIED;
        selected = null;
        focusMii = null;
        focusKind = 0;
        handTargetFor(sp, pt);
        handX = pt[0];
        handY = Math.max(140f, sp.sy + MiiArt.figureTop(sp.mii) * sp.sc - 26f);
        for (int i = 0; i < 4; i++) held[i] = false;
        host.sfx(MiiAudio.LIFT);
    }

    private void drop() {
        Sprite sp = carried;
        carried = null;
        sp.state = FALL;
        sp.vy = 0f;
        sp.bounces = 0;
        for (int i = 0; i < 4; i++) held[i] = false;
        float[] t = BUTTONS[B_TRASH], e = BUTTONS[B_EDIT];
        if (Math.hypot(handX - t[0], handY - t[1]) < t[2] * 1.4f) {
            MiiFloor.ground(t[0] + (t[0] < 960f ? 220f : -220f), t[1] + 60f, pt);
            sp.x = pt[0];
            sp.z = pt[1];
            askDelete(sp);
        } else if (Math.hypot(handX - e[0], handY - e[1]) < e[2] * 1.4f) {
            MiiFloor.ground(e[0] + 220f, e[1] + 60f, pt);
            sp.x = pt[0];
            sp.z = pt[1];
            host.editMii(sp.mii);
        }
        focusMii = sp;
        focusKind = K_MII;
    }

    private void closeSort() {
        sortOpen = false;
        if (formation >= 0) scatter();
        focusKind = K_BUTTON;
        focusIndex = B_SORT;
    }

    private void askDelete(Sprite sp) {
        host.confirmDelete(sp.mii, () -> {
            sp.state = POOF;
            sp.poofT = 0f;
            if (selected == sp) selected = null;
            if (focusMii == sp) {
                focusMii = null;
                focusKind = K_BUTTON;
                focusIndex = B_TRASH;
            }
            host.sfx(MiiAudio.POOF);
        });
    }

    private void back() {
        if (carried != null) {
            drop();
            return;
        }
        if (sortOpen) {
            closeSort();
            host.sound(WiiSounds.BACK);
            return;
        }
        if (editPick) {
            editPick = false;
            host.hint(null);
            host.sound(WiiSounds.BACK);
            return;
        }
        if (parade) {
            toggleParade();
            host.sound(WiiSounds.BACK);
            return;
        }
        if (selected != null) {
            selected = null;
            host.sound(WiiSounds.BACK);
            return;
        }
        host.askExit();
    }

    // ---- hand -------------------------------------------------------------------------------------------

    private void handTargetFor(Sprite sp, float[] out) {
        out[0] = sp.sx + 0.22f * sp.sc;
        out[1] = sp.sy - 1.7f * sp.sc;
    }

    void handTarget(float[] out) {
        if (carried != null) {
            out[0] = handX;
            out[1] = handY;
            return;
        }
        if (focusKind == K_MII && focusMii != null && focusMii.sc > 0f) {
            handTargetFor(focusMii, out);
        } else if (focusKind == K_BUTTON) {
            float[] b = BUTTONS[focusIndex];
            out[0] = b[0] + b[2] * 0.32f;
            out[1] = b[1] + b[2] * 0.38f;
        } else if (focusKind == K_SORT) {
            RectF rr = sortRect(focusIndex);
            out[0] = rr.left + rr.width() * 0.7f;
            out[1] = rr.top + rr.height() * 0.68f;
        } else {
            out[0] = handX;
            out[1] = handY;
            return;
        }
        handX = out[0];
        handY = out[1];
    }

    // ---- drawing -----------------------------------------------------------------------------------------

    void draw(Canvas c) {
        float s = ui.s;
        if (floor != null) {
            r.set(0, 0, 1920f * s, 1080f * s);
            c.drawBitmap(floor, null, r, bmp);
        } else {
            c.drawColor(0xFFFCFCFA);
        }
        order.clear();
        for (Sprite sp : sprites) {
            float lift = sp.lift + sp.hop;
            MiiFloor.project(sp.x, sp.z, pt);
            sp.sx = pt[0];
            sp.sy = pt[1];
            sp.sc = pt[2];
            if (sp != carried && lift >= 0f) order.add(sp);
        }
        Collections.sort(order, (a, b) -> Float.compare(b.z, a.z));
        for (Sprite sp : order) drawSprite(c, sp);
        if (carried != null) drawSprite(c, carried);
        for (Sprite sp : order) drawExtras(c, sp);
        drawButtons(c);
    }

    private void drawSprite(Canvas c, Sprite sp) {
        float s = ui.s;
        if (sp.sy < -200f || sp.sy > 1500f || sp.appear <= 0.01f) return;
        if (!sp.ready) renderHeads(sp, art);
        Mii m = sp.mii;
        float u = sp.sc * s * (1f + 0.12f * sp.hover) * (0.6f + 0.4f * easeOut(sp.appear));
        float sx = sp.sx * s, sy = sp.sy * s;
        float lift = sp.lift + sp.hop;
        float ws = MiiArt.weightScale(m);
        float shW = u * 1.25f * ws * (1f - Math.min(0.4f, lift * 0.06f));
        float shH = u * 0.34f * (1f - Math.min(0.4f, lift * 0.06f));
        if (sp.select > 0.01f) {
            ui.stroke.setColor(MiiArt.alpha(MiiUi.BLUE, (int) (220 * sp.select)));
            ui.stroke.setStrokeWidth(u * 0.07f);
            r.set(sx - shW * 0.62f, sy - shH * 0.62f, sx + shW * 0.62f, sy + shH * 0.62f);
            c.drawOval(r, ui.stroke);
        }
        r.set(sx - shW / 2f, sy - shH / 2f, sx + shW / 2f, sy + shH / 2f);
        bmp.setAlpha(Math.round(255 * sp.appear));
        c.drawBitmap(shadow, null, r, bmp);
        int save = c.save();
        c.translate(sx, sy - lift * u);
        c.scale(u, u);
        MiiArt.Pose p = sp.pose.reset();
        p.view = sp.view;
        p.phase = sp.phase;
        p.walk = sp.walk;
        p.wave = sp.wave;
        p.wavePhase = sp.wavePhase;
        p.dangle = sp.state == CARRIED ? 1f : 0f;
        if (sp.state == FALL && sp.lift > 0.3f) p.dangle = 0.6f;
        MiiArt.place(m, p);
        if (p.view != MiiArt.BACK && sp.backs[p.view] != null) headBmp(c, sp.backs[p.view], p, sp.tilt, BB_T);
        art.body(c, m, p);
        int hi = p.view == MiiArt.FRONT && sp.closed ? 4 : p.view;
        headBmp(c, sp.heads[hi], p, sp.tilt, HB_T);
        c.restoreToCount(save);
        bmp.setAlpha(255);
        if (sp.state == POOF && ui.sprites != null) {
            float t = Math.min(1f, sp.poofT / 0.55f);
            float k = u * (1.7f + 1.1f * easeOut(t)) / 60f;
            ui.sprites.draw(c, "poof", sx, sy - u * 1.4f, k, k, MiiUiSprites.NONE, 0, 0, (int) (255 * (1f - t * t)));
        } else if (sp.state == POOF) {
            float t = sp.poofT / 0.55f;
            ui.fill.setColor(MiiArt.alpha(0xFFFFFFFF, (int) (230 * (1f - t))));
            for (int k = 0; k < 7; k++) {
                double a = k * Math.PI * 2 / 7;
                float d = u * (0.4f + t * 1.1f);
                c.drawCircle(sx + (float) Math.cos(a) * d, sy - u * 1.4f + (float) Math.sin(a) * d * 0.7f,
                        u * (0.35f + 0.25f * t), ui.fill);
            }
        }
    }

    private static float easeOut(float t) {
        return 1f - (1f - t) * (1f - t);
    }

    private void headBmp(Canvas c, Bitmap b, MiiArt.Pose p, float tilt, float top) {
        if (b == null) return;
        int save = c.save();
        c.translate(p.headX, p.headY);
        if (tilt != 0f) c.rotate(tilt, 0f, 0.6f);
        c.scale(1f / RES, 1f / RES);
        c.drawBitmap(b, HB_L * RES, top * RES, bmp);
        c.restoreToCount(save);
    }

    /** Name tags, chat bubbles and the sleepers' Zs, drawn over everyone. */
    private void drawExtras(Canvas c, Sprite sp) {
        float s = ui.s;
        if (sp.state == POOF) return;
        float u = sp.sc * (1f + 0.12f * sp.hover);
        float topY = sp.sy - (sp.lift + sp.hop) * u + MiiArt.figureTop(sp.mii) * u;
        if (sp.hover > 0.02f) {
            String name = sp.mii.label();
            String by = sp.mii.creator.isEmpty() ? null : host.str(R.string.mii_by, sp.mii.creator);
            ui.text.setTypeface(ui.bold);
            ui.text.setTextSize(40f * s);
            float tw = ui.text.measureText(name) / s;
            float bw = tw;
            if (by != null) {
                ui.text.setTextSize(26f * s);
                bw = Math.max(bw, ui.text.measureText(by) / s);
            }
            float w = bw + 60f, h = by != null ? 104f : 74f;
            float cx = sp.sx, bottom = topY - 16f;
            float a = sp.hover;
            int save = c.saveLayerAlpha(0, 0, c.getWidth(), c.getHeight(), Math.round(255 * a));
            RectF rr = tag;
            rr.set((cx - w / 2f) * s, (bottom - h) * s, (cx + w / 2f) * s, bottom * s);
            ui.fill.setColor(0x30000000);
            rr.offset(0, 5f * s);
            c.drawRoundRect(rr, 24f * s, 24f * s, ui.fill);
            rr.offset(0, -5f * s);
            ui.box(c, rr, 24f * s, 0xFFFFFFFF, 0xFFE9F0F0, sp == selected ? MiiUi.BLUE : 0xFF9AAAAA, 4f * s);
            ui.path.reset();
            ui.path.moveTo((cx - 14f) * s, bottom * s - 2f * s);
            ui.path.lineTo(cx * s, (bottom + 16f) * s);
            ui.path.lineTo((cx + 14f) * s, bottom * s - 2f * s);
            ui.fill.setColor(0xFFE9F0F0);
            c.drawPath(ui.path, ui.fill);
            ui.label(c, name, cx, bottom - h + 52f, 40f, 0xFF4A5252, Paint.Align.CENTER, true);
            if (by != null) ui.label(c, by, cx, bottom - 20f, 26f, 0xFF8A9494, Paint.Align.CENTER, false);
            c.restoreToCount(save);
        }
        if (sp.bubble != null && sp.hover < 0.02f) {
            float cx = sp.sx + (sp.view == MiiArt.LEFT ? -0.4f : 0.4f) * u, by = topY - 8f;
            float bw = 70f, bh = 56f;
            RectF rr = tag;
            rr.set((cx - bw / 2f) * s, (by - bh) * s, (cx + bw / 2f) * s, by * s);
            ui.box(c, rr, bh * 0.5f * s, 0xFFFFFFFF, 0xFFF0F4F4, 0xFFA6B2B2, 3f * s);
            ui.label(c, sp.bubble, cx, by - 16f, 34f, 0xFF607070, Paint.Align.CENTER, true);
        }
        if (sp.state == SLEEP) {
            float t = (sp.timer * 0.9f) % 1.5f;
            for (int k = 0; k < 3; k++) {
                float ph = ((1.5f - t) / 1.5f + k / 3f) % 1f;
                float zx = sp.sx + (0.35f + ph * 0.5f) * u, zy = topY + 0.3f * u - ph * 1.1f * u;
                int al = (int) (255 * Math.sin(ph * Math.PI));
                float size = (22f + ph * 22f) * Math.max(0.6f, u / 50f);
                if (ui.sprites != null) {
                    float zk = size * 1.1f / 30f * s;
                    ui.sprites.draw(c, "z", zx * s, (zy - size * 0.4f) * s, zk, zk, MiiUiSprites.NONE, 0, 0, al);
                } else {
                    ui.label(c, "z", zx, zy, size, MiiArt.alpha(0xFF7A8A8A, al), Paint.Align.CENTER, true);
                }
            }
        }
    }

    private void drawButtons(Canvas c) {
        if (uiAlpha > 0.01f || parade) {
            boolean layer = uiAlpha < 0.999f;
            int save = layer ? c.saveLayerAlpha(0, 0, c.getWidth(), c.getHeight(), Math.round(255 * uiAlpha)) : c.save();
            for (int i = 0; i < BUTTONS.length; i++) {
                if (i == B_PARADE || (i == B_SORT && sortOpen)) continue;
                float[] b = BUTTONS[i];
                ui.plazaButton(c, ICONS[i], b[0], b[1], b[2], btnLift[i], i == B_EDIT && editPick);
            }
            c.restoreToCount(save);
        }
        float[] b = BUTTONS[B_PARADE];
        ui.plazaButton(c, MiiUi.ICON_PARADE, b[0], b[1], b[2], btnLift[B_PARADE], parade);
        if (sortOpen) {
            int count = 0;
            for (Sprite sp : sprites) if (sp.state != POOF) count++;
            ui.countBubble(c, 490f, 967f, count);
            for (int i = 0; i < 5; i++) ui.darkPill(c, sortRect(i), SORT_ICONS[i], sortLift[i]);
        }
    }
}
