package com.gathin.tvhome;

/**
 * Pixels for the channel menu's static parts: the striped backdrop, the blue line, the bottom panel with
 * its button tracks, the two round buttons' bodies and the empty-slot texture. Plain Java over int arrays,
 * so it runs off the UI thread; WiiMenuView wraps the arrays in bitmaps.
 *
 * Coordinates are pixels of the 1920 x 1080 reference ("ref px"); s converts them to screen pixels.
 */
final class WiiBase {
    private WiiBase() {}

    static final float CX = 959.5f;
    static final float LINE_TOP = 783.36f;
    static final float LINE_BOTTOM = 898.48f;
    /** x of the four points of the left curve's cubic; y runs top, top, bottom, bottom. Mirrored on the right. */
    private static final float[] CURVE = {288f, 448f, 516f, 686f};
    static final float LINE_HALF = 2.5f;
    private static final int LINE_R = 0x3E, LINE_G = 0xBC, LINE_B = 0xE8;
    private static final float HIGHLIGHT_D = -8.5f, HIGHLIGHT_SIGMA = 1.3f, HIGHLIGHT_AMP = 6f;

    static final float BTN_CX = 172.1f;
    static final float BTN_CY = 908.9f;
    static final float BTN_RING = 88.6f;
    static final float BTN_RING_HALF = 2.7f;
    private static final float TRACK_CY = 918f;
    private static final float TRACK_END_X = 178f;
    /** Button body grey as a polynomial in (x, y) / BODY_R, least-squares fitted to both buttons of the capture. */
    private static final float BODY_R = 86f;
    private static final float[] BODY = {219.876f, -27.784f, -14.379f, 0.952f, -0.734f, 13.346f, 24.161f, 7.595f,
            4.184f, 31.043f, -6.780f, -32.405f};
    private static float bez(float a, float b, float c, float d, float t) {
        float u = 1f - t;
        return u * u * u * a + 3f * u * u * t * b + 3f * u * t * t * c + t * t * t * d;
    }

    /** Height of the blue line's centre at reference x. */
    static float lineY(float x) {
        float xm = x > CX ? 2f * CX - x : x;
        if (xm <= CURVE[0]) return LINE_TOP;
        if (xm >= CURVE[3]) return LINE_BOTTOM;
        float lo = 0f, hi = 1f;
        for (int i = 0; i < 22; i++) {
            float t = (lo + hi) * 0.5f;
            if (bez(CURVE[0], CURVE[1], CURVE[2], CURVE[3], t) < xm) lo = t;
            else hi = t;
        }
        return bez(LINE_TOP, LINE_TOP, LINE_BOTTOM, LINE_BOTTOM, (lo + hi) * 0.5f);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }

    private static int clamp255(float v) {
        int i = Math.round(v);
        return i < 0 ? 0 : i > 255 ? 255 : i;
    }

    /** Smooth bump that is 1 between lo and hi and falls off over soft ref px either side. */
    private static float band(float v, float lo, float hi, float soft) {
        if (v < lo) return clamp01(1f - (lo - v) / soft);
        if (v > hi) return clamp01(1f - (v - hi) / soft);
        return 1f;
    }

    private static int panel(float xm, float d) {
        float fi = xm / WiiPalette.PANEL_STEP_X, fj = Math.max(0f, d) / WiiPalette.PANEL_STEP_D;
        if (fi < 0f) fi = 0f;
        if (fi > WiiPalette.PANEL_NX - 1.001f) fi = WiiPalette.PANEL_NX - 1.001f;
        if (fj > WiiPalette.PANEL_ND - 1.001f) fj = WiiPalette.PANEL_ND - 1.001f;
        int i = (int) fi, j = (int) fj;
        float ax = fi - i, ay = fj - j;
        int n = WiiPalette.PANEL_NX;
        int c00 = WiiPalette.PANEL[j * n + i], c10 = WiiPalette.PANEL[j * n + i + 1];
        int c01 = WiiPalette.PANEL[(j + 1) * n + i], c11 = WiiPalette.PANEL[(j + 1) * n + i + 1];
        float w00 = (1 - ax) * (1 - ay), w10 = ax * (1 - ay), w01 = (1 - ax) * ay, w11 = ax * ay;
        int r = Math.round(((c00 >> 16) & 255) * w00 + ((c10 >> 16) & 255) * w10 + ((c01 >> 16) & 255) * w01 + ((c11 >> 16) & 255) * w11);
        int g = Math.round(((c00 >> 8) & 255) * w00 + ((c10 >> 8) & 255) * w10 + ((c01 >> 8) & 255) * w01 + ((c11 >> 8) & 255) * w11);
        int b = Math.round((c00 & 255) * w00 + (c10 & 255) * w10 + (c01 & 255) * w01 + (c11 & 255) * w11);
        return (r << 16) | (g << 8) | b;
    }

    private static float trackDelta(float y) {
        int i = Math.round(y) - WiiPalette.TRACK_Y0;
        return i >= 0 && i < WiiPalette.TRACK_DELTA.length ? WiiPalette.TRACK_DELTA[i] : 0f;
    }

    /** Grey offset the button tracks and sockets add to the panel at a mirrored point (left half). */
    private static float socketDelta(float xm, float y, boolean rightSide) {
        if (xm > 330f || y < 780f) return 0f;
        float delta = 0f;
        if (xm <= TRACK_END_X) {
            float dl = trackDelta(y);
            // The upper grooves stop short of the button; the lower edge runs on round the track's end.
            if (y < 870f) dl *= clamp01((165f - xm) / 45f);
            delta += dl;
        } else {
            float r = (float) Math.hypot(xm - TRACK_END_X, y - TRACK_CY);
            if (r >= 98f) delta += trackDelta(TRACK_CY + r);
            else if (r < 98f && y > TRACK_CY - 98f) delta += -3f;
        }
        float bx = xm - BTN_CX, by = y - BTN_CY;
        float rr = (float) Math.hypot(bx, by);
        if (rr > 0f) {
            float towardCentre = bx / rr;
            if (towardCentre > 0f) delta += 9f * towardCentre * band(rr, 99f, 111f, 4f);
            float realX = rightSide ? -bx : bx;
            float c = (realX + by) / (rr * 1.41421356f);
            if (c > 0f && rr > BTN_RING) delta -= 18f * c * clamp01(1f - (rr - BTN_RING - 1f) / 14f);
        }
        return delta;
    }

    static int[] backdrop(int w, int h) {
        return backdrop(w, h, false);
    }

    static int[] backdrop(int w, int h, boolean dark) {
        float s = w / 1920f;
        int[] out = new int[w * h];
        float[] colLine = new float[w], colCos = new float[w], colBase = new float[w], colAmp = new float[w];
        float[] colXm = new float[w];
        boolean[] colRight = new boolean[w];
        for (int x = 0; x < w; x++) {
            float rx = (x + 0.5f) / s - 0.5f;
            float ly = lineY(rx);
            float slope = lineY(rx + 0.5f) - lineY(rx - 0.5f);
            colLine[x] = ly;
            colCos[x] = (float) (1.0 / Math.sqrt(1.0 + slope * slope));
            float fd = Math.abs(rx - CX) / 32f;
            int i = Math.min(WiiPalette.BG_BASE.length - 2, (int) fd);
            float a = Math.min(1f, fd - i);
            colBase[x] = WiiPalette.BG_BASE[i] * (1 - a) + WiiPalette.BG_BASE[i + 1] * a;
            colAmp[x] = WiiPalette.BG_AMP[i] * (1 - a) + WiiPalette.BG_AMP[i + 1] * a;
            colRight[x] = rx > CX;
            colXm[x] = colRight[x] ? 2f * CX - rx : rx;
        }
        float period = WiiPalette.STRIPE_PERIOD, phase = WiiPalette.STRIPE_PHASE;
        float inv2s2 = 1f / (2f * WiiPalette.STRIPE_SIGMA * WiiPalette.STRIPE_SIGMA);
        for (int y = 0; y < h; y++) {
            float ry = (y + 0.5f) / s - 0.5f;
            float ph = ((ry - phase) / period) % 1f;
            if (ph < 0f) ph += 1f;
            float dist = Math.min(ph, 1f - ph) * period;
            float k = (float) Math.exp(-dist * dist * inv2s2);
            int row = y * w;
            for (int x = 0; x < w; x++) {
                float dY = ry - colLine[x];
                float dp = dY * colCos[x];
                float r, g, b;
                if (dp < 0f) {
                    float hd = dp - HIGHLIGHT_D;
                    float v = colBase[x] + colAmp[x] * k + HIGHLIGHT_AMP * (float) Math.exp(-hd * hd / (2f * HIGHLIGHT_SIGMA * HIGHLIGHT_SIGMA));
                    if (dark) {
                        float dv = 24f + (v - 200f) * 0.42f;
                        r = dv - 2f;
                        g = dv;
                        b = dv + 5f;
                    } else {
                        r = g = b = v;
                    }
                } else {
                    int c = panel(colXm[x], dY);
                    float dl = socketDelta(colXm[x], ry, colRight[x]);
                    r = ((c >> 16) & 255) + dl;
                    g = ((c >> 8) & 255) + dl;
                    b = (c & 255) + dl;
                    if (dark) {
                        r = 20f + (r - 160f) * 0.35f;
                        g = 23f + (g - 160f) * 0.36f;
                        b = 29f + (b - 160f) * 0.38f;
                    }
                }
                float cov = clamp01((LINE_HALF - Math.abs(dp)) * s + 0.5f);
                if (cov > 0f) {
                    r += (LINE_R - r) * cov;
                    g += (LINE_G - g) * cov;
                    b += (LINE_B - b) * cov;
                }
                out[row + x] = 0xFF000000 | (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
            }
        }
        return out;
    }

    static int[] button(int size, float s) {
        return button(size, s, false);
    }

    /**
     * One round button's body and blue ring, centred in a size x size square (size in screen px), transparent
     * outside the ring. Lit from the upper left with a soft gloss, as both buttons are.
     */
    static int[] button(int size, float s, boolean dark) {
        int[] out = new int[size * size];
        float c = size / 2f;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float dx = (x + 0.5f - c) / s, dy = (y + 0.5f - c) / s;
                float r = (float) Math.sqrt(dx * dx + dy * dy);
                float outer = clamp01((BTN_RING + BTN_RING_HALF - r) * s + 0.5f);
                if (outer <= 0f) continue;
                float nx = dx / BODY_R, ny = dy / BODY_R;
                float r2 = nx * nx + ny * ny;
                float[] k = BODY;
                float v = k[0] + k[1] * nx + k[2] * ny + k[3] * nx * nx + k[4] * ny * ny + k[5] * nx * ny
                        + k[6] * nx * nx * nx + k[7] * ny * ny * ny + k[8] * nx * nx * ny + k[9] * nx * ny * ny
                        + k[10] * r2 * r2 + k[11] * r2 * r2 * r2;
                float cr, cg, cb;
                if (dark) {
                    float dv = 46f + (v - 200f) * 0.55f;
                    cr = dv - 2f;
                    cg = dv + 1f;
                    cb = dv + 6f;
                } else {
                    cr = v;
                    cg = v;
                    cb = v;
                }
                float ring = clamp01((BTN_RING_HALF - Math.abs(r - BTN_RING)) * s + 0.5f);
                cr += (0x4D - cr) * ring;
                cg += (0xBD - cg) * ring;
                cb += (0xE3 - cb) * ring;
                int a = Math.round(outer * 255f);
                out[y * size + x] = (a << 24) | (clamp255(cr) << 16) | (clamp255(cg) << 8) | clamp255(cb);
            }
        }
        return out;
    }

    static int[] emptySlot(int tw, int th, float s) {
        return emptySlot(tw, th, s, false);
    }

    /** The frosted grey an empty channel slot shows: darker at the top, faint horizontal grain. */
    static int[] emptySlot(int tw, int th, float s, boolean dark) {
        int[] out = new int[tw * th];
        for (int y = 0; y < th; y++) {
            float t = y / (float) Math.max(1, th - 1);
            float base = dark ? (0x24 + (0x32 - 0x24) * t * t) : (0xCF + (0xDF - 0xCF) * t * t);
            int ry = (int) (y / s);
            float streak = ((hash(ry, 7) & 255) / 255f - 0.5f) * (dark ? 2f : 3f);
            for (int x = 0; x < tw; x++) {
                int rx = (int) (x / s);
                float n = ((hash(rx, ry) & 255) / 255f - 0.5f) * (dark ? 3.5f : 5f);
                int r = clamp255(base + streak + n - (dark ? 2f : 0f));
                int g = clamp255(base + streak + n);
                int b = clamp255(base + streak + n + (dark ? 4f : 0f));
                out[y * tw + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return out;
    }

    private static int hash(int x, int y) {
        int hsh = x * 374761393 + y * 668265263;
        hsh = (hsh ^ (hsh >>> 13)) * 1274126177;
        return hsh ^ (hsh >>> 16);
    }
}
