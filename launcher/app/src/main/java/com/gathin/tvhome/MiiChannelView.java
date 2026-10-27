package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Process;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * The Mii channel: the plaza or the maker, the dialogs, keyboard and look-alike picker over them, the
 * pointing hand following the focus, and a white fade between screens. Keeps the saved characters, the
 * music and the interface sounds.
 */
final class MiiChannelView extends View {

    interface Typed {
        /** The text entered, or null when the keyboard was closed with Back. */
        void done(String text);
    }

    private static final int WHITE = 0xFFFCFCFA;

    private static final String[] KB_ROWS = {"1234567890", "qwertyuiop", "asdfghjkl\u00F1", "zxcvbnm\u00B4-."};
    private static final char ACCENT = '\u00B4';
    private static final float KB_W = 116f, KB_H = 96f, KB_GAP = 12f;
    private static final float KB_X0 = (1920f - (10 * KB_W + 9 * KB_GAP)) / 2f, KB_Y0 = 396f;
    private static final int KB_CHAR = 1, KB_SHIFT = 2, KB_SPACE = 3, KB_DEL = 4, KB_OK = 5;

    private static final float LA_W = 300f, LA_H = 300f, LA_GAP_X = 36f, LA_GAP_Y = 32f, LA_Y0 = 206f;
    private static final float LA_X0 = (1920f - (4 * LA_W + 3 * LA_GAP_X)) / 2f;
    private static final int LA_TILE = 1, LA_MORE = 2, LA_BACK = 3;

    private final Runnable onExit;
    private final MiiStore store;
    private final MiiUi ui;
    private final MiiArt art = new MiiArt();
    private final MiiPlaza plaza;
    private final MiiAudio audio;
    private final WiiMusic music;
    private final WiiSounds sounds;
    private final MiiPackSounds packSounds;
    private final Random rnd = new Random();
    private final List<MiiUi.Overlay> overlays = new ArrayList<>();
    private final float[] target = new float[2];
    private final RectF rr = new RectF();
    private final Paint veil = new Paint();
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

    private MiiEditor editor;
    /** The saved character the editor is changing a copy of; null for a new one. */
    private Mii original;
    private boolean ready, running;
    private long lastNanos;
    private float handX = 960f, handY = 620f;
    private boolean handPlaced;
    private float fade = 1f;
    private int fadeDir;
    private boolean fadeBack;
    private Runnable fadeMid;
    private String hint, hintShown;
    private float hintAlpha;
    private int floorGen;
    /** The pack's floor tile, 256 x 256 ARGB, or null to draw the squares. */
    private int[] floorTile;
    private float s = 1f;

    MiiChannelView(Context context, Runnable onExit) {
        super(context);
        this.onExit = onExit;
        MiiPack pack = MiiPack.get(context);
        if (pack != null) MiiPlaza.usePack(pack);
        Typeface regular;
        try {
            regular = context.getResources().getFont(R.font.outfit_medium);
        } catch (RuntimeException e) {
            regular = Typeface.SANS_SERIF;
        }
        ui = new MiiUi(WiiMenuView.weight(context, regular, 700), regular);
        ui.useSprites(MiiUiSprites.from(pack));
        Bitmap tile = ui.sprites == null ? null : ui.sprites.bitmap("floor");
        if (tile != null && tile.getWidth() == 256 && tile.getHeight() == 256) {
            floorTile = new int[256 * 256];
            tile.getPixels(floorTile, 0, 256, 0, 0, 256, 256);
        }
        plaza = new MiiPlaza(this, ui, art);
        boolean sound = context.getResources().getBoolean(R.bool.wii_sounds);
        audio = new MiiAudio(sound);
        // The Plaza's music sits under its effects, at 40 % of the level set for the menu music.
        int musicVolume = Math.max(0, Math.min(100, context.getResources().getInteger(R.integer.wii_music_volume)));
        music = new WiiMusic(context, context.getResources().getBoolean(R.bool.wii_music), 40 * musicVolume / 100,
                () -> audio.setMusic(true));
        music.setTrack(R.raw.wii_music_plaza);
        audio.setMusic(!music.hasTrack());
        sounds = new WiiSounds(context, sound,
                WiiSounds.MOVE, WiiSounds.SELECT, WiiSounds.PAGE, WiiSounds.BACK, WiiSounds.MENU);
        packSounds = sound && pack != null ? new MiiPackSounds(context, pack) : null;
        store = new MiiStore(context);
        plaza.setMiis(store.load());
        setFocusable(true);
        setFocusableInTouchMode(true);
        final List<MiiPlaza.Sprite> list = new ArrayList<>(plaza.sprites);
        new Thread(() -> {
            MiiPlaza.renderHeads(list, new MiiArt());
            post(this::onReady);
        }, "mii-heads").start();
    }

    private void onReady() {
        ready = true;
        fade = 1f;
        fadeDir = -1;
        if (!store.welcomed()) {
            push(ui.new Dialog(str(R.string.mii_welcome_title), str(R.string.mii_welcome_body),
                    new String[] {str(R.string.mii_ok)}, null, 0, i -> {
                        pop();
                        store.markWelcomed();
                        sound(WiiSounds.SELECT);
                    }));
        }
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;
        s = w / 1920f;
        ui.setScale(s);
        final int gen = ++floorGen;
        new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            int[] px = MiiFloor.render(w, h, floorTile);
            Bitmap b = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
            post(() -> {
                if (gen == floorGen) plaza.setFloor(b);
            });
        }, "mii-floor").start();
    }

    // ---- lifecycle --------------------------------------------------------------------------------------

    void resume() {
        running = true;
        lastNanos = 0;
        audio.setCalm(editor != null);
        audio.resume();
        music.setTrack(editor != null ? R.raw.wii_music_editor : R.raw.wii_music_plaza);
        audio.setMusic(!music.hasTrack());
        music.resume();
        invalidate();
    }

    void pause() {
        running = false;
        audio.pause();
        music.pause();
    }

    void release() {
        audio.release();
        music.release();
        sounds.release();
        if (packSounds != null) packSounds.release();
    }

    // ---- what the plaza and the maker ask of the channel -------------------------------------------------

    void sfx(int id) {
        if (packSounds != null && packSounds.sfx(id)) return;
        audio.sfx(id);
    }

    void sfxQuiet() {
        sfx(MiiAudio.HOP);
    }

    void sound(int which) {
        if (packSounds != null && packSounds.ui(which)) return;
        sounds.play(which);
    }

    String str(int id, Object... args) {
        return args.length == 0 ? getContext().getString(id) : getContext().getString(id, args);
    }

    void plazaChanged() {
        store.save(plaza.miis());
    }

    void hint(String text) {
        hint = text;
    }

    void message(String text) {
        push(ui.new Dialog(str(R.string.mii_channel_name), text, new String[] {str(R.string.mii_ok)}, null, 0,
                i -> {
                    pop();
                    sound(WiiSounds.BACK);
                }));
    }

    void help() {
        push(ui.new Dialog(str(R.string.mii_help_title), str(R.string.mii_help_body),
                new String[] {str(R.string.mii_ok)}, null, 0, i -> {
                    pop();
                    sound(WiiSounds.BACK);
                }));
    }

    void askExit() {
        push(ui.new Dialog(str(R.string.mii_exit_title), str(R.string.mii_exit_body),
                new String[] {str(R.string.mii_yes), str(R.string.mii_no)}, null, 1, i -> {
                    pop();
                    if (i == 0) {
                        sound(WiiSounds.SELECT);
                        fadeTo(onExit, false);
                    } else {
                        sound(WiiSounds.BACK);
                    }
                }));
    }

    void confirmDelete(Mii m, Runnable yes) {
        push(ui.new Dialog(str(R.string.mii_delete_title), str(R.string.mii_delete_body, m.label()),
                new String[] {str(R.string.mii_delete), str(R.string.mii_cancel)}, null, 1, i -> {
                    pop();
                    if (i == 0) {
                        sound(WiiSounds.SELECT);
                        yes.run();
                    } else {
                        sound(WiiSounds.BACK);
                    }
                }));
    }

    void editMii(Mii m) {
        original = m;
        openEditor(m.copy(), false);
    }

    /** New Mii: boy or girl, then from scratch or from a look-alike. */
    void newMii() {
        push(ui.new Dialog(str(R.string.mii_new_title), str(R.string.mii_new_gender),
                new String[] {str(R.string.mii_boy), str(R.string.mii_girl)},
                new int[] {MiiUi.ICON_BOY, MiiUi.ICON_GIRL}, 2, i -> {
                    pop();
                    if (i > 1) {
                        sound(WiiSounds.BACK);
                        return;
                    }
                    sound(WiiSounds.SELECT);
                    askStart(i == 1);
                }));
    }

    private void askStart(boolean female) {
        push(ui.new Dialog(str(R.string.mii_new_title), str(R.string.mii_new_how),
                new String[] {str(R.string.mii_scratch), str(R.string.mii_lookalike)}, null, 2, i -> {
                    pop();
                    if (i > 1) {
                        sound(WiiSounds.BACK);
                        newMii();
                        return;
                    }
                    sound(WiiSounds.SELECT);
                    if (i == 0) {
                        original = null;
                        openEditor(Mii.blank(female), true);
                    } else {
                        push(new LookAlike(female));
                    }
                }));
    }

    void editorQuit() {
        if (editor == null) return;
        if (!editor.isNew && original != null && same(original, editor.mii)) {
            closeEditor(null);
            return;
        }
        push(ui.new Dialog(str(R.string.mii_quit_title), null, new String[] {str(R.string.mii_save_quit),
                str(R.string.mii_quit_nosave), str(R.string.mii_keep_editing)}, null, 2, i -> {
                    pop();
                    if (i == 0) {
                        sound(WiiSounds.SELECT);
                        closeEditor(editor.mii);
                    } else if (i == 1) {
                        sound(WiiSounds.SELECT);
                        closeEditor(null);
                    } else {
                        sound(WiiSounds.BACK);
                    }
                }));
    }

    void keyboard(String prompt, String initial, Typed done) {
        push(new Keyboard(prompt, initial, done));
    }

    private static boolean same(Mii a, Mii b) {
        return Arrays.equals(a.v, b.v) && a.name.equals(b.name) && a.creator.equals(b.creator);
    }

    private void openEditor(Mii m, boolean isNew) {
        hint = null;
        fadeTo(() -> {
            editor = new MiiEditor(this, ui, art, m, isNew);
            audio.setCalm(true);
            music.setTrack(R.raw.wii_music_editor);
            audio.setMusic(!music.hasTrack());
            handPlaced = false;
        }, true);
    }

    private void closeEditor(Mii saved) {
        final boolean isNew = editor.isNew;
        if (saved != null && saved.name.trim().isEmpty()) saved.name = Mii.random(rnd, saved.female()).name;
        fadeTo(() -> {
            editor = null;
            original = null;
            audio.setCalm(false);
            music.setTrack(R.raw.wii_music_plaza);
            audio.setMusic(!music.hasTrack());
            handPlaced = false;
            if (saved != null) {
                if (isNew) plaza.addNew(saved);
                else plaza.replace(saved);
                store.save(plaza.miis());
            }
        }, true);
    }

    private void fadeTo(Runnable mid, boolean back) {
        fadeMid = mid;
        fadeBack = back;
        fadeDir = 1;
    }

    private void push(MiiUi.Overlay o) {
        overlays.add(o);
    }

    private void pop() {
        if (!overlays.isEmpty()) overlays.remove(overlays.size() - 1);
    }

    private MiiUi.Overlay top() {
        return overlays.isEmpty() ? null : overlays.get(overlays.size() - 1);
    }

    // ---- keys ---------------------------------------------------------------------------------------------

    private static boolean channelKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_DEL:
                return true;
            default:
                return MiiUi.isOk(code);
        }
    }

    /** A key press; unicode is what the key types, for the keyboard. False for keys the channel leaves alone. */
    boolean keyDown(int code, int repeat, int unicode) {
        MiiUi.Overlay top = top();
        boolean typing = top instanceof Keyboard && unicode > 0 && !channelKey(code);
        if (!channelKey(code) && !typing) return false;
        if (!ready || fadeDir != 0) return true;
        if (repeat > 0 && (MiiUi.isOk(code) || code == KeyEvent.KEYCODE_BACK)) return true;
        if (top != null) {
            if (typing) ((Keyboard) top).type((char) unicode);
            else top.key(code);
            if (top instanceof MiiUi.Dialog && ((MiiUi.Dialog) top).moved) {
                ((MiiUi.Dialog) top).moved = false;
                sound(WiiSounds.MOVE);
            }
            return true;
        }
        if (editor != null) editor.key(code);
        else plaza.key(code);
        return true;
    }

    boolean keyUp(int code) {
        plaza.keyUp(code);
        return channelKey(code);
    }

    // ---- frame --------------------------------------------------------------------------------------------

    private void step(float dt) {
        if (fadeDir > 0) {
            fade = Math.min(1f, fade + dt / 0.22f);
            if (fade >= 1f) {
                Runnable mid = fadeMid;
                fadeMid = null;
                fadeDir = fadeBack ? -1 : 0;
                if (mid != null) mid.run();
            }
        } else if (fadeDir < 0) {
            fade = Math.max(0f, fade - dt / 0.32f);
            if (fade <= 0f) fadeDir = 0;
        }
        if (!ready) return;
        if (editor != null) editor.step(dt);
        else plaza.step(dt);
        for (int i = 0; i < overlays.size(); i++) overlays.get(i).step(dt);
        if (hint != null) hintShown = hint;
        hintAlpha = MiiUi.approach(hintAlpha, hint != null && editor == null ? 1f : 0f,
                1f - (float) Math.exp(-dt / 0.1f), 0.004f);

        MiiUi.Overlay top = top();
        if (top != null) top.handTarget(target);
        else if (editor != null) editor.handTarget(target);
        else plaza.handTarget(target);
        boolean snap = !handPlaced || (top == null && editor == null && plaza.carrying());
        float k = snap ? 1f : 1f - (float) Math.exp(-dt / 0.055f);
        handX += (target[0] - handX) * k;
        handY += (target[1] - handY) * k;
        handPlaced = true;
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 1f / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        step(dt);
        if (!ready) {
            c.drawColor(WHITE);
        } else {
            if (editor != null) editor.draw(c);
            else plaza.draw(c);
            if (hintAlpha > 0.01f && hintShown != null) drawHint(c);
            for (int i = 0; i < overlays.size(); i++) overlays.get(i).draw(c);
            ui.hand.grabbing = editor == null && top() == null && plaza.carrying();
            ui.hand.draw(c, handX * s, handY * s);
        }
        if (fade > 0.002f) {
            veil.setColor(WHITE);
            veil.setAlpha(Math.round(255 * fade));
            c.drawRect(0, 0, getWidth(), getHeight(), veil);
        }
        if (running) postInvalidateOnAnimation();
        else lastNanos = 0;
    }

    private void drawHint(Canvas c) {
        ui.text.setTypeface(ui.bold);
        ui.text.setTextSize(38f * s);
        float w = ui.text.measureText(hintShown) / s + 100f, h = 84f, cx = 960f, t = 958f;
        int save = hintAlpha < 0.995f
                ? c.saveLayerAlpha(0, 0, getWidth(), getHeight(), Math.round(255 * hintAlpha)) : c.save();
        c.translate(0f, (1f - hintAlpha) * 30f * s);
        rr.set((cx - w / 2f) * s, t * s, (cx + w / 2f) * s, (t + h) * s);
        ui.fill.setColor(0x30000000);
        rr.offset(0f, 6f * s);
        c.drawRoundRect(rr, h / 2f * s, h / 2f * s, ui.fill);
        rr.offset(0f, -6f * s);
        ui.box(c, rr, h / 2f * s, 0xFFFFFFFF, 0xFFE6EEEE, MiiUi.SLATE, 4f * s);
        ui.label(c, hintShown, cx, t + h / 2f + 14f, 38f, 0xFF4E5E5E, Paint.Align.CENTER, true);
        c.restoreToCount(save);
    }

    // ---- on-screen keyboard -----------------------------------------------------------------------------

    /** The maker's keyboard for the nickname and creator: a text field over a grid of keys. */
    private final class Keyboard implements MiiUi.Overlay {
        private final String prompt;
        private final Typed done;
        private final StringBuilder text;
        private final List<MiiUi.Item> keys = new ArrayList<>();
        private final float[] lift;
        private int focus = 10;
        private boolean upper, accent;
        private float appear, time;

        Keyboard(String prompt, String initial, Typed done) {
            this.prompt = prompt;
            this.done = done;
            text = new StringBuilder(initial == null ? "" : initial);
            upper = text.length() == 0;
            for (int row = 0; row < KB_ROWS.length; row++) {
                for (int col = 0; col < 10; col++) {
                    float l = KB_X0 + col * (KB_W + KB_GAP), t = KB_Y0 + row * (KB_H + KB_GAP);
                    keys.add(new MiiUi.Item().set(KB_CHAR, row, col, l, t, l + KB_W, t + KB_H));
                }
            }
            float t = KB_Y0 + KB_ROWS.length * (KB_H + KB_GAP);
            int[] kinds = {KB_SHIFT, KB_SPACE, KB_DEL, KB_OK};
            int[] span = {2, 4, 2, 2};
            int col = 0;
            for (int i = 0; i < kinds.length; i++) {
                float l = KB_X0 + col * (KB_W + KB_GAP);
                keys.add(new MiiUi.Item().set(kinds[i], 0, 0, l, t, l + span[i] * KB_W + (span[i] - 1) * KB_GAP,
                        t + KB_H));
                col += span[i];
            }
            lift = new float[keys.size()];
        }

        private char charOf(MiiUi.Item it) {
            char ch = KB_ROWS[it.a].charAt(it.b);
            return upper ? Character.toUpperCase(ch) : ch;
        }

        @Override
        public void step(float dt) {
            time += dt;
            appear = MiiUi.approach(appear, 1f, 1f - (float) Math.exp(-dt / 0.09f), 0.003f);
            float k = 1f - (float) Math.exp(-dt / 0.06f);
            for (int i = 0; i < lift.length; i++) lift[i] = MiiUi.approach(lift[i], i == focus ? 1f : 0f, k, 0.003f);
        }

        @Override
        public void draw(Canvas c) {
            int save = ui.dim(c, appear);
            ui.darkPanel(c, new RectF(ui.sr(250f, 96f, 1670f, 968f)), prompt);
            RectF f = new RectF(ui.sr(KB_X0, 250f, 1920f - KB_X0, 352f));
            ui.box(c, f, 24f * s, 0xFFFFFFFF, 0xFFE6EEEE, 0xFF2A3436, 4f * s);
            String str = text.toString();
            ui.label(c, str, KB_X0 + 36f, 320f, 54f, 0xFF333B3D, Paint.Align.LEFT, true);
            float cx = KB_X0 + 40f + ui.text.measureText(str) / s;
            if (((int) (time * 2.4f)) % 2 == 0) {
                ui.fill.setColor(MiiUi.BLUE);
                c.drawRect(ui.sr(cx, 268f, cx + 6f, 334f), ui.fill);
            }
            ui.label(c, text.length() + "/" + Mii.MAX_NAME, 1920f - KB_X0 - 32f, 316f, 32f, 0xFF8A9696,
                    Paint.Align.RIGHT, true);
            for (int i = 0; i < keys.size(); i++) {
                if (i != focus) drawKey(c, keys.get(i), lift[i]);
            }
            drawKey(c, keys.get(focus), lift[focus]);
            c.restoreToCount(save);
        }

        private void drawKey(Canvas c, MiiUi.Item it, float lf) {
            RectF b = new RectF(ui.sr(it.r));
            float sc = 1f + 0.07f * lf;
            int sv = c.save();
            c.scale(sc, sc, b.centerX(), b.centerY());
            boolean on = (it.kind == KB_SHIFT && upper)
                    || (it.kind == KB_CHAR && KB_ROWS[it.a].charAt(it.b) == ACCENT && accent);
            int top = on ? 0xFFC8F1FF : it.kind == KB_OK ? 0xFFEFFFF7 : 0xFFF8FCFC;
            int bottom = on ? 0xFF86D3F0 : it.kind == KB_OK ? 0xFFB6E3C9 : 0xFFC4D0D0;
            ui.box(c, b, 20f * s, top, bottom, 0xFF2A3436, 3.5f * s);
            ui.glow(c, b, 20f * s, lf);
            String label;
            switch (it.kind) {
                case KB_SHIFT:
                    label = str(R.string.mii_kb_shift);
                    break;
                case KB_SPACE:
                    label = str(R.string.mii_kb_space);
                    break;
                case KB_DEL:
                    label = str(R.string.mii_kb_delete);
                    break;
                case KB_OK:
                    label = str(R.string.mii_ok);
                    break;
                default:
                    label = String.valueOf(charOf(it));
                    break;
            }
            float size = it.kind == KB_CHAR ? 50f : 38f;
            ui.label(c, label, b.centerX() / s, b.centerY() / s + size * 0.36f, size, 0xFF3A4446,
                    Paint.Align.CENTER, true);
            c.restoreToCount(sv);
        }

        @Override
        public void key(int code) {
            if (code == KeyEvent.KEYCODE_BACK) {
                finish(null);
                return;
            }
            int dx = MiiUi.dx(code), dy = MiiUi.dy(code);
            if (dx != 0 || dy != 0) {
                int n = gridNav(dx, dy);
                if (n >= 0) {
                    focus = n;
                    sound(WiiSounds.MOVE);
                }
                return;
            }
            if (code == KeyEvent.KEYCODE_DEL) {
                delete();
                return;
            }
            if (!MiiUi.isOk(code)) return;
            MiiUi.Item it = keys.get(focus);
            switch (it.kind) {
                case KB_CHAR:
                    if (KB_ROWS[it.a].charAt(it.b) == ACCENT) {
                        accent = !accent;
                        sound(WiiSounds.SELECT);
                    } else {
                        type(KB_ROWS[it.a].charAt(it.b));
                    }
                    break;
                case KB_SHIFT:
                    upper = !upper;
                    sound(WiiSounds.SELECT);
                    break;
                case KB_SPACE:
                    type(' ');
                    break;
                case KB_DEL:
                    delete();
                    break;
                case KB_OK:
                    finish(text.toString());
                    break;
                default:
                    break;
            }
        }

        /**
         * Keys move along their row, and up or down to the key under the same spot of the next row; the
         * nearest-rectangle rule would jump from the wide bottom keys to the letters above.
         */
        private int gridNav(int dx, int dy) {
            RectF cur = keys.get(focus).r;
            float cx = cur.centerX();
            float rowTop = cur.top + dy * (KB_H + KB_GAP);
            int best = -1;
            float bestD = Float.MAX_VALUE;
            for (int i = 0; i < keys.size(); i++) {
                RectF b = keys.get(i).r;
                if (Math.abs(b.top - rowTop) > 1f || i == focus) continue;
                float d;
                if (dx != 0) {
                    d = (b.centerX() - cx) * dx;
                    if (d <= 0f) continue;
                } else {
                    d = b.left <= cx && cx <= b.right ? 0f : Math.min(Math.abs(b.left - cx), Math.abs(b.right - cx));
                }
                if (d < bestD) {
                    bestD = d;
                    best = i;
                }
            }
            return best;
        }

        void type(char ch) {
            if (Character.isISOControl(ch)) return;
            if (text.length() >= Mii.MAX_NAME || (ch == ' ' && text.length() == 0)) {
                sound(WiiSounds.BACK);
                return;
            }
            if (accent) {
                int v = "aeiou".indexOf(Character.toLowerCase(ch));
                if (v >= 0) {
                    boolean up = Character.isUpperCase(ch);
                    ch = "\u00E1\u00E9\u00ED\u00F3\u00FA".charAt(v);
                    if (up) ch = Character.toUpperCase(ch);
                }
                accent = false;
            }
            if (upper && Character.isLetter(ch)) {
                ch = Character.toUpperCase(ch);
                upper = false;
            }
            text.append(ch);
            sound(WiiSounds.SELECT);
        }

        private void delete() {
            if (text.length() == 0) {
                sound(WiiSounds.BACK);
                return;
            }
            text.setLength(text.length() - 1);
            if (text.length() == 0) upper = true;
            sound(WiiSounds.MOVE);
        }

        private void finish(String result) {
            overlays.remove(this);
            sound(result == null ? WiiSounds.BACK : WiiSounds.SELECT);
            done.done(result);
        }

        @Override
        public void handTarget(float[] out) {
            RectF b = keys.get(focus).r;
            out[0] = b.left + b.width() * (b.width() > 300f ? 0.8f : 0.7f);
            out[1] = b.top + b.height() * 0.72f;
        }
    }

    // ---- look-alikes --------------------------------------------------------------------------------------

    /** Eight random faces to start a new character from, with "others" for another eight. */
    private final class LookAlike implements MiiUi.Overlay {
        private final boolean female;
        private final Mii[] miis = new Mii[8];
        private final Bitmap[] front = new Bitmap[8], back = new Bitmap[8];
        private final List<MiiUi.Item> items = new ArrayList<>();
        private final float[] lift = new float[10];
        private int focus;
        private float appear;

        LookAlike(boolean female) {
            this.female = female;
            for (int i = 0; i < 8; i++) {
                float l = LA_X0 + (i % 4) * (LA_W + LA_GAP_X), t = LA_Y0 + (i / 4) * (LA_H + LA_GAP_Y);
                items.add(new MiiUi.Item().set(LA_TILE, i, 0, l, t, l + LA_W, t + LA_H));
            }
            float by = LA_Y0 + 2 * LA_H + LA_GAP_Y + 34f;
            items.add(new MiiUi.Item().set(LA_MORE, 0, 0, 960f - 40f - 440f, by, 960f - 40f, by + 104f));
            items.add(new MiiUi.Item().set(LA_BACK, 0, 0, 960f + 40f, by, 960f + 480f, by + 104f));
            roll();
        }

        private void roll() {
            for (int i = 0; i < 8; i++) {
                Mii m = Mii.random(rnd, female);
                m.name = "";
                miis[i] = m;
                front[i] = MiiPlaza.headBitmap(art, m, MiiArt.FRONT, false, false);
                back[i] = MiiPlaza.hasBackHair(m) ? MiiPlaza.headBitmap(art, m, MiiArt.FRONT, true, false) : null;
            }
        }

        @Override
        public void step(float dt) {
            appear = MiiUi.approach(appear, 1f, 1f - (float) Math.exp(-dt / 0.09f), 0.003f);
            float k = 1f - (float) Math.exp(-dt / 0.06f);
            for (int i = 0; i < lift.length; i++) lift[i] = MiiUi.approach(lift[i], i == focus ? 1f : 0f, k, 0.003f);
        }

        @Override
        public void draw(Canvas c) {
            int save = ui.dim(c, appear);
            ui.darkPanel(c, new RectF(ui.sr(LA_X0 - 74f, 60f, 1920f - LA_X0 + 74f, 1020f)),
                    str(R.string.mii_lookalike_title));
            for (int i = 0; i < 8; i++) {
                if (i != focus) drawTile(c, i);
            }
            if (focus < 8) drawTile(c, focus);
            ui.pill(c, new RectF(ui.sr(items.get(8).r)), str(R.string.mii_more), lift[8], 44f);
            ui.pill(c, new RectF(ui.sr(items.get(9).r)), str(R.string.mii_back), lift[9], 44f);
            c.restoreToCount(save);
        }

        private void drawTile(Canvas c, int i) {
            RectF b = new RectF(ui.sr(items.get(i).r));
            float lf = lift[i], sc = 1f + 0.06f * lf, rad = 30f * s;
            int sv = c.save();
            c.scale(sc, sc, b.centerX(), b.centerY());
            ui.box(c, b, rad, 0xFFFFFFFF, 0xFFE2EAEA, 0xFF2A3436, 4f * s);
            ui.glow(c, b, rad, lf);
            c.clipRect(b.left + 4f * s, b.top + 4f * s, b.right - 4f * s, b.bottom - 4f * s);
            float u = 122f * s;
            c.translate(b.centerX(), b.centerY() + 0.2f * u);
            float k = u / MiiPlaza.RES;
            c.scale(k, k);
            if (back[i] != null) c.drawBitmap(back[i], MiiPlaza.HB_L * MiiPlaza.RES, MiiPlaza.BB_T * MiiPlaza.RES, bmp);
            c.drawBitmap(front[i], MiiPlaza.HB_L * MiiPlaza.RES, MiiPlaza.HB_T * MiiPlaza.RES, bmp);
            c.restoreToCount(sv);
        }

        @Override
        public void key(int code) {
            if (code == KeyEvent.KEYCODE_BACK) {
                close();
                return;
            }
            int dx = MiiUi.dx(code), dy = MiiUi.dy(code);
            if (dx != 0 || dy != 0) {
                int n = MiiUi.nav(items, focus, dx, dy);
                if (n >= 0) {
                    focus = n;
                    sound(WiiSounds.MOVE);
                }
                return;
            }
            if (!MiiUi.isOk(code)) return;
            MiiUi.Item it = items.get(focus);
            if (it.kind == LA_MORE) {
                roll();
                sound(WiiSounds.PAGE);
            } else if (it.kind == LA_BACK) {
                close();
            } else {
                overlays.remove(this);
                sound(WiiSounds.SELECT);
                original = null;
                openEditor(miis[it.a], true);
            }
        }

        private void close() {
            overlays.remove(this);
            sound(WiiSounds.BACK);
            askStart(female);
        }

        @Override
        public void handTarget(float[] out) {
            RectF b = items.get(focus).r;
            out[0] = b.left + b.width() * 0.72f;
            out[1] = b.top + b.height() * 0.72f;
        }
    }
}
