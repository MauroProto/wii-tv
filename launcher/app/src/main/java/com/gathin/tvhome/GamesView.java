package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The Games channel: the channel's pixel meadow as a header, the games as cartridges on a shelf, the focused
 * one's name, and the two buttons of a channel's start screen with RetroArch's settings between them. The
 * pointing hand follows the focus, and a white fade covers coming in, leaving and starting a game.
 *
 * Geometry is in pixels of a 1920 x 1080 screen, scaled by the view width.
 */
final class GamesView extends View {

    interface Host {
        /** Starts the game in RetroArch; false when it could not, and the channel comes back. */
        boolean play(GameList.Game game);

        /** Opens RetroArch's own menu; false when it could not. */
        boolean openRetroArch();

        void exit();
    }

    private static final int BLUE = 0xFF3FC0EC;
    private static final int BLUE_TEXT = 0xFF2C9ED6;
    private static final int TEXT = 0xFF555555;
    private static final int SOFT = 0xFF9A9A9A;
    private static final int WARN = 0xFFD9534F;
    private static final int BORDER = 0xFFB5B5B5;

    private static final float HEADER_H = 300f;
    /** The shelf's top, where the cartridges stand. */
    private static final float SHELF_Y = 632f;
    private static final float CART_PX = 8f;
    private static final float PITCH = 250f;
    private static final float TITLE_Y = 742f;
    private static final float SUB_Y = 792f;
    private static final float LEGEND_Y = 850f;
    private static final float BAND_Y = 892f;
    private static final float BTN_Y = 986f;
    private static final float BTN_H = 116f;
    private static final float[] BTN_X = {500f, 960f, 1420f};
    private static final float[] BTN_W = {460f, 380f, 460f};
    private static final float GEAR_R = 23f;
    private static final int BTN_MENU = 0;
    private static final int BTN_RETRO = 1;
    private static final int BTN_PLAY = 2;
    private static final long FADE_MS = 300L;
    private static final long LAUNCH_MS = 520L;
    private static final long ENTER_MS = 420L;
    private static final long STAGGER_MS = 60L;
    /** The TV remote's colour buttons, in the order tools/games/add.py --setup maps them. */
    private static final int[] REMOTE = {0xFFE53935, 0xFF43A047, 0xFFFDD835, 0xFF1E88E5};

    private final Host host;
    private final WiiSounds sounds;
    private final PixelScene scene;
    private final WiiHand hand;
    private final Typeface bold;
    private final Typeface regular;
    private final String menuLabel, playLabel, retroLabel, emptyTitle, emptyBody, missing, coin;
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pix = new Paint();
    private final Paint plain = new Paint();
    private final RectF r = new RectF();
    private final Path gear = new Path();
    private final Path scaledGear = new Path();
    private final Matrix matrix = new Matrix();

    private List<GameList.Game> games = new ArrayList<>();
    private Bitmap[] carts = new Bitmap[0];
    /** Each game's GameList.pad, for the legend. */
    private String[][] pads = new String[0][];
    private String[][] keys = new String[0][];
    private float[] lift = new float[0];
    private final float[] btnLift = new float[3];
    private boolean hasRetroArch;
    private boolean onShelf;
    private int focus;
    private int button = BTN_PLAY;
    private float shelfPos;
    private float handX = 960f, handY = 760f;
    private boolean handPlaced;
    private float s = 1f;
    private Bitmap backdrop;
    private Shader pillShader;
    private DashPathEffect dash;
    private long enterMs, lastNanos;
    private boolean running;
    private float veil = 1f;
    private int veilDir = -1;
    private Runnable afterVeil;
    private long launchMs;
    private boolean launched;

    GamesView(Context context, Host host, WiiSounds sounds) {
        super(context);
        this.host = host;
        this.sounds = sounds;
        Typeface f;
        try {
            f = context.getResources().getFont(R.font.outfit_medium);
        } catch (RuntimeException e) {
            f = Typeface.SANS_SERIF;
        }
        regular = f;
        bold = WiiMenuView.weight(context, f, 640);
        menuLabel = context.getString(R.string.mii_exit_title);
        playLabel = context.getString(R.string.games_play);
        retroLabel = context.getString(R.string.games_retroarch);
        emptyTitle = context.getString(R.string.games_empty_title);
        emptyBody = context.getString(R.string.games_empty_body);
        missing = context.getString(R.string.games_missing);
        coin = context.getString(R.string.games_coin);
        scene = new PixelScene(context.getString(R.string.games_channel_name), bold);
        hand = new WiiHand(bold);
        pix.setFilterBitmap(false);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        buildGear();
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    /** A gear of unit radius: eight teeth round a ring. */
    private void buildGear() {
        gear.addCircle(0f, 0f, 0.72f, Path.Direction.CW);
        Path tooth = new Path();
        Matrix m = new Matrix();
        for (int i = 0; i < 8; i++) {
            tooth.reset();
            tooth.addRoundRect(new RectF(-0.17f, -1f, 0.17f, -0.55f), 0.06f, 0.06f, Path.Direction.CW);
            m.setRotate(i * 45f);
            tooth.transform(m);
            gear.op(tooth, Path.Op.UNION);
        }
        Path hole = new Path();
        hole.addCircle(0f, 0f, 0.32f, Path.Direction.CW);
        gear.op(hole, Path.Op.DIFFERENCE);
    }

    void setGames(List<GameList.Game> list, boolean retroArch, String lastPlayed) {
        games = list;
        hasRetroArch = retroArch;
        carts = new Bitmap[list.size()];
        pads = new String[list.size()][];
        keys = new String[list.size()][];
        for (int i = 0; i < carts.length; i++) {
            carts[i] = PixelArt.cartridge(list.get(i).color, list.get(i).title);
            pads[i] = GameList.pad(list.get(i).rom, coin);
            String[] buttons = GameList.buttons(list.get(i).rom);
            keys[i] = new String[] {getContext().getString(R.string.games_key_a, buttons[0]),
                    getContext().getString(R.string.games_key_b, buttons[1]),
                    getContext().getString(R.string.games_key_menu)};
        }
        lift = new float[list.size()];
        focus = 0;
        for (int i = 0; i < list.size(); i++) if (list.get(i).rom.equals(lastPlayed)) focus = i;
        shelfPos = focus;
        onShelf = !list.isEmpty();
        button = onShelf && retroArch ? BTN_PLAY : retroArch ? BTN_RETRO : BTN_MENU;
        handPlaced = false;
        enterMs = SystemClock.uptimeMillis();
        invalidate();
    }

    void resume() {
        running = true;
        veil = 1f;
        veilDir = -1;
        launchMs = 0L;
        launched = false;
        afterVeil = null;
        lastNanos = 0L;
        invalidate();
    }

    void pause() {
        running = false;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;
        s = w / 1920f;
        hand.setScale(s);
        pillShader = new LinearGradient(0, -BTN_H * s / 2f, 0, BTN_H * s / 2f, 0xFFFFFFFF, 0xFFE9E9E9,
                Shader.TileMode.CLAMP);
        dash = new DashPathEffect(new float[] {18f * s, 12f * s}, 0f);
        backdrop = buildBackdrop(w, h);
    }

    /** Everything that never moves: the stripes, the shelf and the band with the blue arc. */
    private Bitmap buildBackdrop(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.drawColor(0xFFF4F4F4);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFECECEC);
        for (float y = HEADER_H * s; y < h; y += 10f * s) cv.drawRect(0, y, w, y + 4f * s, p);

        p.setShader(new LinearGradient(0, SHELF_Y * s, 0, (SHELF_Y + 22f) * s, 0xFFFFFFFF, 0xFFD3D3D3,
                Shader.TileMode.CLAMP));
        cv.drawRoundRect(60f * s, SHELF_Y * s, w - 60f * s, (SHELF_Y + 22f) * s, 11f * s, 11f * s, p);
        p.setShader(new LinearGradient(0, (SHELF_Y + 22f) * s, 0, (SHELF_Y + 42f) * s, 0x24000000, 0x00000000,
                Shader.TileMode.CLAMP));
        cv.drawRect(80f * s, (SHELF_Y + 22f) * s, w - 80f * s, (SHELF_Y + 42f) * s, p);
        p.setShader(new LinearGradient(0, HEADER_H * s, 0, (HEADER_H + 26f) * s, 0x30000000, 0x00000000,
                Shader.TileMode.CLAMP));
        cv.drawRect(0, HEADER_H * s, w, (HEADER_H + 26f) * s, p);

        Path arc = new Path();
        arc.moveTo(0, (BAND_Y + 26f) * s);
        arc.quadTo(w / 2f, (BAND_Y - 26f) * s, w, (BAND_Y + 26f) * s);
        Path band = new Path(arc);
        band.lineTo(w, h);
        band.lineTo(0, h);
        band.close();
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(0x10000000);
        p.setStrokeWidth(22f * s);
        cv.save();
        cv.translate(0, -8f * s);
        cv.drawPath(arc, p);
        cv.restore();
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, (BAND_Y - 26f) * s, 0, h, 0xFFF3F3F3, 0xFFD6D6D6, Shader.TileMode.CLAMP));
        cv.drawPath(band, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4f * s);
        p.setColor(BLUE);
        cv.drawPath(arc, p);
        cv.save();
        cv.translate(0, 4f * s);
        p.setStrokeWidth(2f * s);
        p.setColor(0xFFFFFFFF);
        cv.drawPath(arc, p);
        cv.restore();
        return b;
    }

    // ---- keys -------------------------------------------------------------------------------------------

    boolean keyDown(int keyCode, int repeat) {
        if (launchMs != 0L || veilDir > 0) return isOurs(keyCode);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                move(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                move(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (!onShelf && !games.isEmpty()) {
                    onShelf = true;
                    sounds.play(WiiSounds.MOVE);
                    invalidate();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (onShelf) {
                    onShelf = false;
                    button = hasRetroArch ? BTN_PLAY : BTN_MENU;
                    sounds.play(WiiSounds.MOVE);
                    invalidate();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (repeat == 0) choose();
                return true;
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (repeat == 0) startGame();
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_ESCAPE:
                if (repeat == 0) leave();
                return true;
            default:
                return false;
        }
    }

    private static boolean isOurs(int keyCode) {
        return keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN
                && keyCode != KeyEvent.KEYCODE_VOLUME_MUTE;
    }

    private void move(int d) {
        if (onShelf) {
            int n = Math.max(0, Math.min(games.size() - 1, focus + d));
            if (n != focus) {
                focus = n;
                sounds.play(WiiSounds.MOVE);
            }
        } else {
            for (int b = button + d; b >= BTN_MENU && b <= BTN_PLAY; b += d) {
                if (enabled(b)) {
                    button = b;
                    sounds.play(WiiSounds.MOVE);
                    break;
                }
            }
        }
        invalidate();
    }

    private boolean enabled(int b) {
        return b == BTN_MENU || hasRetroArch && (b == BTN_RETRO || !games.isEmpty());
    }

    private void choose() {
        if (onShelf || button == BTN_PLAY) {
            startGame();
        } else if (button == BTN_MENU) {
            leave();
        } else if (hasRetroArch) {
            sounds.play(WiiSounds.SELECT);
            fadeOut(() -> {
                if (!host.openRetroArch()) fadeIn();
            });
        }
    }

    private void startGame() {
        if (games.isEmpty() || !hasRetroArch) {
            sounds.play(WiiSounds.BACK);
            return;
        }
        sounds.play(WiiSounds.START);
        launchMs = SystemClock.uptimeMillis();
        launched = false;
        invalidate();
    }

    private void leave() {
        sounds.play(WiiSounds.BACK);
        fadeOut(host::exit);
    }

    private void fadeOut(Runnable then) {
        afterVeil = then;
        veilDir = 1;
        invalidate();
    }

    private void fadeIn() {
        launchMs = 0L;
        launched = false;
        veilDir = -1;
        invalidate();
    }

    // ---- drawing ----------------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        long nanos = System.nanoTime();
        float dt = lastNanos == 0L ? 0f : Math.min(0.05f, (nanos - lastNanos) / 1e9f);
        lastNanos = nanos;
        boolean moving = step(dt, now);

        if (backdrop != null) c.drawBitmap(backdrop, 0, 0, plain);
        scene.draw(c, getWidth(), HEADER_H * s, now, 0.16f, 4f);
        plain.setColor(BLUE);
        c.drawRect(0, HEADER_H * s - 5f * s, getWidth(), HEADER_H * s, plain);

        drawShelf(c, now);
        drawInfo(c);
        drawPill(c, BTN_MENU, menuLabel, true);
        drawPill(c, BTN_RETRO, retroLabel, enabled(BTN_RETRO));
        drawPill(c, BTN_PLAY, playLabel, enabled(BTN_PLAY));
        hand.draw(c, handX * s, handY * s);

        if (veil > 0.002f) {
            plain.setColor(0xFFFFFFFF);
            plain.setAlpha(Math.round(255 * veil));
            c.drawRect(0, 0, getWidth(), getHeight(), plain);
            plain.setAlpha(255);
        }
        if (!running) return;
        if (moving) postInvalidateOnAnimation();
        else postInvalidateDelayed(33L);
    }

    /** Moves everything one frame on; true while something moves faster than the meadow. */
    private boolean step(float dt, long now) {
        boolean moving = false;
        float k = 1f - (float) Math.exp(-dt * 12f);
        float pos = shelfPos + (focus - shelfPos) * (1f - (float) Math.exp(-dt * 10f));
        if (Math.abs(focus - pos) < 0.002f) pos = focus;
        if (pos != shelfPos) moving = true;
        shelfPos = pos;
        for (int i = 0; i < lift.length; i++) {
            float want = onShelf && i == focus ? 1f : 0f;
            float v = Math.abs(want - lift[i]) < 0.003f ? want : lift[i] + (want - lift[i]) * k;
            if (v != lift[i]) moving = true;
            lift[i] = v;
        }
        for (int b = 0; b < btnLift.length; b++) {
            float want = !onShelf && b == button ? 1f : 0f;
            float v = Math.abs(want - btnLift[b]) < 0.003f ? want : btnLift[b] + (want - btnLift[b]) * k;
            if (v != btnLift[b]) moving = true;
            btnLift[b] = v;
        }

        float tx, ty;
        if (onShelf) {
            tx = 960f + (focus - shelfPos) * PITCH + 72f;
            ty = SHELF_Y - 86f;
        } else {
            tx = BTN_X[button] + BTN_W[button] * 0.28f;
            ty = BTN_Y + 26f;
        }
        if (!handPlaced) {
            handX = tx;
            handY = ty;
            handPlaced = true;
        } else {
            float hk = 1f - (float) Math.exp(-dt * 14f);
            handX += (tx - handX) * hk;
            handY += (ty - handY) * hk;
            if (Math.abs(tx - handX) > 0.5f || Math.abs(ty - handY) > 0.5f) moving = true;
        }

        if (veilDir != 0) {
            moving = true;
            veil += veilDir * dt * 1000f / FADE_MS;
            if (veilDir < 0 && veil <= 0f) {
                veil = 0f;
                veilDir = 0;
            } else if (veilDir > 0 && veil >= 1f) {
                veil = 1f;
                veilDir = 0;
                Runnable then = afterVeil;
                afterVeil = null;
                if (then != null) post(then);
            }
        }
        if (launchMs != 0L) {
            moving = true;
            float lk = Math.min(1f, (now - launchMs) / (float) LAUNCH_MS);
            veil = Math.max(veil, ease((lk - 0.35f) / 0.65f));
            if (lk >= 1f && !launched) {
                launched = true;
                final GameList.Game game = games.get(focus);
                post(() -> {
                    if (!host.play(game)) fadeIn();
                });
            }
        }
        if (now - enterMs < ENTER_MS + STAGGER_MS * 6) moving = true;
        return moving;
    }

    private void drawShelf(Canvas c, long now) {
        float cw = PixelArt.CART_W * CART_PX * s, ch = PixelArt.CART_H * CART_PX * s;
        if (games.isEmpty()) {
            float cx = 960f * s, bottom = SHELF_Y * s;
            r.set(cx - cw / 2f, bottom - ch, cx + cw / 2f, bottom);
            stroke.setPathEffect(dash);
            stroke.setColor(0xFFC6C6C6);
            stroke.setStrokeWidth(5f * s);
            c.drawRoundRect(r, 14f * s, 14f * s, stroke);
            stroke.setPathEffect(null);
            stroke.setStrokeWidth(10f * s);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            float arm = 34f * s, my = r.centerY();
            c.drawLine(cx - arm, my, cx + arm, my, stroke);
            c.drawLine(cx, my - arm, cx, my + arm, stroke);
            stroke.setStrokeCap(Paint.Cap.BUTT);
            return;
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < carts.length; i++) {
                if ((i == focus) != (pass == 1)) continue;
                float cx = (960f + (i - shelfPos) * PITCH) * s;
                if (cx + cw < 0 || cx - cw > getWidth()) continue;
                drawCart(c, i, cx, cw, ch, now);
            }
        }
    }

    private void drawCart(Canvas c, int i, float cx, float cw, float ch, long now) {
        float e = ease((now - enterMs - Math.abs(i - focus) * STAGGER_MS) / (float) ENTER_MS);
        float lf = lift[i];
        float sc = 1f + 0.16f * lf;
        float up = lf * (16f + 4f * (float) Math.sin(now / 420.0)) * s;
        if (launchMs != 0L && i == focus) {
            float lk = Math.min(1f, (now - launchMs) / (float) LAUNCH_MS);
            sc *= 1f + 0.12f * ease(lk);
            up += (float) Math.sin(Math.min(1f, lk * 1.6f) * Math.PI) * 70f * s;
        }
        float bottom = SHELF_Y * s - up + (1f - e) * 90f * s;
        float dw = cw * sc, dh = ch * sc;

        fill.setColor(0x26000000);
        fill.setAlpha(Math.round(0x26 * e * (1f - 0.4f * lf)));
        float sw = dw * 0.42f * (1f - 0.2f * lf);
        c.drawOval(cx - sw, SHELF_Y * s - 6f * s, cx + sw, SHELF_Y * s + 6f * s, fill);

        r.set(cx - dw / 2f, bottom - dh, cx + dw / 2f, bottom);
        if (lf > 0.01f) {
            r.inset(-8f * s, -8f * s);
            glow(c, r, 18f * s, lf);
            stroke.setColor(withAlpha(BLUE, Math.round(255 * lf)));
            stroke.setStrokeWidth(5f * s);
            c.drawRoundRect(r, 18f * s, 18f * s, stroke);
            r.inset(8f * s, 8f * s);
        }
        pix.setAlpha(Math.round(255 * e));
        c.drawBitmap(carts[i], null, r, pix);
    }

    private void drawInfo(Canvas c) {
        String title, sub;
        int subColor = SOFT;
        if (games.isEmpty()) {
            title = emptyTitle;
            sub = emptyBody;
        } else {
            GameList.Game g = games.get(focus);
            title = g.title;
            String system = GameList.system(g.rom);
            sub = !hasRetroArch ? missing : system != null ? system : g.core;
            if (!hasRetroArch) subColor = WARN;
        }
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(bold);
        text.setTextSize(58f * s);
        text.setColor(TEXT);
        CharSequence t = TextUtils.ellipsize(title, text, 1500f * s, TextUtils.TruncateAt.END);
        c.drawText(t, 0, t.length(), 960f * s, TITLE_Y * s, text);
        text.setTypeface(regular);
        text.setTextSize(32f * s);
        text.setColor(subColor);
        c.drawText(sub, 960f * s, SUB_Y * s, text);
        if (!games.isEmpty() && hasRetroArch) drawLegend(c, pads[focus], keys[focus]);
    }

    /** The remote's colour buttons, OK and Back as the console's own buttons. */
    private void drawLegend(Canvas c, String[] pad, String[] keys) {
        text.setTypeface(regular);
        text.setTextSize(27f * s);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(0xFF8C8C8C);
        float dot = 20f * s, gap = 10f * s, item = 26f * s, group = 52f * s;
        float total = 0f;
        for (String p : pad) if (p != null) total += dot + gap + text.measureText(p) + item;
        total += group - item;
        for (int i = 0; i < keys.length; i++) total += text.measureText(keys[i]) + (i < keys.length - 1 ? group : 0f);
        float x = 960f * s - total / 2f, y = LEGEND_Y * s;
        for (int i = 0; i < pad.length; i++) {
            if (pad[i] == null) continue;
            fill.setColor(REMOTE[i]);
            c.drawCircle(x + dot / 2f, y - 9f * s, dot / 2f, fill);
            stroke.setColor(0x33000000);
            stroke.setStrokeWidth(1.5f * s);
            c.drawCircle(x + dot / 2f, y - 9f * s, dot / 2f, stroke);
            x += dot + gap;
            c.drawText(pad[i], x, y, text);
            x += text.measureText(pad[i]) + item;
        }
        x += group - item;
        for (String k : keys) {
            c.drawText(k, x, y, text);
            x += text.measureText(k) + group;
        }
        text.setTextAlign(Paint.Align.CENTER);
    }

    /** One of the start screen's rounded buttons; RetroArch's carries a gear before its name. */
    private void drawPill(Canvas c, int which, String label, boolean on) {
        float lf = btnLift[which];
        float k = 1f + 0.04f * lf;
        int save = c.save();
        c.translate(BTN_X[which] * s, BTN_Y * s);
        c.scale(k, k);
        float bw = BTN_W[which] * s;
        r.set(-bw / 2f, -BTN_H * s / 2f, bw / 2f, BTN_H * s / 2f);
        float rad = BTN_H * s / 2f;
        glow(c, r, rad, lf);
        fill.setShader(pillShader);
        c.drawRoundRect(r, rad, rad, fill);
        fill.setShader(null);
        stroke.setColor(blend(BORDER, BLUE, lf));
        stroke.setStrokeWidth((4.5f + 1.5f * lf) * s);
        c.drawRoundRect(r, rad, rad, stroke);
        int ink = on ? blend(0xFF6A6A6A, BLUE_TEXT, lf) : 0xFFC6C6C6;
        text.setTypeface(bold);
        text.setTextSize((which == BTN_RETRO ? 40f : 46f) * s);
        text.setColor(ink);
        float tw = text.measureText(label), x = 0f;
        if (which == BTN_RETRO) {
            float g = GEAR_R * s, gap = 14f * s, left = -(2f * g + gap + tw) / 2f;
            matrix.setScale(g, g);
            matrix.postTranslate(left + g, 0f);
            gear.transform(matrix, scaledGear);
            fill.setColor(ink);
            c.drawPath(scaledGear, fill);
            x = left + 2f * g + gap + tw / 2f;
        }
        c.drawText(label, x, -(text.ascent() + text.descent()) / 2f, text);
        c.restoreToCount(save);
    }

    /** The menu's blue halo round a focused frame. */
    private void glow(Canvas c, RectF box, float rad, float lf) {
        if (lf <= 0.01f) return;
        for (int k = 3; k >= 1; k--) {
            stroke.setColor(withAlpha(BLUE, Math.round(lf * 64 / k)));
            stroke.setStrokeWidth((4.5f + k * 5f) * s);
            c.drawRoundRect(box, rad, rad, stroke);
        }
    }

    private static float ease(float x) {
        x = Math.max(0f, Math.min(1f, x));
        float u = 1f - x;
        return 1f - u * u * u;
    }

    private static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0xFFFFFF);
    }

    private static int blend(int a, int b, float t) {
        return PixelArt.mix(a, b, Math.max(0f, Math.min(1f, t)));
    }
}
