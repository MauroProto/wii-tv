package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

import io.nayuki.qrcodegen.QrCode;

import java.util.Arrays;

/**
 * The Wiimote channel's screen: the remote on the left, its lights sweeping while it waits as a remote syncing does;
 * on the right the QR code that opens the phone's page, the three steps, and how many phones are connected.
 * Laid out in px of a 1920 x 1080 screen, scaled by the view's width.
 */
final class WiimoteView extends View {
    private static final int TITLE = 0xFF27A3DA, INK = 0xFF4A5056, SOFT = 0xFF8A939B, RED = 0xFFD9534F;
    private static final long FRAME_MS = 33L;

    private final RemoteArt remote;
    private final Bitmap qr;
    private final String title, subtitle, waiting, joinedText, offline, hint;
    private final String[] steps;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** The QR code's modules stay square and sharp: no filtering. */
    private final Paint qrPaint = new Paint();
    private final TextPaint body = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface bold, regular;
    private final RectF r = new RectF();
    private StaticLayout[] stepLayouts;
    private Shader backdrop;
    private float s = 1f;
    private int phones;
    private boolean online = true, joined, running;
    /** How far the drawn remote leans after the phone's last moves, in degrees; it settles back upright. */
    private float tilt;
    private long lastNanos, startMs;

    WiimoteView(Context context, String url) {
        super(context);
        Typeface f;
        try {
            f = context.getResources().getFont(R.font.outfit_medium);
        } catch (RuntimeException e) {
            f = Typeface.SANS_SERIF;
        }
        regular = f;
        bold = WiiMenuView.weight(context, f, 680);
        remote = new RemoteArt(bold);
        title = context.getString(R.string.wiimote_channel_name);
        subtitle = context.getString(R.string.wiimote_title);
        waiting = context.getString(R.string.wiimote_waiting);
        joinedText = context.getString(R.string.wiimote_joined);
        offline = context.getString(R.string.wiimote_offline);
        hint = context.getString(R.string.wiimote_hint);
        steps = new String[] {context.getString(R.string.wiimote_step_scan),
                context.getString(R.string.wiimote_step_start), context.getString(R.string.wiimote_step_point)};
        qr = url == null ? null : qrBitmap(url);
        line.setStyle(Paint.Style.STROKE);
        wave.setStyle(Paint.Style.STROKE);
        wave.setStrokeCap(Paint.Cap.ROUND);
        body.setTypeface(regular);
        body.setColor(INK);
    }

    /** The code with its four-module quiet zone, one pixel per module. */
    static Bitmap qrBitmap(String content) {
        QrCode code = QrCode.encodeText(content, QrCode.Ecc.MEDIUM);
        int quiet = 4, n = code.size + 2 * quiet;
        int[] px = new int[n * n];
        Arrays.fill(px, 0xFFFFFFFF);
        for (int y = 0; y < code.size; y++) {
            for (int x = 0; x < code.size; x++) {
                if (code.getModule(x, y)) px[(y + quiet) * n + x + quiet] = 0xFF1E2328;
            }
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
    }

    void setPhones(int count, boolean justJoined) {
        phones = count;
        if (justJoined) joined = true;
        invalidate();
    }

    void setOnline(boolean up) {
        online = up;
        invalidate();
    }

    /** The phone moved: the drawn remote leans the same way. */
    void nudge(float dx) {
        tilt = Math.max(-18f, Math.min(18f, tilt + dx * 0.03f));
        invalidate();
    }

    void resume() {
        running = true;
        startMs = SystemClock.uptimeMillis();
        lastNanos = 0L;
        invalidate();
    }

    void pause() {
        running = false;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        s = Math.max(1, w) / 1920f;
        backdrop = new LinearGradient(0f, 0f, 0f, h, 0xFFFAFCFD, 0xFFE2E9EE, Shader.TileMode.CLAMP);
        stepLayouts = null;
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        long now = System.nanoTime();
        float dt = lastNanos == 0L ? 0f : Math.min(0.05f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        tilt *= (float) Math.exp(-dt / 0.45f);
        float t = (SystemClock.uptimeMillis() - startMs) / 1000f;

        fill.setShader(backdrop);
        c.drawRect(0f, 0f, w, h, fill);
        fill.setShader(null);
        line.setColor(0x0F7A8C99);
        line.setStrokeWidth(1.5f * s);
        for (float y = 0f; y < h; y += 7f * s) c.drawLine(0f, y, w, y, line);

        boolean waitingNow = online && !joined && phones == 0;
        int save = c.save();
        c.translate(470f * s, 580f * s);
        c.rotate(-6f + 3f * (float) Math.sin(t * 1.3f) + tilt);
        float rh = 680f * s;
        if (waitingNow) waves(c, -rh / 2f - 24f * s, t);
        remote.draw(c, rh, lights(t));
        c.restoreToCount(save);

        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(bold);
        text.setTextSize(104f * s);
        text.setColor(TITLE);
        c.drawText(title, 860f * s, 225f * s, text);
        text.setTypeface(regular);
        text.setTextSize(42f * s);
        text.setColor(INK);
        c.drawText(subtitle, 864f * s, 292f * s, text);

        r.set(860f * s, 345f * s, 1270f * s, 755f * s);
        fill.setColor(0xFFFFFFFF);
        c.drawRoundRect(r, 36f * s, 36f * s, fill);
        line.setColor(RemoteArt.BLUE);
        line.setStrokeWidth(6f * s);
        c.drawRoundRect(r, 36f * s, 36f * s, line);
        if (qr != null) {
            int k = Math.max(1, (int) Math.floor(372f * s / qr.getWidth()));
            float size = k * qr.getWidth(), x = Math.round(r.centerX() - size / 2f), y = Math.round(r.centerY() - size / 2f);
            r.set(x, y, x + size, y + size);
            c.drawBitmap(qr, null, r, qrPaint);
        }

        if (stepLayouts == null) {
            body.setTextSize(33f * s);
            stepLayouts = new StaticLayout[steps.length];
            int width = Math.max(1, Math.round(452f * s));
            for (int i = 0; i < steps.length; i++) {
                stepLayouts[i] = StaticLayout.Builder.obtain(steps[i], 0, steps[i].length(), body, width)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.15f).build();
            }
        }
        float sy = 362f * s;
        for (int i = 0; i < stepLayouts.length; i++) {
            fill.setColor(RemoteArt.BLUE);
            c.drawCircle(1340f * s, sy + 24f * s, 26f * s, fill);
            text.setTypeface(bold);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(32f * s);
            text.setColor(0xFFFFFFFF);
            c.drawText(String.valueOf(i + 1), 1340f * s, sy + 35f * s, text);
            int at = c.save();
            c.translate(1388f * s, sy + 2f * s);
            stepLayouts[i].draw(c);
            c.restoreToCount(at);
            sy += Math.max(stepLayouts[i].getHeight(), 52f * s) + 40f * s;
        }

        float ly = 845f * s;
        int lit = lights(t);
        for (int i = 0; i < 4; i++) {
            float x = 864f * s + i * 58f * s;
            r.set(x, ly - 9f * s, x + 42f * s, ly + 9f * s);
            fill.setColor((lit >> i & 1) != 0 ? RemoteArt.BLUE : 0xFFD3D9DE);
            c.drawRoundRect(r, 9f * s, 9f * s, fill);
        }
        String status = !online ? offline : joined ? joinedText : phones > 0
                ? getResources().getQuantityString(R.plurals.wiimote_phones, phones, phones) : waiting;
        text.setTypeface(regular);
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(38f * s);
        text.setColor(online ? INK : RED);
        c.drawText(status, 864f * s + 4 * 58f * s + 22f * s, ly + 13f * s, text);

        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(27f * s);
        text.setColor(SOFT);
        c.drawText(hint, w / 2f, 1015f * s, text);

        if (running) postInvalidateDelayed(FRAME_MS);
    }

    /** The player lights: one sweeping across while it waits, then one for each phone. */
    private int lights(float t) {
        if (!online) return 0;
        if (joined || phones > 0) return (1 << Math.min(4, Math.max(1, phones))) - 1;
        return 1 << ((int) (t * 3f) % 4);
    }

    /** Rings going out from the remote's tip toward the screen, as if it were looking for it. */
    private void waves(Canvas c, float tipY, float t) {
        for (int i = 0; i < 3; i++) {
            float phase = (t * 0.8f + i / 3f) % 1f;
            float radius = (40f + 170f * phase) * s;
            wave.setStrokeWidth(10f * s);
            wave.setColor((RemoteArt.BLUE & 0x00FFFFFF) | Math.round(200 * (1f - phase)) << 24);
            r.set(-radius, tipY - radius, radius, tipY + radius);
            c.drawArc(r, 235f, 70f, false, wave);
        }
    }
}
