package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

/**
 * What the Grok channel draws over the page: the Wii hand the remote steers and, while the page loads or when it
 * would not, the channel's picture over the whole screen. A press on an arrow nudges the hand; held, the hand
 * glides faster and faster (GrokPages.speed). Pushed against the top or the bottom, it scrolls the page instead.
 */
final class GrokOverlay extends View implements Choreographer.FrameCallback {

    interface Host {
        /** The hand moved to (x, y), in px of this view, which covers the page exactly. */
        void onHandMoved(float x, float y);
        /** The hand was pushed dy px past the top (dy < 0) or the bottom (dy > 0). */
        void onEdgePush(float dy);
    }

    static final int UP = 0, DOWN = 1, LEFT = 2, RIGHT = 3;
    private static final float[] DX = {0f, 0f, -1f, 1f}, DY = {-1f, 1f, 0f, 0f};
    /** Reference px (1920 wide) a single press moves the hand. */
    private static final float NUDGE = 16f;
    /**
     * A press this soon after the same arrow let go goes on with the hold: some IR remotes repeat a held button as
     * press and release pairs instead of one long press.
     */
    private static final long SAME_HOLD_MS = 200L;
    /** A little smaller than the menu's hand: web pages have smaller things to point at. */
    private static final float HAND_SCALE = 0.8f;
    /** The picture stays at least this long, so the channel is seen to open before the page shows. */
    private static final long MIN_PICTURE_MS = 900L;
    private static final float FADE_PER_S = 2.5f;

    private final Host host;
    private final GrokBanner art;
    private final WiiHand hand;
    private final String hint;
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Per arrow: when it went down (0 while up), when the hold began, when it last let go. */
    private final long[] pressed = new long[4], holdStart = new long[4], released = new long[4];
    private float x = -1f, y = -1f, s = 1f;
    /** How much of the picture shows over the page, 0 to 1, and whether it should be coming or going. */
    private float veil = 1f;
    private boolean veilWanted = true;
    private long pictureSince;
    private String message;
    private boolean running, posted;
    private long lastNanos;

    GrokOverlay(Context context, Host host, String label, String hint) {
        super(context);
        this.host = host;
        this.hint = hint;
        Typeface f;
        try {
            f = context.getResources().getFont(R.font.outfit_medium);
        } catch (RuntimeException e) {
            f = Typeface.SANS_SERIF;
        }
        Typeface bold = WiiMenuView.weight(context, f, 620);
        art = new GrokBanner(label, bold);
        hand = new WiiHand(bold);
        small.setTypeface(f);
        small.setTextAlign(Paint.Align.CENTER);
        pictureSince = SystemClock.uptimeMillis();
    }

    float handX() {
        return x;
    }

    float handY() {
        return y;
    }

    void press(int dir) {
        if (pressed[dir] != 0L) return;
        long now = SystemClock.uptimeMillis();
        if (now - released[dir] > SAME_HOLD_MS) {
            holdStart[dir] = now;
            move(DX[dir] * NUDGE * s, DY[dir] * NUDGE * s);
        }
        pressed[dir] = now;
        kick();
    }

    void release(int dir) {
        if (pressed[dir] == 0L) return;
        pressed[dir] = 0L;
        released[dir] = SystemClock.uptimeMillis();
    }

    /** The page is (re)loading: the picture comes back, with its dots typing. */
    void loading() {
        message = null;
        veilWanted = true;
        pictureSince = SystemClock.uptimeMillis();
        kick();
        invalidate();
    }

    /** The page is up: the picture fades away, unless it is telling what went wrong. */
    void ready() {
        if (message != null) return;
        veilWanted = false;
        kick();
    }

    /** The page could not open: the picture comes back with the message instead of the hint. */
    void fail(String why) {
        message = why;
        veilWanted = true;
        kick();
        invalidate();
    }

    void resume() {
        running = true;
        lastNanos = 0L;
        kick();
    }

    void pause() {
        running = false;
        for (int d = 0; d < 4; d++) pressed[d] = 0L;
        if (posted) Choreographer.getInstance().removeFrameCallback(this);
        posted = false;
    }

    private void kick() {
        if (!running || posted) return;
        posted = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    public void doFrame(long frameNanos) {
        posted = false;
        if (!running) return;
        float elapsed = lastNanos == 0L ? 0f : (frameNanos - lastNanos) / 1e9f;
        // The hand never jumps after a slow frame; the picture fades in its own time however slow the frames are.
        float dt = Math.min(0.05f, elapsed), fade = Math.min(0.25f, elapsed) * FADE_PER_S;
        lastNanos = frameNanos;
        long now = SystemClock.uptimeMillis();
        boolean busy = false;
        float dx = 0f, dy = 0f;
        for (int d = 0; d < 4; d++) {
            if (pressed[d] != 0L) {
                float v = GrokPages.speed((now - holdStart[d]) / 1000f) * s * dt;
                dx += DX[d] * v;
                dy += DY[d] * v;
                busy = true;
            } else if (now - released[d] <= SAME_HOLD_MS) {
                busy = true;
            }
        }
        if (dx != 0f || dy != 0f) move(dx, dy);
        boolean up = veilWanted || now - pictureSince < MIN_PICTURE_MS;
        float before = veil;
        veil = up ? Math.min(1f, veil + fade) : Math.max(0f, veil - fade);
        if (veil > 0f) busy = true;
        if (veil > 0f || before > 0f) invalidate();
        if (busy) kick();
        else lastNanos = 0L;
    }

    private void move(float dx, float dy) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float nx = Math.max(0f, Math.min(w - 1f, x + dx)), ny = Math.max(0f, Math.min(h - 1f, y + dy));
        float past = y + dy - ny;
        if (nx != x || ny != y) {
            x = nx;
            y = ny;
            invalidate();
            host.onHandMoved(x, y);
        }
        if (past != 0f) host.onEdgePush(past);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;
        s = w / 1920f;
        hand.setScale(s * HAND_SCALE);
        if (x < 0f) {
            x = w / 2f;
            y = h / 2f;
        } else {
            x = Math.min(x, w - 1f);
            y = Math.min(y, h - 1f);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        pause();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (veil > 0f) {
            int save = veil < 1f ? c.saveLayerAlpha(0, 0, w, h, Math.round(255 * veil)) : c.save();
            art.paint(c, w, h, SystemClock.uptimeMillis(), message == null);
            small.setColor(message != null ? 0xFFFFFFFF : 0xB3FFFFFF);
            small.setTextSize((message != null ? 34f : 28f) * s);
            c.drawText(message != null ? message : hint, w / 2f, h - 64f * s, small);
            c.restoreToCount(save);
        }
        hand.draw(c, x, y);
    }
}
