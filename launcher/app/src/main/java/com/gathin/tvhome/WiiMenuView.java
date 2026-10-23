package com.gathin.tvhome;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.CornerPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.os.Build;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A home screen after the old console channel menu: twelve rounded channel frames to a page with the next
 * page's first column at the edge, a striped backdrop, a curved blue line over a shaded panel holding a
 * segment clock, the date and two round buttons in tracks, and a pointing hand that follows the focus.
 *
 * Geometry is in pixels of a 1920 x 1080 capture of the original ("ref px"), measured off it, and scaled by
 * the view width. The static backdrop and the button bodies are computed per pixel in WiiBase off the UI
 * thread; everything that moves is drawn here. Frames are only drawn while something moves (a live tile counts).
 * On Android 10 and later what holds still is kept in a GPU layer that is re-rendered only when part of it
 * changes; each frame draws that layer and, over it, only what moves.
 */
public class WiiMenuView extends View {

    /** A tile picture that changes over time; the menu keeps redrawing while one is on screen. */
    interface LiveArt {
        boolean animating();
    }

    interface Listener {
        void onChannel(Channel channel);
        /** A channel picked up with a held OK was put down somewhere else; the channel keys in their new order. */
        void onChannelsMoved(List<String> keys);
        void onSettingsButton();
        void onSdButton();
        void onInputsButton();
        /** The welcome screen started to leave: the menu is coming up, as after power-on. */
        void onIntroDismissed();
    }

    static final class Channel {
        final String key;
        final String label;
        final Drawable banner;
        final Drawable icon;
        final Intent intent;
        Bitmap art;
        BitmapShader shader;

        Channel(String key, String label, Drawable banner, Drawable icon, Intent intent) {
            this.key = key;
            this.label = label;
            this.banner = banner;
            this.icon = icon;
            this.intent = intent;
        }
    }

    static final class MenuItem {
        final String label;
        final Runnable action;

        MenuItem(String label, Runnable action) {
            this.label = label;
            this.action = action;
        }
    }

    private static final int SLOTS_PER_PAGE = WiiGrid.PER_PAGE;
    /** Frame interval while only a live tile moves: smooth enough for its slow loop, light on the TV. */
    private static final long LIVE_FRAME_MS = 33L;
    private static final int MIN_PAGES = 4;
    static final int BTN_SETTINGS = -1;
    static final int BTN_SD = -2;
    static final int BTN_INPUTS = -3;
    private static final long INTRO_AUTO_MS = 12_000L;
    /** How often the clock reads the time; it shows minutes, so this only bounds how late a new minute appears. */
    private static final long CLOCK_CHECK_MS = 250L;

    // What a frame draws, in drawing order.
    private static final int IT_BASE = 0, IT_TILE = 1, IT_ARROW_R = 2, IT_ARROW_L = 3, IT_BTN_L = 4, IT_BTN_R = 5,
            IT_SD = 6, IT_DIGITS = 7, IT_COLON = 8, IT_AMPM = 9, IT_DATE = 10, IT_HAND = 11, IT_MENU = 12,
            IT_INTRO = 13;

    // Channel frames: the box through the corner arcs' ends; the sides bow out by TILE_BULGE in the middle.
    private static final float TILE_X0 = 163f;
    private static final float TILE_Y0 = 93.5f;
    private static final float TILE_W = 381f;
    private static final float TILE_H = 204.5f;
    private static final float COL_PITCH = 404f;
    private static final float ROW_PITCH = 228f;
    private static final float PAGE_W = COL_PITCH * 4;
    private static final float TILE_R = 25f;
    private static final float TILE_BULGE = 3f;
    private static final float TILE_BORDER = 4.5f;
    private static final int TILE_BORDER_COLOR = 0xFFB5B5B5;
    /** A channel picked up to move it is drawn this much bigger, over a shadow this many ref px down and right. */
    private static final float CARRY_SCALE = 1.05f;
    private static final float CARRY_SHADOW = 6f;

    private static final float ARROW_L = 1797.5f;
    private static final float ARROW_TIP = 1850f;
    private static final float ARROW_T = 365f;
    private static final float ARROW_B = 462.5f;
    private static final float ARROW_Y = 414f;

    private static final float BTN_RIGHT_CX = 2f * WiiBase.CX - WiiBase.BTN_CX;
    private static final float SD_L = 356f;
    private static final float SD_T = 897f;
    private static final float SD_R = 424f;
    private static final float SD_B = 994f;

    private static final float DIGIT_W = 50f;
    private static final float DIGIT_H = 79f;
    private static final float DIGIT_T = 12f;
    private static final float DIGIT_GAP = 2.5f;
    private static final float CLOCK_TOP = 789f;
    private static final float[] DIGIT_X = {825f, 886f, 983f, 1044f};
    private static final float COLON_X = 959.5f;
    private static final float COLON_DOT = 11f;
    private static final float COLON_Y1 = 811.5f;
    private static final float COLON_Y2 = 844.5f;
    private static final float AMPM_X = 1142f;
    private static final float AMPM_P_W = 24f;
    private static final float AMPM_M_X = 1172f;
    private static final float AMPM_M_W = 28f;
    private static final float AMPM_BASE = 869f;
    private static final float AMPM_CAP = 34f;
    private static final float DATE_TRACKING = 2f;
    private static final float DATE_EXTRA_SPACE = 3f;
    private static final float DATE_BASE = 981f;
    private static final float DATE_CAP = 55f;
    private static final int CLOCK_COLOR = 0xFF9A9A9A;
    private static final int DATE_COLOR = 0xFF7C7C7C;
    /** Fingertip of the hand in the reference capture. */
    private static final float HAND_REF_X = 1072f;
    private static final float HAND_REF_Y = 894f;

    private static final int BLUE = 0xFF3FC0EC;
    /** Segment bits a-g per digit; the original's 7 keeps the upper-left bar. */
    private static final int[] SEGMENTS = {0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x27, 0x7F, 0x6F};
    /** The segment bits in the order digitSegs holds and draws them: a, d, f, b, e, c, g. */
    private static final int[] SEGMENT_ORDER = {0x01, 0x08, 0x20, 0x02, 0x10, 0x04, 0x40};
    /** The previous version's hand was drawn in units of 1/1024 of the width. */
    private static final float HAND_UNIT = 1920f / 1024f;

    private final Listener listener;
    private final List<Channel> channels = new ArrayList<>();
    private int pages = MIN_PAGES;
    private int focus;
    private int page;
    private float pagePos;
    private float[] lift = new float[MIN_PAGES * SLOTS_PER_PAGE + 3];
    private float handX, handY;
    /** Set each frame when a live tile is on screen. */
    private boolean liveShown;
    private boolean handPlaced;
    private boolean handMoving;
    private long lastFrameNanos;
    private boolean started;
    private boolean colonOn = true;
    private boolean refPose;
    private String settingsLabel = "wiiOS";
    private WiiSounds sounds;

    private float introAlpha;
    private boolean introLeaving;
    private long introStartMs;
    private String introTitle = "";
    private String introBodyText = "";
    private String introContinue = "";
    private StaticLayout introBody;

    private List<MenuItem> menuItems = new ArrayList<>();
    private String menuTitle = "";
    private int menuFocus;
    private float menuAlpha;
    private boolean menuOpen;
    private float[] menuLift = new float[0];

    /** OK went down on the grid and has not come up yet: it opens on the way up, unless held to pick up. */
    private boolean okHeld;
    /** The focused channel is picked up: the arrows carry it, OK or Back puts it down. */
    private boolean carrying;
    /** The channel order when it was picked up, to save only when it moved. */
    private List<String> orderBefore;

    private float s = 1f;
    private int w = 1, h = 1;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint artPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    /** Paints the backdrop back around a live tile; aliased, which the GPU fills without building a mask. */
    private final Paint under = new Paint();
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF tmp = new RectF();
    private final Matrix matrix = new Matrix();
    private final float[] point = new float[2];
    private final android.graphics.Rect inkBounds = new android.graphics.Rect();
    private final Typeface face;
    private final Typeface faceBold;
    private final Typeface sansBold = Typeface.create("sans-serif", Typeface.BOLD);
    private final Typeface sdFace = Typeface.create(sansBold, Typeface.BOLD_ITALIC);

    private volatile int buildGen;
    private Bitmap base, btnLeft, btnRight, emptyArt;
    private BitmapShader emptyShader, baseShader, underShader;
    private Path tilePath, handPath, handLines;
    /** Everything outside a channel frame, for trimming a live tile to the frame. */
    private Path tileOutside;
    /** Outlines with their corners already rounded: the shapes a CornerPathEffect gives, worked out once. */
    private Path arrowRound, sdRound, warnRound;
    /** Per clock position, its seven segments in SEGMENT_ORDER. */
    private Path[][] digitSegs;
    private LinearGradient arrowBody, arrowShine;
    private LinearGradient[] menuShaders;

    private int clockHour = 12, clockMinute;
    private boolean clockPm, clockStale = true;
    /** The TV's 24-hour setting: hours 0-23 and no AM/PM, as on the console's PAL menu. */
    private boolean clock24;
    private long clockAtMs;
    private String dateText = "", dateDay = "", dateRest = "";
    private float dateRestW, dateTotal, dateScale;
    private int dateKey = Integer.MIN_VALUE;
    private Locale dateLocale;

    // This frame's parts in drawing order, with screen bounds and whether each is drawn straight into the frame.
    private int itemCount;
    private int[] itemKind = new int[0], itemArg = new int[0];
    private boolean[] itemDirect = new boolean[0], itemLive = new boolean[0];
    private float[] itemBox = new float[0];
    /** What the still layer holds, to tell when it must be recorded again. */
    private int[] sig = new int[0], lastSig = new int[0];
    private int lastSigLen = -1;
    /** Bumped when something the still layer draws changes in a way the signature cannot see. */
    private int stillGen;
    private Still still;

    private final Runnable blink = new Runnable() {
        @Override
        public void run() {
            colonOn = !colonOn;
            invalidate();
            if (started && !refPose) postDelayed(this, 1000);
        }
    };
    private final Runnable autoContinue = this::dismissIntro;
    private final Runnable hoverSound = () -> {
        if (started && hasWindowFocus() && !refPose && !isIntroShowing() && !menuOpen
                && !carrying && !okHeld
                && focus >= 0 && focus < channels.size()) sound(WiiSounds.WOOP);
    };

    private void scheduleHoverSound() {
        removeCallbacks(hoverSound);
        int delay = getResources().getInteger(R.integer.wii_hover_delay_ms);
        if (delay > 0 && started && !refPose && !isIntroShowing() && !menuOpen
                && !carrying && !okHeld
                && focus >= 0 && focus < channels.size()) postDelayed(hoverSound, delay);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) scheduleHoverSound();
        else removeCallbacks(hoverSound);
    }
    private final Runnable okHeldLong = this::pickUp;

    WiiMenuView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        Typeface f;
        try {
            f = context.getResources().getFont(R.font.outfit_medium);
        } catch (RuntimeException e) {
            f = Typeface.SANS_SERIF;
        }
        face = f;
        faceBold = weight(context, f, 680);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    /** The variable font at a real weight; Typeface.create(f, BOLD) would only smear the 500 outlines. */
    static Typeface weight(Context context, Typeface fallback, int wght) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                Font font = new Font.Builder(context.getResources(), R.font.outfit)
                        .setFontVariationSettings("'wght' " + wght)
                        .setWeight(Math.min(1000, Math.max(1, wght)))
                        .build();
                return new Typeface.CustomFallbackBuilder(new FontFamily.Builder(font).build()).build();
            } catch (Exception e) {
                // fall through
            }
        }
        return Typeface.create(fallback, Typeface.BOLD);
    }

    // ---- state -----------------------------------------------------------------------------------

    void setChannels(List<Channel> list) {
        // An app installed or removed mid-carry: the new list wins, the carry so far is dropped unsaved.
        carrying = false;
        okHeld = false;
        removeCallbacks(okHeldLong);
        orderBefore = null;
        channels.clear();
        channels.addAll(list);
        pages = Math.max(MIN_PAGES, (channels.size() + SLOTS_PER_PAGE - 1) / SLOTS_PER_PAGE);
        int n = pages * SLOTS_PER_PAGE + 3;
        if (lift.length != n) lift = new float[n];
        if (focus >= pages * SLOTS_PER_PAGE) focus = 0;
        if (page >= pages) {
            page = 0;
            pagePos = 0f;
        }
        stillGen++;
        invalidate();
        scheduleHoverSound();
    }

    void setIntroText(String title, String body, String cont) {
        introTitle = title;
        introBodyText = body;
        introContinue = cont;
        introBody = null;
    }

    void setSettingsLabel(String label) {
        settingsLabel = label;
        rebuild();
    }

    /** Freezes the screen in the state of the reference capture (2:07 PM, Fri 3/22, hand on the date). */
    void setRefPose(boolean on) {
        removeCallbacks(hoverSound);
        refPose = on;
        if (on) {
            colonOn = false;
            focus = Integer.MIN_VALUE;
            introAlpha = 0f;
            introLeaving = false;
            handPlaced = false;
        }
        stillGen++;
        clockStale = true;
        invalidate();
    }

    void start() {
        if (started) return;
        started = true;
        lastFrameNanos = 0;
        // Changing the setting means leaving for Settings, so a read on every return is enough.
        clock24 = android.text.format.DateFormat.is24HourFormat(getContext());
        clockStale = true;
        removeCallbacks(blink);
        if (!refPose) postDelayed(blink, 1000);
        if (introAlpha > 0f && !introLeaving) postDelayed(autoContinue, INTRO_AUTO_MS);
        scheduleHoverSound();
        invalidate();
    }

    void stop() {
        started = false;
        removeCallbacks(blink);
        removeCallbacks(autoContinue);
        removeCallbacks(hoverSound);
        okHeld = false;
        removeCallbacks(okHeldLong);
        if (carrying) putDown(false);
    }

    void playIntro() {
        if (refPose) return;
        removeCallbacks(hoverSound);
        menuOpen = false;
        menuAlpha = 0f;
        introAlpha = 1f;
        introLeaving = false;
        introStartMs = SystemClock.uptimeMillis();
        removeCallbacks(autoContinue);
        postDelayed(autoContinue, INTRO_AUTO_MS);
        lastFrameNanos = 0;
        invalidate();
    }

    void dismissIntro() {
        if (introAlpha <= 0f || introLeaving) return;
        introLeaving = true;
        removeCallbacks(autoContinue);
        listener.onIntroDismissed();
        scheduleHoverSound();
        invalidate();
    }

    boolean isIntroShowing() {
        return introAlpha > 0f && !introLeaving;
    }

    void showMenu(String title, List<MenuItem> items) {
        if (items.isEmpty()) return;
        removeCallbacks(hoverSound);
        menuTitle = title;
        menuItems = new ArrayList<>(items);
        menuFocus = 0;
        menuLift = new float[menuItems.size()];
        menuShaders = null;
        menuOpen = true;
        invalidate();
    }

    void closeMenu() {
        menuOpen = false;
        scheduleHoverSound();
        invalidate();
    }

    /** Back: closes the menu or the welcome screen, or puts a carried channel down. False when there was nothing. */
    boolean back() {
        if (introAlpha > 0f) {
            dismissIntro();
            return true;
        }
        if (carrying) {
            putDown(true);
            return true;
        }
        if (menuOpen) {
            sound(WiiSounds.BACK);
            closeMenu();
            return true;
        }
        return false;
    }

    /** Home pressed with the menu already on screen: closes the list, or else goes back to the first channel. */
    void home() {
        if (carrying) {
            putDown(true);
            return;
        }
        if (menuOpen) {
            closeMenu();
            return;
        }
        if (refPose || introAlpha > 0f) return;
        int before = focus, pageBefore = page;
        page = 0;
        focus = 0;
        if (focus != before || page != pageBefore) {
            sound(page != pageBefore ? WiiSounds.PAGE : WiiSounds.MOVE);
            scheduleHoverSound();
            invalidate();
        }
    }

    void setSounds(WiiSounds sounds) {
        this.sounds = sounds;
    }

    private void sound(int which) {
        if (sounds != null) sounds.play(which);
    }

    // ---- keys ------------------------------------------------------------------------------------

    private static boolean isOk(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A;
    }

    private static boolean isDpad(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    boolean handleKey(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE || keyCode == KeyEvent.KEYCODE_BACK) {
            return false;
        }
        if (refPose) {
            if (!isOk(keyCode) && !isDpad(keyCode)) return false;
            refPose = false;
            focus = 0;
            clockStale = true;
            removeCallbacks(blink);
            postDelayed(blink, 1000);
            invalidate();
            return true;
        }
        if (introAlpha > 0f) {
            // Only OK and the arrows continue: the power or wake-up key that turned the TV on also arrives
            // here, and must not skip the screen it just brought up.
            if (!isOk(keyCode) && !isDpad(keyCode)) return false;
            if (!introLeaving) {
                dismissIntro();
            }
            return true;
        }
        if (menuOpen) return menuKey(keyCode);
        if (carrying) return carryKey(keyCode, event.getRepeatCount());
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                move(0, 1);
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                turnPage(1);
                return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                turnPage(-1);
                return true;
            default:
                if (isOk(keyCode)) {
                    okDown(event);
                    return true;
                }
                return false;
        }
    }

    /** OK opens on the way up, so that holding it can pick the channel up instead. */
    boolean handleKeyUp(int keyCode) {
        if (!isOk(keyCode) || !okHeld) return false;
        okHeld = false;
        removeCallbacks(okHeldLong);
        if (!carrying && !menuOpen && introAlpha <= 0f) activate();
        return true;
    }

    private void move(int dx, int dy) {
        int before = focus, pageBefore = page;
        if (focus >= 0) {
            int next = WiiGrid.neighbor(focus, dx, dy, pages);
            if (next >= 0) {
                focus = next;
                page = next / SLOTS_PER_PAGE;
            } else if (dy > 0) {
                int col = focus % WiiGrid.COLS;
                focus = col == 0 ? BTN_SETTINGS : col == 1 ? BTN_SD : BTN_INPUTS;
            }
        } else if (dy < 0) {
            int col = focus == BTN_SETTINGS ? 0 : focus == BTN_SD ? 1 : 3;
            focus = page * SLOTS_PER_PAGE + 8 + col;
        } else if (dx < 0) {
            if (focus == BTN_SD) focus = BTN_SETTINGS;
            else if (focus == BTN_INPUTS) focus = BTN_SD;
        } else if (dx > 0) {
            if (focus == BTN_SETTINGS) focus = BTN_SD;
            else if (focus == BTN_SD) focus = BTN_INPUTS;
        }
        if (focus != before) {
            sound(page != pageBefore ? WiiSounds.PAGE : WiiSounds.MOVE);
            scheduleHoverSound();
            invalidate();
        }
    }

    /** A whole page at a time; the hand keeps its place in the grid. */
    private void turnPage(int dir) {
        int to = page + dir;
        if (to < 0 || to >= pages) return;
        page = to;
        if (focus >= 0) focus = to * SLOTS_PER_PAGE + focus % SLOTS_PER_PAGE;
        sound(WiiSounds.PAGE);
        scheduleHoverSound();
        invalidate();
    }

    private void activate() {
        removeCallbacks(hoverSound);
        boolean bigButton = focus == BTN_SETTINGS || focus == BTN_SD || focus == BTN_INPUTS;
        sound(bigButton ? WiiSounds.BIG_BUTTON : WiiSounds.SELECT);
        if (focus == BTN_SETTINGS) listener.onSettingsButton();
        else if (focus == BTN_SD) listener.onSdButton();
        else if (focus == BTN_INPUTS) listener.onInputsButton();
        else if (focus >= 0 && focus < channels.size()) listener.onChannel(channels.get(focus));
    }

    // ---- moving channels: hold OK on one, carry it with the arrows, OK or Back puts it down ----------

    private void okDown(KeyEvent event) {
        long hold = ViewConfiguration.getLongPressTimeout();
        if (event.getRepeatCount() == 0) {
            removeCallbacks(hoverSound);
            okHeld = true;
            removeCallbacks(okHeldLong);
            if (focus >= 0 && focus < channels.size()) postDelayed(okHeldLong, hold);
        } else if (okHeld && event.getEventTime() - event.getDownTime() >= hold) {
            // The remote's own repeat can come before the timer: a held key is a held key either way.
            pickUp();
        }
    }

    private void pickUp() {
        removeCallbacks(okHeldLong);
        if (!okHeld || carrying || !started || focus < 0 || focus >= channels.size()) return;
        removeCallbacks(hoverSound);
        carrying = true;
        orderBefore = channelKeys();
        sound(WiiSounds.SELECT);
        stillGen++;
        invalidate();
    }

    private boolean carryKey(int keyCode, int repeat) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                carryTo(WiiGrid.neighbor(focus, -1, 0, pages));
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                carryTo(WiiGrid.neighbor(focus, 1, 0, pages));
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                carryTo(WiiGrid.neighbor(focus, 0, -1, pages));
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                carryTo(WiiGrid.neighbor(focus, 0, 1, pages));
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                carryTo(focus + SLOTS_PER_PAGE);
                return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                carryTo(focus - SLOTS_PER_PAGE);
                return true;
            default:
                if (isOk(keyCode)) {
                    // The OK that picked it up may still be held and repeating: only a new press puts it down.
                    if (repeat == 0) putDown(true);
                    return true;
                }
                return false;
        }
    }

    /** Swaps the carried channel with the one at slot to. Channels fill the slots in order, so the empty ones stop it. */
    private void carryTo(int to) {
        if (to < 0 || to >= channels.size() || to == focus) return;
        int pageBefore = page;
        Collections.swap(channels, focus, to);
        focus = to;
        page = to / SLOTS_PER_PAGE;
        sound(page != pageBefore ? WiiSounds.PAGE : WiiSounds.MOVE);
        stillGen++;
        invalidate();
    }

    private void putDown(boolean withSound) {
        if (!carrying) return;
        carrying = false;
        if (withSound) sound(WiiSounds.SELECT);
        stillGen++;
        invalidate();
        List<String> keys = channelKeys();
        if (!keys.equals(orderBefore)) listener.onChannelsMoved(keys);
        orderBefore = null;
        scheduleHoverSound();
    }

    private List<String> channelKeys() {
        List<String> keys = new ArrayList<>(channels.size());
        for (Channel ch : channels) keys.add(ch.key);
        return keys;
    }

    // ---- where the hand is, kept for when Android closes the menu while an app runs ------------------

    /** The channel under the hand, or null on an empty slot or a button. */
    String handChannel() {
        return focus >= 0 && focus < channels.size() ? channels.get(focus).key : null;
    }

    /** The slot or button under the hand; Integer.MIN_VALUE in the reference pose, which has none. */
    int handSlot() {
        return focus;
    }

    int handPage() {
        return page;
    }

    /**
     * Puts the hand back where it was, with no animation: on the same channel wherever it is now, or else on
     * the same slot or button. Goes after setChannels.
     */
    void placeHand(String channel, int slot, int pg) {
        if (refPose) return;
        if (channel != null) {
            for (int i = 0; i < channels.size(); i++) {
                if (channels.get(i).key.equals(channel)) {
                    slot = i;
                    break;
                }
            }
        }
        boolean button = slot == BTN_SETTINGS || slot == BTN_SD || slot == BTN_INPUTS;
        if (!button && (slot < 0 || slot >= pages * SLOTS_PER_PAGE)) return;
        focus = slot;
        page = button ? Math.max(0, Math.min(pg, pages - 1)) : slot / SLOTS_PER_PAGE;
        pagePos = page;
        handPlaced = false;
        stillGen++;
        scheduleHoverSound();
        invalidate();
    }

    private boolean menuKey(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (menuFocus > 0) {
                menuFocus--;
                sound(WiiSounds.MOVE);
                invalidate();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (menuFocus < menuItems.size() - 1) {
                menuFocus++;
                sound(WiiSounds.MOVE);
                invalidate();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
        if (isOk(keyCode)) {
            MenuItem item = menuItems.get(menuFocus);
            sound(WiiSounds.SELECT);
            closeMenu();
            item.action.run();
            return true;
        }
        return false;
    }

    // ---- geometry --------------------------------------------------------------------------------

    private int liftIndex(int f) {
        return f >= 0 ? f : pages * SLOTS_PER_PAGE + (-f - 1);
    }

    private void slotRect(int slot, float pos, RectF out) {
        int pg = slot / SLOTS_PER_PAGE, i = slot % SLOTS_PER_PAGE, row = i / 4, col = i % 4;
        float left = (TILE_X0 + (pg * 4 + col) * COL_PITCH - pos * PAGE_W) * s;
        float top = (TILE_Y0 + row * ROW_PITCH) * s;
        out.set(left, top, left + TILE_W * s, top + TILE_H * s);
    }

    /** Where the hand's fingertip goes for this focus. */
    private void handTarget(int f, float[] out) {
        if (refPose || f == Integer.MIN_VALUE) {
            out[0] = HAND_REF_X * s;
            out[1] = HAND_REF_Y * s;
        } else if (f >= 0) {
            slotRect(f, page, tmp);
            out[0] = tmp.left + tmp.width() * 0.70f;
            out[1] = tmp.top + tmp.height() * 0.80f;
        } else if (f == BTN_SD) {
            out[0] = ((SD_L + SD_R) / 2f + 10f) * s;
            out[1] = ((SD_T + SD_B) / 2f + 14f) * s;
        } else {
            float cx = f == BTN_SETTINGS ? WiiBase.BTN_CX : BTN_RIGHT_CX;
            out[0] = (cx + 26f) * s;
            out[1] = (WiiBase.BTN_CY + 22f) * s;
        }
    }

    /** Where the focused channel or button is on screen, for an opening app to grow out of. */
    boolean focusBounds(android.graphics.Rect out) {
        if (focus >= 0) {
            slotRect(focus, pagePos, tmp);
        } else if (focus == BTN_SD) {
            tmp.set(SD_L * s, SD_T * s, SD_R * s, SD_B * s);
        } else if (focus == BTN_SETTINGS || focus == BTN_INPUTS) {
            float cx = (focus == BTN_SETTINGS ? WiiBase.BTN_CX : BTN_RIGHT_CX) * s, cy = WiiBase.BTN_CY * s;
            float r = WiiBase.BTN_RING * s;
            tmp.set(cx - r, cy - r, cx + r, cy + r);
        } else {
            return false;
        }
        tmp.round(out);
        return true;
    }

    @Override
    protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        w = Math.max(1, nw);
        h = Math.max(1, nh);
        s = w / 1920f;
        tilePath = buildTilePath(TILE_W * s, TILE_H * s, TILE_R * s, TILE_BULGE * s);
        tileOutside = new Path(tilePath);
        tileOutside.setFillType(Path.FillType.INVERSE_WINDING);
        arrowRound = rounded(buildArrow(), new CornerPathEffect(7f * s));
        arrowBody = new LinearGradient(0f, (ARROW_T - ARROW_Y) * s, 0f, (ARROW_B - ARROW_Y) * s,
                new int[] {0xFF6FCDF3, 0xFFA9E6FB, 0xFFDDF8FF, 0xFF9FE0F8, 0xFF62C5F0},
                new float[] {0f, 0.35f, 0.5f, 0.62f, 1f}, Shader.TileMode.CLAMP);
        arrowShine = new LinearGradient((ARROW_L - ARROW_TIP) * s, 0f, 0f, 0f, 0x66FFFFFF, 0x00FFFFFF,
                Shader.TileMode.CLAMP);
        digitSegs = buildDigits(new CornerPathEffect(2.5f * s));
        buildHand();
        sdRound = rounded(buildSd(), new CornerPathEffect(4f * s));
        warnRound = buildWarning();
        menuShaders = null;
        clockStale = true;
        stillGen++;
        introBody = null;
        for (Channel c : channels) {
            c.art = null;
            c.shader = null;
        }
        handPlaced = false;
        rebuild();
    }

    /** Recomputes the per-pixel layers off the UI thread; until they arrive the screen shows plain grey. */
    private void rebuild() {
        if (w <= 1 || h <= 1) return;
        final int gen = ++buildGen;
        final int bw = w, bh = h;
        final float bs = s;
        final String label = settingsLabel;
        new Thread(() -> {
            int[] px = WiiBase.backdrop(bw, bh);
            Bitmap bg = Bitmap.createBitmap(px, bw, bh, Bitmap.Config.ARGB_8888);
            int size = (int) Math.ceil(2f * (WiiBase.BTN_RING + WiiBase.BTN_RING_HALF + 2f) * bs);
            int[] bp = WiiBase.button(size, bs);
            Bitmap left = Bitmap.createBitmap(bp, size, size, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true);
            Bitmap right = left.copy(Bitmap.Config.ARGB_8888, true);
            drawWordmark(new Canvas(left), size, bs, label);
            drawEnvelope(new Canvas(right), size, bs);
            int tw = Math.round(TILE_W * bs) + 8, th = Math.round(TILE_H * bs) + 8;
            Bitmap empty = Bitmap.createBitmap(WiiBase.emptySlot(tw, th, bs), tw, th, Bitmap.Config.ARGB_8888);
            post(() -> {
                if (gen != buildGen) return;
                base = bg;
                btnLeft = left;
                btnRight = right;
                emptyArt = empty;
                emptyShader = new BitmapShader(empty, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                baseShader = new BitmapShader(bg, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                underShader = new BitmapShader(bg, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                stillGen++;
                invalidate();
            });
        }, "wii-base").start();
    }

    private void drawWordmark(Canvas c, int size, float bs, String label) {
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setTypeface(sansBold);
        tp.setTextAlign(Paint.Align.CENTER);
        float maxW = 128f * bs;
        tp.setTextSize(52f * bs);
        float tw = tp.measureText(label);
        if (tw > maxW) tp.setTextSize(52f * bs * maxW / tw);
        float y = size / 2f + 17f * bs;
        tp.setColor(0x99FFFFFF);
        c.drawText(label, size / 2f, y + 2f * bs, tp);
        tp.setColor(0xFFA4A4A4);
        c.drawText(label, size / 2f, y, tp);
    }

    private void drawEnvelope(Canvas c, int size, float bs) {
        float cx = size / 2f, cy = size / 2f;
        float ew = 97f * bs, eh = 65f * bs;
        RectF r = new RectF(cx - ew / 2f, cy - eh / 2f, cx + ew / 2f, cy + eh / 2f);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF939393);
        c.drawRoundRect(r, 5f * bs, 5f * bs, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4.5f * bs);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(0xFFE2E2E2);
        Path v = new Path();
        v.moveTo(r.left + 2f * bs, r.top + 14f * bs);
        v.lineTo(cx, r.top + 38f * bs);
        v.lineTo(r.right - 2f * bs, r.top + 14f * bs);
        c.drawPath(v, p);
    }

    /** A channel frame: rounded corners joined by sides that bow out by b in the middle. */
    private static Path buildTilePath(float tw, float th, float r, float b) {
        float k = r * 0.4477f;
        Path p = new Path();
        p.moveTo(r, 0f);
        p.quadTo(tw / 2f, -2f * b, tw - r, 0f);
        p.cubicTo(tw - k, 0f, tw, k, tw, r);
        p.quadTo(tw + 2f * b, th / 2f, tw, th - r);
        p.cubicTo(tw, th - k, tw - k, th, tw - r, th);
        p.quadTo(tw / 2f, th + 2f * b, r, th);
        p.cubicTo(k, th, 0f, th - k, 0f, th - r);
        p.quadTo(-2f * b, th / 2f, 0f, r);
        p.cubicTo(0f, k, k, 0f, r, 0f);
        p.close();
        return p;
    }

    /** Right-pointing arrow around its tip at (0, 0); the back edge curves in toward the tip. */
    private Path buildArrow() {
        float l = (ARROW_L - ARROW_TIP) * s, t = (ARROW_T - ARROW_Y) * s, b = (ARROW_B - ARROW_Y) * s;
        Path p = new Path();
        p.moveTo(0f, 0f);
        p.lineTo(l, t);
        p.quadTo(l + 12f * s, 0f, l, b);
        p.close();
        return p;
    }

    /**
     * The pointing hand, fingertip at (0, 0): index finger up, three curled fingers, palm and thumb, in hand
     * units of HAND_UNIT ref px.
     */
    private void buildHand() {
        float u = HAND_UNIT * s;
        Path p = new Path();
        p.addRoundRect(new RectF(0f, 0f, 11f * u, 34f * u), 5.5f * u, 5.5f * u, Path.Direction.CW);
        union(p, u, 10f, 16f, 19f, 34f, 4.5f);
        union(p, u, 18f, 19f, 26f, 36f, 4f);
        union(p, u, 25f, 22f, 32f, 38f, 3.5f);
        union(p, u, -3f, 26f, 32f, 50f, 9f);
        union(p, u, -11f, 25f, 4f, 37f, 6f);
        Path lines = new Path();
        lines.moveTo(10.6f * u, 23f * u);
        lines.lineTo(10.6f * u, 31f * u);
        lines.moveTo(18.4f * u, 25f * u);
        lines.lineTo(18.4f * u, 33f * u);
        lines.moveTo(25.6f * u, 28f * u);
        lines.lineTo(25.6f * u, 35f * u);
        lines.moveTo(3f * u, 32f * u);
        lines.lineTo(9f * u, 34f * u);
        Matrix m = new Matrix();
        m.setTranslate(-5.5f * u, 0f);
        p.transform(m);
        lines.transform(m);
        handPath = p;
        handLines = lines;
    }

    private static void union(Path p, float u, float l, float t, float r, float b, float rad) {
        Path q = new Path();
        q.addRoundRect(new RectF(l * u, t * u, r * u, b * u), rad * u, rad * u, Path.Direction.CW);
        p.op(q, Path.Op.UNION);
    }

    /** The outline a fill with these corners draws, as a plain path. */
    private static Path rounded(Path src, CornerPathEffect corners) {
        Paint p = new Paint();
        p.setPathEffect(corners);
        Path out = new Path();
        p.getFillPath(src, out);
        return out;
    }

    /**
     * The clock's digit segments as on the original: the outer ones are mitred into the box corners, the ones
     * that meet the middle bar end in a point, and every joint keeps a small gap.
     */
    private Path[][] buildDigits(CornerPathEffect corners) {
        Path[][] out = new Path[DIGIT_X.length][];
        float t = DIGIT_T, g = DIGIT_GAP, k = t / 2f;
        float ya = CLOCK_TOP, yb = CLOCK_TOP + DIGIT_H, ym = CLOCK_TOP + DIGIT_H / 2f;
        for (int i = 0; i < DIGIT_X.length; i++) {
            float xa = DIGIT_X[i], xb = DIGIT_X[i] + DIGIT_W;
            out[i] = new Path[] {
                    rounded(poly(xa + g, ya, xb - g, ya, xb - t - g, ya + t, xa + t + g, ya + t), corners),
                    rounded(poly(xa + t + g, yb - t, xb - t - g, yb - t, xb - g, yb, xa + g, yb), corners),
                    rounded(poly(xa, ya + g, xa + t, ya + t + g, xa + t, ym - k - g, xa + k, ym - g, xa, ym - k - g),
                            corners),
                    rounded(poly(xb, ya + g, xb, ym - k - g, xb - k, ym - g, xb - t, ym - k - g, xb - t, ya + t + g),
                            corners),
                    rounded(poly(xa, ym + k + g, xa + k, ym + g, xa + t, ym + k + g, xa + t, yb - t - g, xa, yb - g),
                            corners),
                    rounded(poly(xb, ym + k + g, xb, yb - g, xb - t, yb - t - g, xb - t, ym + k + g, xb - k, ym + g),
                            corners),
                    rounded(poly(xa + k + g, ym, xa + t + g, ym - k, xb - t - g, ym - k, xb - k - g, ym, xb - t - g,
                            ym + k, xa + t + g, ym + k), corners),
            };
        }
        return out;
    }

    /** A closed polygon through the given ref-px points, in screen px. */
    private Path poly(float... pts) {
        Path p = new Path();
        p.moveTo(pts[0] * s, pts[1] * s);
        for (int i = 2; i < pts.length; i += 2) p.lineTo(pts[i] * s, pts[i + 1] * s);
        p.close();
        return p;
    }

    /** The welcome screen's warning triangle, where drawIntro puts it. */
    private Path buildWarning() {
        float left = 240f * s, headY = 340f * s;
        float cx = left + 34f * s, cy = headY - 22f * s, r = 38f * s;
        Path p = new Path();
        p.moveTo(cx, cy - r);
        p.lineTo(cx + r * 1.1f, cy + r * 0.85f);
        p.lineTo(cx - r * 1.1f, cy + r * 0.85f);
        p.close();
        return rounded(p, new CornerPathEffect(6f * s));
    }

    private Path buildSd() {
        float l = SD_L * s, t = SD_T * s, r = SD_R * s, b = SD_B * s, cut = 9f * s;
        Path p = new Path();
        p.moveTo(l, t);
        p.lineTo(r - cut, t);
        p.lineTo(r, t + cut);
        p.lineTo(r, b);
        p.lineTo(l, b);
        p.close();
        return p;
    }

    // ---- animation -------------------------------------------------------------------------------

    private static float approach(float v, float target, float k, float eps) {
        float n = v + (target - v) * k;
        return Math.abs(target - n) < eps ? target : n;
    }

    /** Advances every animated value by dt seconds; true while anything is still moving. */
    private boolean step(float dt) {
        float kPage = 1f - (float) Math.exp(-dt / 0.11f);
        float kLift = 1f - (float) Math.exp(-dt / 0.07f);
        float kHand = 1f - (float) Math.exp(-dt / 0.06f);
        float kMenu = 1f - (float) Math.exp(-dt / 0.08f);

        pagePos = approach(pagePos, page, kPage, 0.0008f);
        boolean moving = pagePos != page;

        int fi = focus == Integer.MIN_VALUE ? -1000 : liftIndex(focus);
        for (int i = 0; i < lift.length; i++) {
            float t = i == fi ? 1f : 0f;
            lift[i] = approach(lift[i], t, kLift, 0.002f);
            if (lift[i] != t) moving = true;
        }

        handTarget(focus, point);
        if (!handPlaced) {
            handX = point[0];
            handY = point[1];
            handPlaced = true;
        }
        handX = approach(handX, point[0], kHand, 0.3f);
        handY = approach(handY, point[1], kHand, 0.3f);
        handMoving = handX != point[0] || handY != point[1];
        if (handMoving) moving = true;

        float mt = menuOpen ? 1f : 0f;
        menuAlpha = approach(menuAlpha, mt, kMenu, 0.004f);
        if (menuAlpha != mt) moving = true;
        for (int i = 0; i < menuLift.length; i++) {
            float t = menuOpen && i == menuFocus ? 1f : 0f;
            menuLift[i] = approach(menuLift[i], t, kLift, 0.002f);
            if (menuLift[i] != t) moving = true;
        }

        if (introLeaving) {
            introAlpha = approach(introAlpha, 0f, 1f - (float) Math.exp(-dt / 0.2f), 0.004f);
            if (introAlpha == 0f) introLeaving = false;
            else moving = true;
        }
        return moving;
    }

    // ---- drawing ---------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0 ? 1f / 60f : Math.min(0.05f, (now - lastFrameNanos) / 1e9f);
        lastFrameNanos = now;
        boolean moving = step(dt);

        if (clockStale || SystemClock.uptimeMillis() - clockAtMs >= CLOCK_CHECK_MS) updateClock();
        collectItems();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && c.isHardwareAccelerated()) {
            markDirect();
            if (still == null) still = new Still();
            if (signatureChanged() | !still.valid()) {
                Canvas rc = still.begin(w, h);
                for (int i = 0; i < itemCount; i++) if (!itemDirect[i]) drawItem(rc, i);
                still.end();
            }
            // Drawn even under the opaque welcome screen: a node left out of a frame loses its layer, and the
            // fade-out would then start with a full re-render.
            still.draw(c);
            boolean covered = Math.round(introAlpha * 255) >= 255;
            for (int i = 0; i < itemCount; i++) {
                if (itemDirect[i] && (!covered || itemKind[i] == IT_INTRO)) drawItem(c, i);
            }
        } else {
            for (int i = 0; i < itemCount; i++) drawItem(c, i);
        }

        if (moving || introAlpha > 0f) postInvalidateOnAnimation();
        else if (liveShown) postInvalidateDelayed(LIVE_FRAME_MS);
        else lastFrameNanos = 0;
    }

    /** Lets go of the still layer while the menu is hidden; the next frame records it again. */
    void trimMemory() {
        if (still != null) still.discard();
        lastSigLen = -1;
        invalidate();
    }

    /** Lists this frame's parts in drawing order, with their screen bounds and whether each moves. */
    private void collectItems() {
        int cap = pages * SLOTS_PER_PAGE + 16;
        if (itemKind.length < cap) {
            itemKind = new int[cap];
            itemArg = new int[cap];
            itemDirect = new boolean[cap];
            itemLive = new boolean[cap];
            itemBox = new float[cap * 4];
        }
        itemCount = 0;
        liveShown = false;
        add(IT_BASE, 0, 0f, 0f, w, h, false);
        boolean scrolling = pagePos != page;
        int total = pages * SLOTS_PER_PAGE;
        for (int sl = 0; sl < total; sl++) {
            if (sl == focus) continue;
            slotRect(sl, pagePos, rect);
            if (rect.right < -10f * s || rect.left > w + 10f * s) continue;
            addTile(sl, scrolling);
        }
        if (focus >= 0) {
            slotRect(focus, pagePos, rect);
            if (rect.right >= 0 && rect.left <= w) addTile(focus, scrolling);
        }
        float cx2 = 2f * WiiBase.CX;
        if (page < pages - 1) {
            add(IT_ARROW_R, 0, (ARROW_L - 4f) * s, (ARROW_T - 4f) * s, (ARROW_TIP + 4f) * s, (ARROW_B + 4f) * s,
                    false);
        }
        if (page > 0) {
            add(IT_ARROW_L, 0, (cx2 - ARROW_TIP - 4f) * s, (ARROW_T - 4f) * s, (cx2 - ARROW_L + 4f) * s,
                    (ARROW_B + 4f) * s, false);
        }
        if (btnLeft != null) addButton(IT_BTN_L, WiiBase.BTN_CX, BTN_SETTINGS);
        if (btnRight != null) addButton(IT_BTN_R, BTN_RIGHT_CX, BTN_INPUTS);
        float lf = lift[liftIndex(BTN_SD)], sc = 1f + 0.08f * lf;
        float cx = (SD_L + SD_R) / 2f * s, cy = (SD_T + SD_B) / 2f * s;
        float hw = ((SD_R - SD_L) / 2f + 12f) * s * sc, hh = ((SD_B - SD_T) / 2f + 12f) * s * sc;
        add(IT_SD, 0, cx - hw, cy - hh, cx + hw, cy + hh, settling(lf));
        add(IT_DIGITS, 0, (DIGIT_X[0] - 4f) * s, (CLOCK_TOP - 4f) * s, (DIGIT_X[3] + DIGIT_W + 4f) * s,
                (CLOCK_TOP + DIGIT_H + 4f) * s, false);
        if (colonOn) {
            float d = COLON_DOT / 2f + 2f;
            add(IT_COLON, 0, (COLON_X - d) * s, (COLON_Y1 - d) * s, (COLON_X + d) * s, (COLON_Y2 + d) * s, true);
        }
        if (refPose || !clock24) {
            add(IT_AMPM, 0, (AMPM_X - 6f) * s, (AMPM_BASE - AMPM_CAP - 8f) * s, (AMPM_M_X + AMPM_M_W + 6f) * s,
                    (AMPM_BASE + 8f) * s, false);
        }
        float half = dateTotal / 2f + 6f * s;
        add(IT_DATE, 0, WiiBase.CX * s - half, (DATE_BASE - DATE_CAP - 14f) * s, WiiBase.CX * s + half,
                (DATE_BASE + 18f) * s, false);
        float u = HAND_UNIT * s;
        matrix.setRotate(-8f);
        matrix.postTranslate(handX, handY);
        tmp.set(-20f * u, -3f * u, 32f * u, 64f * u);
        matrix.mapRect(tmp);
        add(IT_HAND, 0, tmp.left - 2f, tmp.top - 2f, tmp.right + 2f, tmp.bottom + 2f, handMoving);
        if (menuAlpha > 0f) add(IT_MENU, 0, 0f, 0f, w, h, true);
        if (introAlpha > 0f) add(IT_INTRO, 0, 0f, 0f, w, h, true);
    }

    private void add(int kind, int arg, float l, float t, float r, float b, boolean moves) {
        int i = itemCount++;
        itemKind[i] = kind;
        itemArg[i] = arg;
        itemDirect[i] = moves;
        itemLive[i] = false;
        itemBox[4 * i] = l;
        itemBox[4 * i + 1] = t;
        itemBox[4 * i + 2] = r;
        itemBox[4 * i + 3] = b;
    }

    /** Adds the tile whose box slotRect just put in rect. */
    private void addTile(int sl, boolean scrolling) {
        Channel ch = sl < channels.size() ? channels.get(sl) : null;
        boolean live = ch != null && ch.banner instanceof LiveArt && ((LiveArt) ch.banner).animating();
        liveShown |= live;
        float lf = lift[sl];
        // How far the bulge, border and glow reach past the box; neighbouring tiles' reaches never meet.
        float out = (TILE_BULGE + 1.5f + (lf > 0.01f ? (TILE_BORDER + 15f) / 2f : (TILE_BORDER + 1.5f) / 2f)) * s;
        if (carrying && sl == focus) out += (TILE_W * (CARRY_SCALE - 1f) / 2f + CARRY_SHADOW + 2f) * s;
        add(IT_TILE, sl, rect.left - out, rect.top - out, rect.right + out, rect.bottom + out,
                scrolling || live || settling(lf));
        itemLive[itemCount - 1] = live;
    }

    private void addButton(int kind, float cxRef, int id) {
        float lf = lift[liftIndex(id)];
        float r = (WiiBase.BTN_RING + 12f) * s * (1f + 0.06f * lf);
        float cx = cxRef * s, cy = WiiBase.BTN_CY * s;
        add(kind, 0, cx - r, cy - r, cx + r, cy + r, settling(lf));
    }

    private static boolean settling(float lf) {
        return lf != 0f && lf != 1f;
    }

    /**
     * A still part goes into the layer only when no moving part drawn before it overlaps it. Every pixel then
     * still gets its parts in the original order, so the frame comes out as if all of it were drawn directly.
     */
    private void markDirect() {
        float[] b = itemBox;
        for (int i = 1; i < itemCount; i++) {
            if (itemDirect[i]) continue;
            int p = 4 * i;
            for (int j = 0; j < i; j++) {
                int q = 4 * j;
                if (itemDirect[j] && b[p] < b[q + 2] && b[q] < b[p + 2] && b[p + 1] < b[q + 3] && b[q + 1] < b[p + 3]) {
                    itemDirect[i] = true;
                    break;
                }
            }
        }
    }

    /** True when the still parts differ from the ones last recorded into the layer. */
    private boolean signatureChanged() {
        int need = 8 + itemCount * 3;
        if (sig.length < need) sig = new int[need * 2];
        int n = 0;
        sig[n++] = stillGen;
        sig[n++] = w;
        sig[n++] = h;
        sig[n++] = page;
        sig[n++] = clockHour * 100 + clockMinute + (clockPm ? 10_000 : 0) + (clock24 ? 20_000 : 0);
        sig[n++] = dateText.hashCode();
        for (int i = 0; i < itemCount; i++) {
            if (itemDirect[i]) continue;
            int kind = itemKind[i];
            sig[n++] = kind << 24 | itemArg[i];
            if (kind == IT_TILE) sig[n++] = Float.floatToIntBits(lift[itemArg[i]]);
            else if (kind == IT_BTN_L) sig[n++] = Float.floatToIntBits(lift[liftIndex(BTN_SETTINGS)]);
            else if (kind == IT_BTN_R) sig[n++] = Float.floatToIntBits(lift[liftIndex(BTN_INPUTS)]);
            else if (kind == IT_SD) sig[n++] = Float.floatToIntBits(lift[liftIndex(BTN_SD)]);
            else if (kind == IT_HAND) {
                sig[n++] = Float.floatToIntBits(handX);
                sig[n++] = Float.floatToIntBits(handY);
            }
        }
        boolean same = n == lastSigLen;
        for (int i = 0; same && i < n; i++) same = sig[i] == lastSig[i];
        if (same) return false;
        int[] t = lastSig;
        lastSig = sig;
        sig = t;
        lastSigLen = n;
        return true;
    }

    private void drawItem(Canvas c, int i) {
        switch (itemKind[i]) {
            case IT_BASE:
                if (base != null) c.drawBitmap(base, 0f, 0f, null);
                else c.drawColor(0xFFE9E9E9);
                break;
            case IT_TILE: {
                int sl = itemArg[i];
                slotRect(sl, pagePos, rect);
                drawTile(c, sl, rect, lift[sl], itemLive[i]);
                break;
            }
            case IT_ARROW_R:
                drawArrow(c, ARROW_TIP * s, ARROW_Y * s, false);
                break;
            case IT_ARROW_L:
                drawArrow(c, (2f * WiiBase.CX - ARROW_TIP) * s, ARROW_Y * s, true);
                break;
            case IT_BTN_L:
                drawButton(c, btnLeft, WiiBase.BTN_CX, BTN_SETTINGS);
                break;
            case IT_BTN_R:
                drawButton(c, btnRight, BTN_RIGHT_CX, BTN_INPUTS);
                break;
            case IT_SD:
                drawSd(c);
                break;
            case IT_DIGITS:
                drawDigits(c);
                break;
            case IT_COLON:
                drawColon(c);
                break;
            case IT_AMPM:
                drawAmPm(c);
                break;
            case IT_DATE:
                drawDate(c);
                break;
            case IT_HAND:
                drawHand(c);
                break;
            case IT_MENU:
                drawMenu(c);
                break;
            case IT_INTRO:
                drawIntro(c);
                break;
            default:
                break;
        }
    }

    private void drawTile(Canvas c, int slot, RectF r, float lf, boolean live) {
        int save = c.save();
        c.translate(r.left, r.top);
        boolean carried = carrying && slot == focus;
        if (carried) {
            c.scale(CARRY_SCALE, CARRY_SCALE, r.width() / 2f, r.height() / 2f);
            c.translate(CARRY_SHADOW * s, CARRY_SHADOW * s);
            fill.setColor(0x30000000);
            c.drawPath(tilePath, fill);
            c.translate(-CARRY_SHADOW * s, -CARRY_SHADOW * s);
        }
        Channel ch = slot < channels.size() ? channels.get(slot) : null;
        if (live && ch != null) {
            // Straight into the frame: a tile bitmap redrawn every frame flickers on the TV, as the render thread
            // can still be reading it while the next frame paints it over. Trimmed by painting the backdrop (all
            // that lies under a tile) back around the frame rather than by clipPath: under a clip this shape,
            // each of the banner's small draws that reaches the edge gets a mask of its own, drawn and uploaded
            // every frame. The two ways differ only on the frame's antialiased rim, under the opaque border.
            float pad = TILE_BULGE * s + 1f;
            int clip = c.save();
            c.clipRect(-pad, -pad, r.width() + pad, r.height() + pad);
            drawCropped(c, ch.banner, Math.round(r.width()), Math.round(r.height()));
            if (underShader != null) {
                matrix.setTranslate(-r.left, -r.top);
                // Undoes the carried tile's scale too, so the backdrop still lines up with the one around it.
                if (carried) matrix.postScale(1f / CARRY_SCALE, 1f / CARRY_SCALE, r.width() / 2f, r.height() / 2f);
                underShader.setLocalMatrix(matrix);
                under.setShader(underShader);
            } else {
                under.setShader(null);
                under.setColor(0xFFE9E9E9);
            }
            c.drawPath(tileOutside, under);
            under.setShader(null);
            c.restoreToCount(clip);
        } else if (ch != null) {
            ensureArt(ch, Math.round(r.width()), Math.round(r.height()));
            artPaint.setShader(ch.shader);
            c.drawPath(tilePath, artPaint);
            artPaint.setShader(null);
        } else if (emptyShader != null) {
            matrix.setTranslate(-4f, -4f);
            emptyShader.setLocalMatrix(matrix);
            artPaint.setShader(emptyShader);
            c.drawPath(tilePath, artPaint);
            artPaint.setShader(null);
        } else {
            fill.setColor(0xFFD6D6D6);
            c.drawPath(tilePath, fill);
        }
        if (lf > 0.01f) {
            for (int k = 3; k >= 1; k--) {
                stroke.setColor(withAlpha(BLUE, (int) (lf * 64 / k)));
                stroke.setStrokeWidth((TILE_BORDER + k * 5f) * s);
                c.drawPath(tilePath, stroke);
            }
        }
        stroke.setColor(blend(TILE_BORDER_COLOR, BLUE, lf));
        stroke.setStrokeWidth((TILE_BORDER + 1.5f * lf) * s);
        c.drawPath(tilePath, stroke);
        c.restoreToCount(save);
    }

    private void ensureArt(Channel ch, int tw, int th) {
        tw = Math.max(1, tw);
        th = Math.max(1, th);
        if (ch.art != null && ch.art.getWidth() == tw && ch.art.getHeight() == th) return;
        Bitmap bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        cv.drawColor(0xFFFFFFFF);
        if (ch.banner != null) {
            drawCropped(cv, ch.banner, tw, th);
        } else {
            Paint bg = new Paint();
            bg.setShader(new LinearGradient(0, 0, 0, th, 0xFFFFFFFF, 0xFFE9E9E9, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, tw, th, bg);
            if (ch.icon != null) {
                int size = Math.round(th * 0.46f);
                int left = (tw - size) / 2, top = Math.round(th * 0.12f);
                ch.icon.setBounds(left, top, left + size, top + size);
                ch.icon.draw(cv);
            }
            TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            tp.setTypeface(sansBold);
            tp.setTextSize(th * 0.15f);
            tp.setColor(0xFF7A7A7A);
            tp.setTextAlign(Paint.Align.CENTER);
            CharSequence label = TextUtils.ellipsize(ch.label, tp, tw * 0.86f, TextUtils.TruncateAt.END);
            cv.drawText(label, 0, label.length(), tw / 2f, th * 0.86f, tp);
        }
        ch.art = bmp;
        ch.shader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
    }

    private static void drawCropped(Canvas cv, Drawable d, int tw, int th) {
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) {
            d.setBounds(0, 0, tw, th);
        } else {
            float sc = Math.max(tw / (float) iw, th / (float) ih);
            int dw = Math.round(iw * sc), dh = Math.round(ih * sc);
            int dx = (tw - dw) / 2, dy = (th - dh) / 2;
            d.setBounds(dx, dy, dx + dw, dy + dh);
        }
        d.draw(cv);
    }

    private void drawArrow(Canvas c, float x, float y, boolean flip) {
        int save = c.save();
        c.translate(x, y);
        if (flip) c.scale(-1f, 1f);
        fill.setColor(0xFFFFFFFF);
        fill.setShader(arrowBody);
        c.drawPath(arrowRound, fill);
        fill.setShader(arrowShine);
        c.drawPath(arrowRound, fill);
        fill.setShader(null);
        stroke.setColor(0xFF2E66C2);
        stroke.setStrokeWidth(3.2f * s);
        c.drawPath(arrowRound, stroke);
        c.restoreToCount(save);
    }

    private void drawButton(Canvas c, Bitmap bmp, float cxRef, int id) {
        if (bmp == null) return;
        float lf = lift[liftIndex(id)];
        float cx = cxRef * s, cy = WiiBase.BTN_CY * s;
        int save = c.save();
        float sc = 1f + 0.06f * lf;
        c.scale(sc, sc, cx, cy);
        c.drawBitmap(bmp, cx - bmp.getWidth() / 2f, cy - bmp.getHeight() / 2f, bmpPaint);
        if (lf > 0.01f) {
            for (int k = 3; k >= 1; k--) {
                stroke.setColor(withAlpha(BLUE, (int) (lf * 70 / k)));
                stroke.setStrokeWidth((3f + k * 5f) * s);
                c.drawCircle(cx, cy, WiiBase.BTN_RING * s, stroke);
            }
        }
        c.restoreToCount(save);
    }

    private void drawSd(Canvas c) {
        float lf = lift[liftIndex(BTN_SD)];
        int save = c.save();
        float sc = 1f + 0.08f * lf;
        c.scale(sc, sc, (SD_L + SD_R) / 2f * s, (SD_T + SD_B) / 2f * s);
        c.translate(4f * s, 4f * s);
        fill.setColor(0x2E000000);
        c.drawPath(sdRound, fill);
        c.translate(-4f * s, -4f * s);
        fill.setColor(0xFF5CC8EE);
        c.drawPath(sdRound, fill);
        fill.setColor(0xFFA9DDF3);
        c.drawRect((SD_L + 4f) * s, (SD_T + 4f) * s, (SD_R - 10f) * s, 915f * s, fill);
        fill.setColor(0xFFFFFFFF);
        c.drawRect(366f * s, 915f * s, 422f * s, 975f * s, fill);
        fill.setColor(0xFF4FC1EA);
        c.drawRect(366f * s, 975f * s, 422f * s, 987f * s, fill);
        if (lf > 0.01f) {
            for (int k = 3; k >= 1; k--) {
                stroke.setColor(withAlpha(BLUE, (int) (lf * 80 / k)));
                stroke.setStrokeWidth((2f + k * 4f) * s);
                c.drawPath(sdRound, stroke);
            }
        }
        text.setTypeface(sdFace);
        text.setTextSize(24f * s);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(0xFF59BDE8);
        c.drawText("SD", 393f * s, 953f * s, text);
        c.restoreToCount(save);
    }

    /** Reads the time and, when the day or language changed, words the date again. */
    private void updateClock() {
        clockStale = false;
        clockAtMs = SystemClock.uptimeMillis();
        String date;
        if (refPose) {
            clockHour = 2;
            clockMinute = 7;
            clockPm = true;
            date = "Fri 3/22";
            dateKey = Integer.MIN_VALUE;
        } else {
            Calendar now = Calendar.getInstance();
            if (clock24) {
                clockHour = now.get(Calendar.HOUR_OF_DAY);
                clockPm = false;
            } else {
                int hour = now.get(Calendar.HOUR);
                clockHour = hour == 0 ? 12 : hour;
                clockPm = now.get(Calendar.AM_PM) == Calendar.PM;
            }
            clockMinute = now.get(Calendar.MINUTE);
            Locale locale = getResources().getConfiguration().getLocales().get(0);
            int key = now.get(Calendar.YEAR) * 400 + now.get(Calendar.DAY_OF_YEAR);
            if (key == dateKey && locale.equals(dateLocale)) {
                date = dateText;
            } else {
                dateKey = key;
                dateLocale = locale;
                java.util.Date at = now.getTime();
                String day = new SimpleDateFormat("EEE", locale).format(at).replace(".", "");
                if (!day.isEmpty()) day = day.substring(0, 1).toUpperCase(locale) + day.substring(1);
                boolean monthFirst = "US".equals(locale.getCountry()) || "en".equals(locale.getLanguage());
                date = day + " " + new SimpleDateFormat(monthFirst ? "M/d" : "d/M", locale).format(at);
            }
        }
        if (!date.equals(dateText) || dateScale != s) {
            dateText = date;
            dateScale = s;
            measureDate();
        }
    }

    private void dateFont() {
        text.setTypeface(sansBold);
        text.setTextSize(DATE_CAP / 0.711f * s);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(DATE_COLOR);
        // The original sets its date looser than Roboto: about 2 px more between letters, 3 more at the space.
        text.setLetterSpacing(DATE_TRACKING / (DATE_CAP / 0.711f));
    }

    private void measureDate() {
        int space = dateText.indexOf(' ');
        dateDay = space < 0 ? "" : dateText.substring(0, space);
        dateRest = space < 0 ? dateText : dateText.substring(space + 1);
        dateFont();
        float gap = text.measureText(" ") + DATE_EXTRA_SPACE * s;
        float wDay = dateDay.isEmpty() ? 0f : text.measureText(dateDay);
        dateRestW = text.measureText(dateRest);
        dateTotal = wDay + (dateDay.isEmpty() ? 0f : gap) + dateRestW;
        text.setLetterSpacing(0f);
    }

    private void drawDigits(Canvas c) {
        fill.setColor(CLOCK_COLOR);
        if (clockHour >= 10) drawDigit(c, 0, clockHour / 10);
        drawDigit(c, 1, clockHour % 10);
        drawDigit(c, 2, clockMinute / 10);
        drawDigit(c, 3, clockMinute % 10);
    }

    private void drawDigit(Canvas c, int pos, int digit) {
        int bits = SEGMENTS[digit];
        Path[] segs = digitSegs[pos];
        for (int k = 0; k < SEGMENT_ORDER.length; k++) {
            if ((bits & SEGMENT_ORDER[k]) != 0) c.drawPath(segs[k], fill);
        }
    }

    private void drawColon(Canvas c) {
        float d = COLON_DOT / 2f;
        fill.setColor(CLOCK_COLOR);
        c.drawRoundRect((COLON_X - d) * s, (COLON_Y1 - d) * s, (COLON_X + d) * s, (COLON_Y1 + d) * s, 2f * s, 2f * s, fill);
        c.drawRoundRect((COLON_X - d) * s, (COLON_Y2 - d) * s, (COLON_X + d) * s, (COLON_Y2 + d) * s, 2f * s, 2f * s, fill);
    }

    private void drawAmPm(Canvas c) {
        text.setTypeface(sansBold);
        text.setTextSize(AMPM_CAP / 0.711f * s);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(CLOCK_COLOR);
        // Letter by letter at the original's ink positions and widths; Roboto's M is wider than the original's.
        drawInkLetter(c, clockPm ? "P" : "A", AMPM_X, AMPM_P_W);
        drawInkLetter(c, "M", AMPM_M_X, AMPM_M_W);
    }

    /** Draws one letter so its ink starts at inkLeft and spans inkWidth ref px, on the AM/PM baseline. */
    private void drawInkLetter(Canvas c, String letter, float inkLeft, float inkWidth) {
        android.graphics.Rect b = inkBounds;
        text.getTextBounds(letter, 0, 1, b);
        float sx = b.width() > 0 ? inkWidth * s / b.width() : 1f;
        int save = c.save();
        c.translate(inkLeft * s - b.left * sx, AMPM_BASE * s);
        c.scale(sx, 1f);
        c.drawText(letter, 0f, 0f, text);
        c.restoreToCount(save);
    }

    private void drawDate(Canvas c) {
        dateFont();
        float x = WiiBase.CX * s - dateTotal / 2f;
        if (!dateDay.isEmpty()) c.drawText(dateDay, x, DATE_BASE * s, text);
        c.drawText(dateRest, x + dateTotal - dateRestW, DATE_BASE * s, text);
        text.setLetterSpacing(0f);
    }

    private void drawHand(Canvas c) {
        float u = HAND_UNIT * s;
        int save = c.save();
        c.translate(handX, handY);
        c.rotate(-8f);
        c.translate(2.5f * u, 3.5f * u);
        fill.setColor(0x38000000);
        c.drawPath(handPath, fill);
        c.translate(-2.5f * u, -3.5f * u);
        fill.setColor(0xFFFFFFFF);
        c.drawPath(handPath, fill);
        stroke.setColor(0xFF3A3A3A);
        stroke.setStrokeWidth(2.2f * u);
        c.drawPath(handPath, stroke);
        stroke.setStrokeWidth(1.5f * u);
        c.drawPath(handLines, stroke);
        tmp.set(-4.5f * u, 47f * u, 23.5f * u, 60f * u);
        fill.setColor(0xFF47B6EA);
        c.drawRoundRect(tmp, 4f * u, 4f * u, fill);
        stroke.setColor(0xFF2C86BA);
        stroke.setStrokeWidth(1.6f * u);
        c.drawRoundRect(tmp, 4f * u, 4f * u, stroke);
        text.setTypeface(sansBold);
        text.setTextSize(10.5f * u);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(0xFFFFFFFF);
        c.drawText("1", tmp.centerX(), tmp.centerY() + 3.8f * u, text);
        c.restoreToCount(save);
    }

    private void drawMenu(Canvas c) {
        int a = Math.round(menuAlpha * 255);
        int save = a < 255 ? c.saveLayerAlpha(0, 0, w, h, a) : c.save();
        fill.setColor(0x78000000);
        c.drawRect(0, 0, w, h, fill);
        int n = menuItems.size();
        float pw = 1000f * s, itemH = 92f * s, gap = 22f * s, head = 150f * s, pad = 56f * s;
        float ph = head + n * itemH + (n - 1) * gap + pad;
        float px = (w - pw) / 2f, py = (h - ph) / 2f;
        c.translate(0f, (1f - menuAlpha) * 36f * s);
        float rad = 40f * s;
        rect.set(px, py, px + pw, py + ph);
        if (base != null) {
            fill.setColor(0xFFFFFFFF);
            fill.setShader(baseShader);
            c.drawRoundRect(rect, rad, rad, fill);
            fill.setShader(null);
        } else {
            fill.setColor(0xFFEDEDED);
            c.drawRoundRect(rect, rad, rad, fill);
        }
        stroke.setColor(TILE_BORDER_COLOR);
        stroke.setStrokeWidth(TILE_BORDER * s);
        c.drawRoundRect(rect, rad, rad, stroke);

        text.setTypeface(sansBold);
        text.setTextSize(48f * s);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(DATE_COLOR);
        c.drawText(menuTitle, w / 2f, py + 92f * s, text);

        text.setTextSize(40f * s);
        if (menuShaders == null || menuShaders.length != n) menuShaders = new LinearGradient[n];
        for (int i = 0; i < n; i++) {
            float iy = py + head + i * (itemH + gap);
            tmp.set(px + 90f * s, iy, px + pw - 90f * s, iy + itemH);
            float lf = i < menuLift.length ? menuLift[i] : 0f;
            float ir = itemH / 2f;
            fill.setColor(0xFFFFFFFF);
            if (menuShaders[i] == null) {
                menuShaders[i] = new LinearGradient(0, tmp.top, 0, tmp.bottom, 0xFFFFFFFF, 0xFFE2E2E2,
                        Shader.TileMode.CLAMP);
            }
            fill.setShader(menuShaders[i]);
            c.drawRoundRect(tmp, ir, ir, fill);
            fill.setShader(null);
            if (lf > 0.01f) {
                for (int k = 3; k >= 1; k--) {
                    stroke.setColor(withAlpha(BLUE, (int) (lf * 70 / k)));
                    stroke.setStrokeWidth((4f + k * 6f) * s);
                    c.drawRoundRect(tmp, ir, ir, stroke);
                }
            }
            stroke.setColor(blend(TILE_BORDER_COLOR, BLUE, lf));
            stroke.setStrokeWidth((TILE_BORDER + 2f * lf) * s);
            c.drawRoundRect(tmp, ir, ir, stroke);
            text.setColor(0xFF666666);
            CharSequence label = TextUtils.ellipsize(menuItems.get(i).label, text, tmp.width() - 70f * s,
                    TextUtils.TruncateAt.END);
            c.drawText(label, 0, label.length(), tmp.centerX(), tmp.centerY() + 14f * s, text);
        }
        c.restoreToCount(save);
    }

    private void drawIntro(Canvas c) {
        int a = Math.round(introAlpha * 255);
        int save = a < 255 ? c.saveLayerAlpha(0, 0, w, h, a) : c.save();
        c.drawColor(0xFFFFFFFF);
        float left = 240f * s;
        float headY = 340f * s;
        drawWarningSign(c, left + 34f * s, headY - 22f * s, 38f * s);
        text.setTypeface(sansBold);
        text.setTextSize(64f * s);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(0xFF1E1E1E);
        c.drawText(introTitle, left + 98f * s, headY, text);
        stroke.setColor(0xFFDADADA);
        stroke.setStrokeWidth(3f * s);
        c.drawLine(left, headY + 40f * s, w - left, headY + 40f * s, stroke);

        if (introBody == null) {
            TextPaint body = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            body.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            body.setTextSize(50f * s);
            body.setColor(0xFF3A3A3A);
            int width = Math.max(1, Math.round(w - 2 * left));
            introBody = StaticLayout.Builder.obtain(introBodyText, 0, introBodyText.length(), body, width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.2f)
                    .build();
        }
        int bs = c.save();
        c.translate(left, headY + 100f * s);
        introBody.draw(c);
        c.restoreToCount(bs);

        float t = (SystemClock.uptimeMillis() - introStartMs) / 1000f;
        float pulse = 0.58f + 0.42f * (float) Math.cos(t * Math.PI * 2 / 1.6);
        int ca = Math.round(255 * Math.max(0.16f, pulse));
        text.setTypeface(sansBold);
        text.setTextSize(52f * s);
        text.setTextAlign(Paint.Align.LEFT);
        float tw = text.measureText(introContinue);
        float iconR = 32f * s, gapIcon = 22f * s;
        float total = 2 * iconR + gapIcon + tw;
        float sx = (w - total) / 2f, baseY = 880f * s;
        float icx = sx + iconR, icy = baseY - 18f * s;
        fill.setColor(withAlpha(0xFFFFFFFF, ca));
        c.drawCircle(icx, icy, iconR, fill);
        stroke.setColor(withAlpha(0xFF9A9A9A, ca));
        stroke.setStrokeWidth(4.5f * s);
        c.drawCircle(icx, icy, iconR, stroke);
        text.setColor(withAlpha(0xFF4A4A4A, ca));
        c.drawText(introContinue, sx + 2 * iconR + gapIcon, baseY, text);
        text.setTextSize(22f * s);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(withAlpha(0xFF777777, ca));
        c.drawText("OK", icx, icy + 8f * s, text);
        c.restoreToCount(save);
    }

    /** The sign is warnRound, built for this spot; cx, cy and r place its mark. */
    private void drawWarningSign(Canvas c, float cx, float cy, float r) {
        fill.setColor(0xFFFFC61E);
        c.drawPath(warnRound, fill);
        stroke.setColor(0xFF1E1E1E);
        stroke.setStrokeWidth(4.5f * s);
        c.drawPath(warnRound, stroke);
        text.setTypeface(sansBold);
        text.setTextSize(r * 1.25f);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(0xFF1E1E1E);
        c.drawText("!", cx, cy + r * 0.65f, text);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }

    private static int blend(int from, int to, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a = Math.round(((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * t);
        int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** A display list kept in its own GPU layer, which the render thread re-renders only after a new recording. */
    @TargetApi(Build.VERSION_CODES.Q)
    private static final class Still {
        private final RenderNode node = new RenderNode("wii-still");

        Still() {
            node.setUseCompositingLayer(true, null);
        }

        Canvas begin(int width, int height) {
            node.setPosition(0, 0, width, height);
            return node.beginRecording(width, height);
        }

        void end() {
            node.endRecording();
        }

        boolean valid() {
            return node.hasDisplayList();
        }

        void draw(Canvas c) {
            c.drawRenderNode(node);
        }

        void discard() {
            node.discardDisplayList();
        }
    }
}
