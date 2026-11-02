package com.gathin.tvhome;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.os.Environment;
import android.os.StatFs;
import java.io.File;
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
import android.graphics.drawable.Animatable;
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
        default void onThemeButton() { }
        default void onMusicButton() { }
        default void onPowerButton() { }
        /** The welcome screen started to leave: the menu is coming up, as after power-on. */
        void onIntroDismissed();
        /** The hand a phone steers came onto a channel, a button or a page arrow. */
        default void onPointedAt() { }
    }

    public interface FrameCallback {
        void onFrame(float scale, float pagePos, boolean introShowing, boolean menuOpen);
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
    static final int BTN_THEME = -4;
    static final int BTN_POWER = -5;
    static final int BTN_MUSIC = -6;
    private static final int EXTRA_BUTTONS = 6;
    private static final long INTRO_AUTO_MS = 12_000L;
    /** How often the clock reads the time; it shows minutes, so this only bounds how late a new minute appears. */
    private static final long CLOCK_CHECK_MS = 250L;

    // What a frame draws, in drawing order.
    private static final int IT_BASE = 0, IT_TILE = 1, IT_ARROW_R = 2, IT_ARROW_L = 3, IT_BTN_L = 4, IT_BTN_R = 5,
            IT_SD = 6, IT_DIGITS = 7, IT_COLON = 8, IT_AMPM = 9, IT_DATE = 10, IT_HAND = 11, IT_MENU = 12,
            IT_INTRO = 13, IT_THEME = 14, IT_POWER = 15, IT_MUSIC = 16;

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
    private static final float THEME_L = 446f;
    private static final float THEME_T = 897f;
    private static final float THEME_R = 514f;
    private static final float THEME_B = 994f;
    private static final float MUSIC_L = 1406f;
    private static final float MUSIC_T = 897f;
    private static final float MUSIC_R = 1474f;
    private static final float MUSIC_B = 994f;
    private static final float POWER_L = 1496f;
    private static final float POWER_T = 897f;
    private static final float POWER_R = 1564f;
    private static final float POWER_B = 994f;
    /** Reference px around the small SD button and the arrows that still count as on them for the free hand. */
    private static final float SD_PAD = 16f, ARROW_PAD = 24f;
    /** Reference px past its edges that the free hand stays on what it is on. */
    private static final float STAY_MARGIN = 12f;

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
    private static final float DATE_BASE = 966f;
    private static final float STATS_BASE = 1008f;
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

    private final Listener listener;
    private FrameCallback frameCallback;
    private final List<Channel> channels = new ArrayList<>();
    private int pages = MIN_PAGES;
    /** A slot or a button; Integer.MIN_VALUE in the reference pose, or while the free hand points at nothing. */
    private int focus;
    private int page;
    private float pagePos;
    private float[] lift = new float[MIN_PAGES * SLOTS_PER_PAGE + EXTRA_BUTTONS];
    private float handX, handY;
    /** Set each frame when a live tile is on screen. */
    private boolean liveShown;
    private boolean handPlaced;
    private boolean handSprites;
    private boolean handMoving;
    private long lastFrameNanos;
    private boolean started;
    private boolean colonOn = true;
    private boolean refPose;
    private boolean darkMode;
    private String settingsLabel = "wiiOS";
    private WiiSounds sounds;

    // HDMI Channel Appearance and Disappearance Animation
    private static final float HDMI_APPEAR_DURATION = 0.55f;
    private static final float HDMI_DISAPPEAR_DURATION = 0.40f;
    private boolean hdmiAppearing = false;
    private boolean hdmiDisappearing = false;
    private float hdmiAppearTime = 0f;
    private float hdmiDisappearTime = 0f;
    private float hdmiAppearScale = 1.0f;
    private float hdmiAppearAlpha = 1.0f;
    private float hdmiAppearGlow = 0f;
    private Runnable onHdmiDisappearComplete;

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
    /** A phone steers the hand (Wiimote): it goes where it is pointed, and what is under it is the focus. */
    private boolean freeHand;
    private float freeX, freeY;
    /** The page arrow under the free hand: 1 the right one, -1 the left one, 0 none. */
    private int arrowUnder;

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
    private final WiiHand hand = new WiiHand(sansBold);

    private volatile int buildGen;
    private Bitmap base, btnLeft, btnRight, emptyArt;
    private BitmapShader emptyShader, baseShader, underShader;
    private Path tilePath;
    /** Everything outside a channel frame, for trimming a live tile to the frame. */
    private Path tileOutside;
    /** Outlines with their corners already rounded: the shapes a CornerPathEffect gives, worked out once. */
    private Path arrowRound, warnRound;
    /** Per clock position, its seven segments in SEGMENT_ORDER. */
    private Path[][] digitSegs;
    private LinearGradient arrowBody, arrowShine;
    private LinearGradient[] menuShaders;

    private int clockHour = 12, clockMinute;
    private boolean musicMuted;
    private String statsText = "";
    private float statsTotal;
    private long lastStatsUpdateMs;

    public void setMusicMuted(boolean muted) {
        if (this.musicMuted != muted) {
            this.musicMuted = muted;
            invalidate();
        }
    }

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
            updateStats();
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

    // Animated thumbnail preview
    private static final long THUMBNAIL_HOVER_DELAY_MS = 300L;
    private int thumbActiveSlot = -1;
    private Drawable thumbActiveDrawable = null;
    private boolean thumbIsPlaying = false;
    private final RectF thumbIconDst = new RectF();
    private final Path thumbClipPath = new Path();
    private final Paint thumbBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Runnable thumbStartRunnable = new Runnable() {
        @Override
        public void run() {
            if (!started || refPose || isIntroShowing() || menuOpen || carrying) {
                return;
            }
            if (focus < 0 || focus >= channels.size()) return;
            startThumbnailPlayback(focus);
        }
    };

    private void startThumbnailPlayback(int slot) {
        if (slot < 0 || slot >= channels.size()) return;
        Channel ch = channels.get(slot);
        if (ch == null) return;
        if (!WiiThumbnailManager.hasThumbnail(getContext(), ch.key)) return;

        stopThumbnailPlayback(false);

        Drawable d = WiiThumbnailManager.loadAnimatedDrawable(getContext(), ch.key);
        if (d != null) {
            thumbActiveDrawable = d;
            thumbActiveSlot = slot;
            thumbIsPlaying = true;
            d.setCallback(this);
            if (d instanceof Animatable) {
                ((Animatable) d).start();
            }
            stillGen++;
            invalidate();
        }
    }

    private void stopThumbnailPlayback(boolean invalidate) {
        removeCallbacks(thumbStartRunnable);
        if (thumbActiveDrawable != null) {
            if (thumbActiveDrawable instanceof Animatable) {
                ((Animatable) thumbActiveDrawable).stop();
            }
            thumbActiveDrawable.setCallback(null);
            thumbActiveDrawable = null;
        }
        int oldSlot = thumbActiveSlot;
        thumbActiveSlot = -1;
        thumbIsPlaying = false;
        if (invalidate && oldSlot >= 0) {
            stillGen++;
            invalidate();
        }
    }

    private void onFocusChangedForThumbnail(int newFocus) {
        removeCallbacks(thumbStartRunnable);
        if (thumbIsPlaying && thumbActiveSlot != newFocus) {
            stopThumbnailPlayback(true);
        }
        if (newFocus >= 0 && newFocus < channels.size()) {
            Channel ch = channels.get(newFocus);
            if (ch != null && WiiThumbnailManager.hasThumbnail(getContext(), ch.key)) {
                postDelayed(thumbStartRunnable, THUMBNAIL_HOVER_DELAY_MS);
            }
        }
    }

    public void refreshChannelArt() {
        stopThumbnailPlayback(false);
        WiiThumbnailManager.clearCache();
        for (Channel ch : channels) {
            ch.art = null;
            ch.shader = null;
        }
        stillGen++;
        invalidate();
    }

    @Override
    public void invalidateDrawable(Drawable who) {
        if (who == thumbActiveDrawable) {
            invalidate();
            return;
        }
        super.invalidateDrawable(who);
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return who == thumbActiveDrawable || super.verifyDrawable(who);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopThumbnailPlayback(false);
    }

    private void scheduleHoverSound() {
        removeCallbacks(hoverSound);
        int delay = getResources().getInteger(R.integer.wii_hover_delay_ms);
        if (delay > 0 && started && !refPose && !isIntroShowing() && !menuOpen
                && !carrying && !okHeld
                && focus >= 0 && focus < channels.size()) postDelayed(hoverSound, delay);
        onFocusChangedForThumbnail(focus);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) {
            scheduleHoverSound();
        } else {
            removeCallbacks(hoverSound);
            stopThumbnailPlayback(true);
        }
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

    public void setFrameCallback(FrameCallback callback) {
        this.frameCallback = callback;
    }

    private boolean externalHand;
    private Runnable onHandInvalidate;

    public void setExternalHand(boolean external) {
        this.externalHand = external;
        invalidate();
    }

    public void setOnHandInvalidate(Runnable r) {
        this.onHandInvalidate = r;
    }

    public void drawHand(Canvas c) {
        if (introAlpha > 0f) return;
        hand.draw(c, handX, handY);
    }

    public void getSlotRect(int slot, RectF out) {
        slotRect(slot, pagePos, out);
    }

    public float getScale() {
        return s;
    }

    public boolean isMenuOpen() {
        return menuOpen;
    }

    public int getFocus() {
        return focus;
    }

    public void startHdmiAppearAnimation() {
        page = 0;
        hdmiDisappearing = false;
        hdmiAppearing = true;
        hdmiAppearTime = 0f;
        hdmiAppearScale = 0.01f;
        hdmiAppearAlpha = 0f;
        hdmiAppearGlow = 0f;
        stillGen++;
        invalidate();
    }

    public void startHdmiDisappearAnimation(Runnable onComplete) {
        if (hdmiDisappearing) return;
        hdmiAppearing = false;
        hdmiDisappearing = true;
        hdmiDisappearTime = 0f;
        this.onHdmiDisappearComplete = onComplete;
        stillGen++;
        invalidate();
    }

    public boolean isHdmiAppearing() {
        return hdmiAppearing;
    }

    public boolean isHdmiDisappearing() {
        return hdmiDisappearing;
    }

    public float getHdmiAppearScale() {
        return hdmiAppearScale;
    }

    public float getHdmiAppearAlpha() {
        return hdmiAppearAlpha;
    }

    public float getHdmiAppearGlow() {
        return hdmiAppearGlow;
    }

    public float getPagePos() {
        return pagePos;
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

    public void setHdmiChannel(Channel hdmiCh) {
        if (hdmiCh == null) return;
        carrying = false;
        okHeld = false;
        removeCallbacks(okHeldLong);
        orderBefore = null;

        if (!channels.isEmpty() && channels.get(0).key.startsWith("hdmi.")) {
            channels.set(0, hdmiCh);
            stillGen++;
            invalidate();
            return;
        }

        channels.add(0, hdmiCh);
        pages = Math.max(MIN_PAGES, (channels.size() + SLOTS_PER_PAGE - 1) / SLOTS_PER_PAGE);
        int n = pages * SLOTS_PER_PAGE + EXTRA_BUTTONS;
        if (lift.length != n) lift = new float[n];
        if (focus >= 0) focus++;
        if (focus >= pages * SLOTS_PER_PAGE) focus = 0;

        sound(WiiSounds.SELECT);
        startHdmiAppearAnimation();
    }

    public void removeHdmiChannel(Runnable onComplete) {
        if (channels.isEmpty() || !channels.get(0).key.startsWith("hdmi.")) {
            if (onComplete != null) onComplete.run();
            return;
        }
        if (pagePos > 0.05f || !started) {
            channels.remove(0);
            pages = Math.max(MIN_PAGES, (channels.size() + SLOTS_PER_PAGE - 1) / SLOTS_PER_PAGE);
            int n = pages * SLOTS_PER_PAGE + EXTRA_BUTTONS;
            if (lift.length != n) lift = new float[n];
            if (focus > 0) focus--;
            stillGen++;
            invalidate();
            if (onComplete != null) onComplete.run();
            return;
        }
        sound(WiiSounds.BACK);
        startHdmiDisappearAnimation(() -> {
            if (!channels.isEmpty() && channels.get(0).key.startsWith("hdmi.")) {
                channels.remove(0);
                pages = Math.max(MIN_PAGES, (channels.size() + SLOTS_PER_PAGE - 1) / SLOTS_PER_PAGE);
                int n = pages * SLOTS_PER_PAGE + EXTRA_BUTTONS;
                if (lift.length != n) lift = new float[n];
                if (focus > 0) focus--;
                stillGen++;
                invalidate();
            }
            if (onComplete != null) onComplete.run();
        });
    }

    void setChannels(List<Channel> list) {
        stopThumbnailPlayback(false);
        // An app installed or removed mid-carry: the new list wins, the carry so far is dropped unsaved.
        carrying = false;
        okHeld = false;
        removeCallbacks(okHeldLong);
        orderBefore = null;
        channels.clear();
        channels.addAll(list);
        pages = Math.max(MIN_PAGES, (channels.size() + SLOTS_PER_PAGE - 1) / SLOTS_PER_PAGE);
        int n = pages * SLOTS_PER_PAGE + EXTRA_BUTTONS;
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

    boolean isDarkMode() {
        return darkMode;
    }

    void setDarkMode(boolean dark) {
        if (darkMode == dark) return;
        darkMode = dark;
        menuShaders = null;
        for (Channel c : channels) {
            if (c.banner == null) {
                c.art = null;
                c.shader = null;
            }
        }
        rebuild();
        stillGen++;
        invalidate();
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
        clock24 = false;
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
        freeHand = false;
        arrowUnder = 0;
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

    /** The original's pointing and grabbing hands, from a Mii pack, in place of the hand drawn in code. */
    void useHandSprites(MiiUiSprites u) {
        if (u == null || handSprites) return;
        handSprites = true;
        hand.useSprites(u.bitmap("hand"), u.bitmap("hand_blur"), u.bitmap("grab"), u.bitmap("grab_blur"));
        stillGen++;
        invalidate();
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
        if (freeHand && isDpad(keyCode) && leaveFreeHand()) return true;
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
                focus = col == 0 ? BTN_SETTINGS : col == 1 ? BTN_SD : col == 2 ? BTN_THEME : BTN_MUSIC;
            }
        } else if (dy < 0) {
            int col = focus == BTN_SETTINGS ? 0 : focus == BTN_SD ? 1 : focus == BTN_THEME ? 2 : 3;
            focus = page * SLOTS_PER_PAGE + 8 + col;
        } else if (dx < 0) {
            if (focus == BTN_SD) focus = BTN_SETTINGS;
            else if (focus == BTN_THEME) focus = BTN_SD;
            else if (focus == BTN_MUSIC) focus = BTN_THEME;
            else if (focus == BTN_POWER) focus = BTN_MUSIC;
            else if (focus == BTN_INPUTS) focus = BTN_POWER;
        } else if (dx > 0) {
            if (focus == BTN_SETTINGS) focus = BTN_SD;
            else if (focus == BTN_SD) focus = BTN_THEME;
            else if (focus == BTN_THEME) focus = BTN_MUSIC;
            else if (focus == BTN_MUSIC) focus = BTN_POWER;
            else if (focus == BTN_POWER) focus = BTN_INPUTS;
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
        if (freeHand) pointAt(false);
        scheduleHoverSound();
        invalidate();
    }

    private void activate() {
        if (focus == Integer.MIN_VALUE) {
            // The free hand on a page arrow turns the page; on nothing, OK does nothing.
            if (arrowUnder != 0) turnPage(arrowUnder);
            return;
        }
        removeCallbacks(hoverSound);
        boolean bigButton = focus < 0;
        sound(bigButton ? WiiSounds.BIG_BUTTON : WiiSounds.SELECT);
        if (focus == BTN_SETTINGS) listener.onSettingsButton();
        else if (focus == BTN_SD) listener.onSdButton();
        else if (focus == BTN_THEME) listener.onThemeButton();
        else if (focus == BTN_MUSIC) listener.onMusicButton();
        else if (focus == BTN_POWER) listener.onPowerButton();
        else if (focus == BTN_INPUTS) listener.onInputsButton();
        else if (focus >= 0 && focus < channels.size()) {
            if (focus == 0 && hdmiDisappearing) return;
            listener.onChannel(channels.get(focus));
        }
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
        if (channels.get(focus).key.startsWith("hdmi.")) return;
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
        if (to == 0 && !channels.isEmpty() && channels.get(0).key.startsWith("hdmi.")) return;
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

    // ---- the free hand: a phone points it (Wiimote), and what is under it is the focus -----------------

    /** A phone moved the hand dx, dy px of a 1920-wide screen. */
    void pointerMove(float dx, float dy) {
        if (refPose || introAlpha > 0f || menuOpen || !started) return;
        if (!freeHand) {
            freeHand = true;
            freeX = handX;
            freeY = handY;
        }
        freeX = Math.max(0f, Math.min(w - 1f, freeX + dx * s));
        freeY = Math.max(0f, Math.min(h - 1f, freeY + dy * s));
        pointAt(true);
        invalidate();
    }

    /** The arrows take the hand back. True when this press only gave it a place, having been on nothing. */
    private boolean leaveFreeHand() {
        freeHand = false;
        arrowUnder = 0;
        if (focus != Integer.MIN_VALUE) return false;
        focus = nearestSlot(handX / s, handY / s);
        sound(WiiSounds.MOVE);
        scheduleHoverSound();
        invalidate();
        return true;
    }

    /** What is under the free hand becomes the focus; cue: a tick, and a buzz on the phone, when it is something. */
    private void pointAt(boolean cue) {
        float x = freeX / s, y = freeY / s;
        if (carrying) {
            // The carried channel follows the hand from slot to slot.
            int to = slotAt(x, y);
            if (to >= 0) carryTo(to);
            return;
        }
        int on = Integer.MIN_VALUE, arrow = 0;
        // Reaching a little past what it is on, so a hand trembling at a border does not flicker between two.
        if (focus != Integer.MIN_VALUE && under(focus, x, y, STAY_MARGIN)) {
            on = focus;
        } else {
            on = slotAt(x, y);
            if (on < 0) on = Integer.MIN_VALUE;
            for (int b = BTN_SETTINGS; on == Integer.MIN_VALUE && b >= BTN_MUSIC; b--) {
                if (under(b, x, y, 0f)) on = b;
            }
        }
        if (on == Integer.MIN_VALUE) arrow = arrowAt(x, y);
        if (on == focus && arrow == arrowUnder) return;
        focus = on;
        arrowUnder = arrow;
        boolean something = arrow != 0 || on != Integer.MIN_VALUE && (on < 0 || on < channels.size());
        if (cue && something) {
            sound(WiiSounds.MOVE);
            listener.onPointedAt();
        }
        scheduleHoverSound();
        invalidate();
    }

    /** Whether (x, y), in reference px, is on this slot of the page shown or on this button, margin px around it. */
    private boolean under(int f, float x, float y, float margin) {
        if (f >= 0) {
            if (f / SLOTS_PER_PAGE != page) return false;
            int i = f % SLOTS_PER_PAGE;
            float left = TILE_X0 + i % WiiGrid.COLS * COL_PITCH, top = TILE_Y0 + i / WiiGrid.COLS * ROW_PITCH;
            return x >= left - margin && x <= left + TILE_W + margin && y >= top - margin && y <= top + TILE_H + margin;
        }
        if (f == BTN_SD) {
            float m = margin + SD_PAD;
            return x >= SD_L - m && x <= SD_R + m && y >= SD_T - m && y <= SD_B + m;
        }
        if (f == BTN_THEME) {
            float m = margin + SD_PAD;
            return x >= THEME_L - m && x <= THEME_R + m && y >= THEME_T - m && y <= THEME_B + m;
        }
        if (f == BTN_MUSIC) {
            float m = margin + SD_PAD;
            return x >= MUSIC_L - m && x <= MUSIC_R + m && y >= MUSIC_T - m && y <= MUSIC_B + m;
        }
        if (f == BTN_POWER) {
            float m = margin + SD_PAD;
            return x >= POWER_L - m && x <= POWER_R + m && y >= POWER_T - m && y <= POWER_B + m;
        }
        float cx = f == BTN_SETTINGS ? WiiBase.BTN_CX : BTN_RIGHT_CX, r = WiiBase.BTN_RING + margin;
        float ddx = x - cx, ddy = y - WiiBase.BTN_CY;
        return (f == BTN_SETTINGS || f == BTN_INPUTS) && ddx * ddx + ddy * ddy <= r * r;
    }

    /** The slot of the page shown under (x, y), in reference px; -1 between and around the tiles. */
    private int slotAt(float x, float y) {
        for (int i = 0; i < SLOTS_PER_PAGE; i++) {
            if (under(page * SLOTS_PER_PAGE + i, x, y, 0f)) return page * SLOTS_PER_PAGE + i;
        }
        return -1;
    }

    private int nearestSlot(float x, float y) {
        int col = Math.round((x - TILE_X0 - TILE_W / 2f) / COL_PITCH);
        int row = Math.round((y - TILE_Y0 - TILE_H / 2f) / ROW_PITCH);
        col = Math.max(0, Math.min(WiiGrid.COLS - 1, col));
        row = Math.max(0, Math.min(WiiGrid.ROWS - 1, row));
        return page * SLOTS_PER_PAGE + row * WiiGrid.COLS + col;
    }

    /** The page arrow shown under (x, y), in reference px: 1 the right one, -1 the left one, 0 none. */
    private int arrowAt(float x, float y) {
        if (y < ARROW_T - ARROW_PAD || y > ARROW_B + ARROW_PAD) return 0;
        float cx2 = 2f * WiiBase.CX;
        if (page < pages - 1 && x >= ARROW_L - ARROW_PAD && x <= ARROW_TIP + ARROW_PAD) return 1;
        if (page > 0 && x >= cx2 - ARROW_TIP - ARROW_PAD && x <= cx2 - ARROW_L + ARROW_PAD) return -1;
        return 0;
    }

    // ---- where the hand is, kept for when Android closes the menu while an app runs ------------------

    /** The channel under the hand, or null on an empty slot or a button. */
    String handChannel() {
        return focus >= 0 && focus < channels.size() ? channels.get(focus).key : null;
    }

    /** The slot or button under the hand; Integer.MIN_VALUE in the reference pose or with the free hand on nothing. */
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
        boolean button = slot == BTN_SETTINGS || slot == BTN_SD || slot == BTN_INPUTS
                || slot == BTN_THEME || slot == BTN_POWER || slot == BTN_MUSIC;
        if (!button && (slot < 0 || slot >= pages * SLOTS_PER_PAGE)) return;
        focus = slot;
        page = button ? Math.max(0, Math.min(pg, pages - 1)) : slot / SLOTS_PER_PAGE;
        pagePos = page;
        freeHand = false;
        arrowUnder = 0;
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
        } else if (f == BTN_THEME) {
            out[0] = ((THEME_L + THEME_R) / 2f + 10f) * s;
            out[1] = ((THEME_T + THEME_B) / 2f + 14f) * s;
        } else if (f == BTN_MUSIC) {
            out[0] = ((MUSIC_L + MUSIC_R) / 2f + 10f) * s;
            out[1] = ((MUSIC_T + MUSIC_B) / 2f + 14f) * s;
        } else if (f == BTN_POWER) {
            out[0] = ((POWER_L + POWER_R) / 2f + 10f) * s;
            out[1] = ((POWER_T + POWER_B) / 2f + 14f) * s;
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
        } else if (focus == BTN_THEME) {
            tmp.set(THEME_L * s, THEME_T * s, THEME_R * s, THEME_B * s);
        } else if (focus == BTN_MUSIC) {
            tmp.set(MUSIC_L * s, MUSIC_T * s, MUSIC_R * s, MUSIC_B * s);
        } else if (focus == BTN_POWER) {
            tmp.set(POWER_L * s, POWER_T * s, POWER_R * s, POWER_B * s);
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
        hand.setScale(s);

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
        final boolean dark = darkMode;
        new Thread(() -> {
            int[] px = WiiBase.backdrop(bw, bh, dark);
            Bitmap bg = Bitmap.createBitmap(px, bw, bh, Bitmap.Config.ARGB_8888);
            int size = (int) Math.ceil(2f * (WiiBase.BTN_RING + WiiBase.BTN_RING_HALF + 2f) * bs);
            int[] bp = WiiBase.button(size, bs, dark);
            Bitmap left = Bitmap.createBitmap(bp, size, size, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true);
            Bitmap right = left.copy(Bitmap.Config.ARGB_8888, true);
            drawWordmark(new Canvas(left), size, bs, label, dark);
            drawEnvelope(new Canvas(right), size, bs, dark);
            int tw = Math.round(TILE_W * bs) + 8, th = Math.round(TILE_H * bs) + 8;
            Bitmap empty = Bitmap.createBitmap(WiiBase.emptySlot(tw, th, bs, dark), tw, th, Bitmap.Config.ARGB_8888);
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

    private void drawWordmark(Canvas c, int size, float bs, String label, boolean dark) {
        float cx = size / 2f, cy = size / 2f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        int shadowColor = dark ? 0x55000000 : 0x99FFFFFF;
        int mainColor = dark ? 0xFFD0D8E4 : 0xFF8C8C8C;
        // Main gear (bottom-left of center) and secondary smaller gear (top-right of center)
        drawGear(c, p, cx - 15f * bs, cy + 12f * bs + 2f * bs, 32f * bs, 24f * bs, 11f * bs, 8, 0.2f, shadowColor);
        drawGear(c, p, cx + 24f * bs, cy - 20f * bs + 2f * bs, 22f * bs, 16f * bs, 7.5f * bs, 6, 0.5f, shadowColor);
        drawGear(c, p, cx - 15f * bs, cy + 12f * bs, 32f * bs, 24f * bs, 11f * bs, 8, 0.2f, mainColor);
        drawGear(c, p, cx + 24f * bs, cy - 20f * bs, 22f * bs, 16f * bs, 7.5f * bs, 6, 0.5f, mainColor);
    }

    private static void drawGear(Canvas c, Paint p, float cx, float cy, float outerR, float rootR, float holeR,
            int teeth, float phase, int color) {
        Path gear = new Path();
        gear.setFillType(Path.FillType.EVEN_ODD);
        int steps = teeth * 4;
        float stepAngle = (float) (Math.PI * 2.0 / steps);
        for (int i = 0; i < steps; i++) {
            float a = phase + i * stepAngle;
            boolean tip = (i % 4 == 1) || (i % 4 == 2);
            float r = tip ? outerR : rootR;
            float x = cx + r * (float) Math.cos(a);
            float y = cy + r * (float) Math.sin(a);
            if (i == 0) gear.moveTo(x, y);
            else gear.lineTo(x, y);
        }
        gear.close();
        gear.addCircle(cx, cy, holeR, Path.Direction.CW);
        p.setColor(color);
        c.drawPath(gear, p);
    }

    private void drawEnvelope(Canvas c, int size, float bs, boolean dark) {
        float cx = size / 2f, cy = size / 2f - 4f * bs;
        float tw = 88f * bs, th = 56f * bs;
        RectF tv = new RectF(cx - tw / 2f, cy - th / 2f, cx + tw / 2f, cy + th / 2f);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // TV outer frame
        p.setStyle(Paint.Style.FILL);
        p.setColor(dark ? 0xFFD0D8E4 : 0xFF8C8C8C);
        c.drawRoundRect(tv, 8f * bs, 8f * bs, p);
        // TV inner screen
        RectF screen = new RectF(tv.left + 7f * bs, tv.top + 7f * bs, tv.right - 7f * bs, tv.bottom - 7f * bs);
        p.setColor(dark ? 0xFF282E38 : 0xFFE4E4E4);
        c.drawRoundRect(screen, 4f * bs, 4f * bs, p);
        // TV stand neck & base
        p.setColor(dark ? 0xFFD0D8E4 : 0xFF8C8C8C);
        c.drawRect(cx - 7f * bs, tv.bottom - 1f * bs, cx + 7f * bs, tv.bottom + 10f * bs, p);
        RectF stand = new RectF(cx - 26f * bs, tv.bottom + 8f * bs, cx + 26f * bs, tv.bottom + 15f * bs);
        c.drawRoundRect(stand, 3.5f * bs, 3.5f * bs, p);
        // Input arrow pointing into the screen
        p.setColor(0xFF3FC0EC);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(5.5f * bs);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        float ay = screen.centerY();
        c.drawLine(tv.left - 12f * bs, ay, cx + 6f * bs, ay, p);
        Path head = new Path();
        head.moveTo(cx - 6f * bs, ay - 11f * bs);
        head.lineTo(cx + 8f * bs, ay);
        head.lineTo(cx - 6f * bs, ay + 11f * bs);
        c.drawPath(head, p);
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


    // ---- animation -------------------------------------------------------------------------------

    private static float approach(float v, float target, float k, float eps) {
        float n = v + (target - v) * k;
        return Math.abs(target - n) < eps ? target : n;
    }

    /** Advances every animated value by dt seconds; true while anything is still moving. */
    private boolean step(float dt) {
        float kPage = 1f - (float) Math.exp(-dt / 0.11f);
        float kLift = 1f - (float) Math.exp(-dt / 0.07f);
        float kHand = 1f - (float) Math.exp(-dt / (freeHand ? 0.03f : 0.06f));
        float kMenu = 1f - (float) Math.exp(-dt / 0.08f);

        pagePos = approach(pagePos, page, kPage, 0.0008f);
        boolean moving = pagePos != page;

        int fi = focus == Integer.MIN_VALUE ? -1000 : liftIndex(focus);
        for (int i = 0; i < lift.length; i++) {
            float t = i == fi ? 1f : 0f;
            lift[i] = approach(lift[i], t, kLift, 0.002f);
            if (lift[i] != t) moving = true;
        }

        if (freeHand) {
            point[0] = freeX;
            point[1] = freeY;
        } else {
            handTarget(focus, point);
        }
        if (!handPlaced) {
            handX = point[0];
            handY = point[1];
            handPlaced = true;
        }
        handX = approach(handX, point[0], kHand, 0.3f);
        handY = approach(handY, point[1], kHand, 0.3f);
        handMoving = handX != point[0] || handY != point[1];
        if (handMoving) {
            moving = true;
            if (onHandInvalidate != null) onHandInvalidate.run();
        }

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

        if (hdmiAppearing) {
            hdmiAppearTime += dt;
            float p = Math.min(1.0f, hdmiAppearTime / HDMI_APPEAR_DURATION);
            float t = p - 1.0f;
            float tension = 1.6f;
            float overshoot = t * t * ((tension + 1f) * t + tension) + 1.0f;
            hdmiAppearScale = Math.max(0.01f, overshoot);
            hdmiAppearAlpha = Math.min(1.0f, p / 0.35f);
            hdmiAppearGlow = (float) Math.sin(p * Math.PI);
            if (p >= 1.0f) {
                hdmiAppearing = false;
                hdmiAppearScale = 1.0f;
                hdmiAppearAlpha = 1.0f;
                hdmiAppearGlow = 0f;
                stillGen++;
            }
            moving = true;
        }

        if (hdmiDisappearing) {
            hdmiDisappearTime += dt;
            float p = Math.min(1.0f, hdmiDisappearTime / HDMI_DISAPPEAR_DURATION);
            if (p < 0.15f) {
                float sub = p / 0.15f;
                hdmiAppearScale = 1.0f + 0.08f * (float) Math.sin(sub * Math.PI * 0.5);
                hdmiAppearAlpha = 1.0f;
                hdmiAppearGlow = sub * 0.85f;
            } else {
                float sub = (p - 0.15f) / 0.85f;
                float sc = (1.0f - sub * sub) * 1.08f;
                hdmiAppearScale = Math.max(0.01f, sc);
                hdmiAppearAlpha = Math.max(0f, 1.0f - sub * 1.15f);
                hdmiAppearGlow = Math.max(0f, (1.0f - sub) * 0.85f);
            }
            if (p >= 1.0f) {
                hdmiDisappearing = false;
                hdmiAppearScale = 0.01f;
                hdmiAppearAlpha = 0f;
                hdmiAppearGlow = 0f;
                stillGen++;
                if (onHdmiDisappearComplete != null) {
                    Runnable cb = onHdmiDisappearComplete;
                    onHdmiDisappearComplete = null;
                    post(cb);
                }
            }
            moving = true;
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

        if (frameCallback != null) {
            frameCallback.onFrame(s, pagePos, isIntroShowing(), menuOpen);
        }

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
        int cap = pages * SLOTS_PER_PAGE + 18;
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
        float lft = lift[liftIndex(BTN_THEME)], sct = 1f + 0.08f * lft;
        float tcX = (THEME_L + THEME_R) / 2f * s, tcY = (THEME_T + THEME_B) / 2f * s;
        float thw = ((THEME_R - THEME_L) / 2f + 12f) * s * sct, thh = ((THEME_B - THEME_T) / 2f + 12f) * s * sct;
        add(IT_THEME, 0, tcX - thw, tcY - thh, tcX + thw, tcY + thh, settling(lft));
        float lfm = lift[liftIndex(BTN_MUSIC)], scm = 1f + 0.08f * lfm;
        float mcX = (MUSIC_L + MUSIC_R) / 2f * s, mcY = (MUSIC_T + MUSIC_B) / 2f * s;
        float mhw = ((MUSIC_R - MUSIC_L) / 2f + 12f) * s * scm, mhh = ((MUSIC_B - MUSIC_T) / 2f + 12f) * s * scm;
        add(IT_MUSIC, 0, mcX - mhw, mcY - mhh, mcX + mhw, mcY + mhh, settling(lfm));

        float lfp = lift[liftIndex(BTN_POWER)], scp = 1f + 0.08f * lfp;
        float pcX = (POWER_L + POWER_R) / 2f * s, pcY = (POWER_T + POWER_B) / 2f * s;
        float phw = ((POWER_R - POWER_L) / 2f + 12f) * s * scp, phh = ((POWER_B - POWER_T) / 2f + 12f) * s * scp;
        add(IT_POWER, 0, pcX - phw, pcY - phh, pcX + phw, pcY + phh, settling(lfp));
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
        float half = Math.max(dateTotal, statsTotal) / 2f + 8f * s;
        add(IT_DATE, 0, WiiBase.CX * s - half, (DATE_BASE - DATE_CAP - 14f) * s, WiiBase.CX * s + half,
                (STATS_BASE + 20f) * s, false);
        // Carrying a channel, the hand closes on it; the argument tells the still layer it changed.
        hand.grabbing = carrying;
        hand.bounds(handX, handY, tmp);
        add(IT_HAND, carrying ? 1 : 0, tmp.left - 2f, tmp.top - 2f, tmp.right + 2f, tmp.bottom + 2f, handMoving);
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
        boolean isThumbLive = (sl == thumbActiveSlot && thumbIsPlaying && thumbActiveDrawable != null);
        boolean live = (ch != null && ch.banner instanceof LiveArt && ((LiveArt) ch.banner).animating()) || isThumbLive;
        liveShown |= live;
        float lf = lift[sl];
        // How far the bulge, border and glow reach past the box; neighbouring tiles' reaches never meet.
        float out = (TILE_BULGE + 1.5f + (lf > 0.01f ? (TILE_BORDER + 15f) / 2f : (TILE_BORDER + 1.5f) / 2f)) * s;
        if (carrying && sl == focus) out += (TILE_W * (CARRY_SCALE - 1f) / 2f + CARRY_SHADOW + 2f) * s;
        if (sl == 0 && (hdmiAppearing || hdmiDisappearing)) out += 45f * s;
        add(IT_TILE, sl, rect.left - out, rect.top - out, rect.right + out, rect.bottom + out,
                scrolling || live || settling(lf) || (sl == 0 && (hdmiAppearing || hdmiDisappearing)));
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
        sig[n++] = clockHour * 100 + clockMinute + (clockPm ? 10_000 : 0) + (clock24 ? 20_000 : 0) + (darkMode ? 40_000 : 0);
        sig[n++] = dateText.hashCode();
        sig[n++] = statsText.hashCode();
        for (int i = 0; i < itemCount; i++) {
            if (itemDirect[i]) continue;
            int kind = itemKind[i];
            sig[n++] = kind << 24 | itemArg[i];
            if (kind == IT_TILE) sig[n++] = Float.floatToIntBits(lift[itemArg[i]]);
            else if (kind == IT_BTN_L) sig[n++] = Float.floatToIntBits(lift[liftIndex(BTN_SETTINGS)]);
            else if (kind == IT_BTN_R) sig[n++] = Float.floatToIntBits(lift[liftIndex(BTN_INPUTS)]);
            else if (kind == IT_SD) sig[n++] = Float.floatToIntBits(lift[liftIndex(BTN_SD)]);
            else if (kind == IT_THEME) sig[n++] = Float.floatToIntBits(lift[liftIndex(BTN_THEME)]);
            else if (kind == IT_MUSIC) sig[n++] = Float.floatToIntBits(lift[liftIndex(BTN_MUSIC)]);
            else if (kind == IT_POWER) sig[n++] = Float.floatToIntBits(lift[liftIndex(BTN_POWER)]);
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
                else c.drawColor(darkMode ? 0xFF1A1D24 : 0xFFE9E9E9);
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
            case IT_THEME:
                drawThemeBtn(c);
                break;
            case IT_MUSIC:
                drawMusicBtn(c);
                break;
            case IT_POWER:
                drawPowerBtn(c);
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
                if (!externalHand) {
                    hand.draw(c, handX, handY);
                }
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
        if (slot == 0 && (hdmiAppearing || hdmiDisappearing)) {
            c.scale(hdmiAppearScale, hdmiAppearScale, r.width() / 2f, r.height() / 2f);
        }
        Channel ch = slot < channels.size() ? channels.get(slot) : null;
        if (live && ch != null && ch.banner instanceof LiveArt) {
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
                if (slot == 0 && (hdmiAppearing || hdmiDisappearing) && hdmiAppearScale > 0.01f) {
                    matrix.postScale(1f / hdmiAppearScale, 1f / hdmiAppearScale, r.width() / 2f, r.height() / 2f);
                }
                underShader.setLocalMatrix(matrix);
                under.setShader(underShader);
            } else {
                under.setShader(null);
                under.setColor(darkMode ? 0xFF1A1D24 : 0xFFE9E9E9);
            }
            c.drawPath(tileOutside, under);
            under.setShader(null);
            c.restoreToCount(clip);
        } else if (ch != null) {
            ensureArt(ch, Math.round(r.width()), Math.round(r.height()));
            artPaint.setShader(ch.shader);
            c.drawPath(tilePath, artPaint);
            artPaint.setShader(null);
            if (slot == thumbActiveSlot && thumbIsPlaying && thumbActiveDrawable != null) {
                drawActiveThumbnail(c, Math.round(r.width()), Math.round(r.height()));
            }
        } else if (emptyShader != null) {
            matrix.setTranslate(-4f, -4f);
            emptyShader.setLocalMatrix(matrix);
            artPaint.setShader(emptyShader);
            c.drawPath(tilePath, artPaint);
            artPaint.setShader(null);
        } else {
            fill.setColor(darkMode ? 0xFF282C35 : 0xFFD6D6D6);
            c.drawPath(tilePath, fill);
        }
        if (slot == 0 && hdmiAppearGlow > 0.01f) {
            int neonBlue = 0xFF00D2FF;
            for (int k = 4; k >= 1; k--) {
                stroke.setColor(withAlpha(neonBlue, (int) (hdmiAppearGlow * 130 / k)));
                stroke.setStrokeWidth((TILE_BORDER + k * 8f) * s);
                c.drawPath(tilePath, stroke);
            }
        }
        if (lf > 0.01f) {
            for (int k = 3; k >= 1; k--) {
                stroke.setColor(withAlpha(BLUE, (int) (lf * 64 / k)));
                stroke.setStrokeWidth((TILE_BORDER + k * 5f) * s);
                c.drawPath(tilePath, stroke);
            }
        }
        int borderCol = darkMode ? 0xFF454C59 : TILE_BORDER_COLOR;
        if (slot == 0 && hdmiAppearGlow > 0.01f) {
            borderCol = blend(borderCol, 0xFF00D2FF, hdmiAppearGlow);
        }
        stroke.setColor(blend(borderCol, BLUE, lf));
        stroke.setStrokeWidth((TILE_BORDER + 1.5f * lf + (slot == 0 ? 2f * hdmiAppearGlow : 0f)) * s);
        c.drawPath(tilePath, stroke);
        c.restoreToCount(save);
    }

    private void drawActiveThumbnail(Canvas c, int tw, int th) {
        if (thumbActiveDrawable == null) return;
        int iw = thumbActiveDrawable.getIntrinsicWidth();
        int ih = thumbActiveDrawable.getIntrinsicHeight();
        if (iw <= 0) iw = WiiThumbnailManager.THUMB_WIDTH;
        if (ih <= 0) ih = WiiThumbnailManager.THUMB_HEIGHT;
        float sc = Math.max(tw / (float) iw, th / (float) ih);
        int dw = Math.round(iw * sc);
        int dh = Math.round(ih * sc);
        int dx = (tw - dw) / 2;
        int dy = (th - dh) / 2;

        int save = c.save();
        c.clipPath(tilePath);
        thumbActiveDrawable.setBounds(dx, dy, dx + dw, dy + dh);
        thumbActiveDrawable.draw(c);
        c.restoreToCount(save);
    }

    private void ensureArt(Channel ch, int tw, int th) {
        tw = Math.max(1, tw);
        th = Math.max(1, th);
        if (ch.art != null && ch.art.getWidth() == tw && ch.art.getHeight() == th) return;
        Bitmap bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        cv.drawColor(darkMode ? 0xFF242830 : 0xFFFFFFFF);
        boolean hasThumb = WiiThumbnailManager.hasThumbnail(getContext(), ch.key);
        if (hasThumb) {
            Bitmap firstFrame = WiiThumbnailManager.getFirstFrame(getContext(), ch.key);
            if (firstFrame != null && !firstFrame.isRecycled()) {
                int iw = firstFrame.getWidth();
                int ih = firstFrame.getHeight();
                float sc = Math.max(tw / (float) iw, th / (float) ih);
                int dw = Math.round(iw * sc);
                int dh = Math.round(ih * sc);
                int dx = (tw - dw) / 2;
                int dy = (th - dh) / 2;
                cv.drawBitmap(firstFrame, null, new RectF(dx, dy, dx + dw, dy + dh), bmpPaint);
            } else {
                cv.drawColor(darkMode ? 0xFF242830 : 0xFFFFFFFF);
            }
        } else if (ch.banner != null) {
            drawCropped(cv, ch.banner, tw, th);
        } else {
            Paint bg = new Paint();
            int topC = darkMode ? 0xFF2E3440 : 0xFFFFFFFF;
            int botC = darkMode ? 0xFF1F232B : 0xFFE9E9E9;
            bg.setShader(new LinearGradient(0, 0, 0, th, topC, botC, Shader.TileMode.CLAMP));
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
            tp.setColor(darkMode ? 0xFFD0D7E2 : 0xFF7A7A7A);
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
        float cx = (SD_L + SD_R) / 2f * s, cy = (SD_T + SD_B) / 2f * s;
        float sc = 1f + 0.08f * lf;
        c.scale(sc, sc, cx, cy);
        RectF r = new RectF(SD_L * s, SD_T * s, SD_R * s, SD_B * s);
        float rad = 14f * s;
        // Shadow
        c.translate(4f * s, 4f * s);
        fill.setColor(0x2E000000);
        c.drawRoundRect(r, rad, rad, fill);
        c.translate(-4f * s, -4f * s);
        // Body
        int btnBg = darkMode ? 0xFF2B3240 : 0xFFE8ECF2;
        fill.setColor(btnBg);
        c.drawRoundRect(r, rad, rad, fill);
        stroke.setColor(blend(darkMode ? 0xFF5A6578 : 0xFFA5B2C2, BLUE, lf));
        stroke.setStrokeWidth(3f * s);
        c.drawRoundRect(r, rad, rad, stroke);
        if (lf > 0.01f) {
            for (int k = 3; k >= 1; k--) {
                stroke.setColor(withAlpha(BLUE, (int) (lf * 80 / k)));
                stroke.setStrokeWidth((2f + k * 4f) * s);
                c.drawRoundRect(r, rad, rad, stroke);
            }
        }

        // USB Flash Drive (Memoria USB) Icon
        // 1. Metal USB plug connector (top)
        int plugCol = darkMode ? 0xFFCCD5E0 : 0xFF7E8C9E;
        fill.setColor(plugCol);
        RectF plug = new RectF(cx - 8f * s, cy - 22f * s, cx + 8f * s, cy - 9f * s);
        c.drawRoundRect(plug, 1.5f * s, 1.5f * s, fill);

        // Holes in metal plug (Type-A connector holes)
        fill.setColor(btnBg);
        RectF holeL = new RectF(cx - 5.5f * s, cy - 18.5f * s, cx - 2f * s, cy - 14f * s);
        c.drawRoundRect(holeL, 0.8f * s, 0.8f * s, fill);
        RectF holeR = new RectF(cx + 2f * s, cy - 18.5f * s, cx + 5.5f * s, cy - 14f * s);
        c.drawRoundRect(holeR, 0.8f * s, 0.8f * s, fill);

        // 2. Drive Body (casing)
        int bodyCol = darkMode ? 0xFF5CC8EE : 0xFF4FC1EA;
        fill.setColor(bodyCol);
        RectF body = new RectF(cx - 13f * s, cy - 9f * s, cx + 13f * s, cy + 22f * s);
        c.drawRoundRect(body, 4f * s, 4f * s, fill);

        // Lanyard loop / keyring cutout at the bottom
        fill.setColor(btnBg);
        RectF loop = new RectF(cx - 4.5f * s, cy + 15f * s, cx + 4.5f * s, cy + 18f * s);
        c.drawRoundRect(loop, 1.5f * s, 1.5f * s, fill);

        // 3. LED Indicator
        fill.setColor(darkMode ? 0xFFE0F7FA : 0xFFFFFFFF);
        c.drawCircle(cx, cy - 3.5f * s, 1.6f * s, fill);

        // 4. "USB" text label on body
        text.setTypeface(sdFace);
        text.setTextSize(9.5f * s);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(darkMode ? 0xFF142638 : 0xFF0D253A);
        c.drawText("USB", cx, cy + 6f * s, text);

        c.restoreToCount(save);
    }

    private void drawThemeBtn(Canvas c) {
        float lf = lift[liftIndex(BTN_THEME)];
        int save = c.save();
        float cx = (THEME_L + THEME_R) / 2f * s, cy = (THEME_T + THEME_B) / 2f * s;
        float sc = 1f + 0.08f * lf;
        c.scale(sc, sc, cx, cy);
        RectF r = new RectF(THEME_L * s, THEME_T * s, THEME_R * s, THEME_B * s);
        float rad = 14f * s;
        // Shadow
        c.translate(4f * s, 4f * s);
        fill.setColor(0x2E000000);
        c.drawRoundRect(r, rad, rad, fill);
        c.translate(-4f * s, -4f * s);
        // Body
        fill.setColor(darkMode ? 0xFF2B3240 : 0xFFE8ECF2);
        c.drawRoundRect(r, rad, rad, fill);
        stroke.setColor(blend(darkMode ? 0xFF5A6578 : 0xFFA5B2C2, BLUE, lf));
        stroke.setStrokeWidth(3f * s);
        c.drawRoundRect(r, rad, rad, stroke);
        if (lf > 0.01f) {
            for (int k = 3; k >= 1; k--) {
                stroke.setColor(withAlpha(BLUE, (int) (lf * 80 / k)));
                stroke.setStrokeWidth((2f + k * 4f) * s);
                c.drawRoundRect(r, rad, rad, stroke);
            }
        }
        if (darkMode) {
            // Draw golden Sun icon
            fill.setColor(0xFFFFC83B);
            c.drawCircle(cx, cy, 11f * s, fill);
            stroke.setColor(0xFFFFC83B);
            stroke.setStrokeWidth(3.2f * s);
            for (int i = 0; i < 8; i++) {
                float a = (float) (i * Math.PI / 4.0);
                float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
                c.drawLine(cx + cos * 15f * s, cy + sin * 15f * s, cx + cos * 21f * s, cy + sin * 21f * s, stroke);
            }
        } else {
            // Draw crescent Moon icon
            Path moon = new Path();
            moon.setFillType(Path.FillType.EVEN_ODD);
            moon.addCircle(cx - 2f * s, cy, 17f * s, Path.Direction.CW);
            moon.addCircle(cx + 7f * s, cy - 5f * s, 14f * s, Path.Direction.CW);
            fill.setColor(0xFF4C5B75);
            c.drawPath(moon, fill);
        }
        c.restoreToCount(save);
    }

    private void drawMusicBtn(Canvas c) {
        float lf = lift[liftIndex(BTN_MUSIC)];
        int save = c.save();
        float cx = (MUSIC_L + MUSIC_R) / 2f * s, cy = (MUSIC_T + MUSIC_B) / 2f * s;
        float sc = 1f + 0.08f * lf;
        c.scale(sc, sc, cx, cy);
        RectF r = new RectF(MUSIC_L * s, MUSIC_T * s, MUSIC_R * s, MUSIC_B * s);
        float rad = 14f * s;
        // Shadow
        c.translate(4f * s, 4f * s);
        fill.setColor(0x2E000000);
        c.drawRoundRect(r, rad, rad, fill);
        c.translate(-4f * s, -4f * s);
        // Body
        fill.setColor(darkMode ? 0xFF2B3240 : 0xFFE8ECF2);
        c.drawRoundRect(r, rad, rad, fill);
        stroke.setColor(blend(darkMode ? 0xFF5A6578 : 0xFFA5B2C2, BLUE, lf));
        stroke.setStrokeWidth(3f * s);
        c.drawRoundRect(r, rad, rad, stroke);
        if (lf > 0.01f) {
            for (int k = 3; k >= 1; k--) {
                stroke.setColor(withAlpha(BLUE, (int) (lf * 80 / k)));
                stroke.setStrokeWidth((2f + k * 4f) * s);
                c.drawRoundRect(r, rad, rad, stroke);
            }
        }
        // Icon: Musical notes (beamed eighth notes)
        int iconCol = musicMuted ? (darkMode ? 0xFF8892A0 : 0xFF9E9E9E) : (darkMode ? 0xFF5CC8EE : 0xFF4FC1EA);
        fill.setColor(iconCol);
        stroke.setColor(iconCol);
        stroke.setStrokeWidth(2.6f * s);

        // Note 1 (left)
        c.save();
        c.translate(cx - 7f * s, cy + 8f * s);
        c.rotate(-22f);
        RectF n1 = new RectF(-5f * s, -3.5f * s, 5f * s, 3.5f * s);
        c.drawOval(n1, fill);
        c.restore();

        // Note 2 (right)
        c.save();
        c.translate(cx + 7f * s, cy + 4f * s);
        c.rotate(-22f);
        RectF n2 = new RectF(-5f * s, -3.5f * s, 5f * s, 3.5f * s);
        c.drawOval(n2, fill);
        c.restore();

        // Stems
        c.drawLine(cx - 3.5f * s, cy + 7f * s, cx - 3.5f * s, cy - 9f * s, stroke);
        c.drawLine(cx + 10.5f * s, cy + 3f * s, cx + 10.5f * s, cy - 13f * s, stroke);

        // Beam
        stroke.setStrokeWidth(4.2f * s);
        c.drawLine(cx - 4.5f * s, cy - 8f * s, cx + 11.5f * s, cy - 12f * s, stroke);

        // If muted, draw diagonal slash
        if (musicMuted) {
            stroke.setColor(0xFFE53935);
            stroke.setStrokeWidth(3f * s);
            c.drawLine(cx - 12f * s, cy + 12f * s, cx + 14f * s, cy - 14f * s, stroke);
        }

        c.restoreToCount(save);
    }

    private void drawPowerBtn(Canvas c) {
        float lf = lift[liftIndex(BTN_POWER)];
        int save = c.save();
        float cx = (POWER_L + POWER_R) / 2f * s, cy = (POWER_T + POWER_B) / 2f * s;
        float sc = 1f + 0.08f * lf;
        c.scale(sc, sc, cx, cy);
        RectF r = new RectF(POWER_L * s, POWER_T * s, POWER_R * s, POWER_B * s);
        float rad = 14f * s;
        // Shadow
        c.translate(4f * s, 4f * s);
        fill.setColor(0x2E000000);
        c.drawRoundRect(r, rad, rad, fill);
        c.translate(-4f * s, -4f * s);
        // Body
        fill.setColor(darkMode ? 0xFF2B3240 : 0xFFE8ECF2);
        c.drawRoundRect(r, rad, rad, fill);
        stroke.setColor(blend(darkMode ? 0xFF5A6578 : 0xFFA5B2C2, 0xFFE53935, lf));
        stroke.setStrokeWidth(3f * s);
        c.drawRoundRect(r, rad, rad, stroke);
        if (lf > 0.01f) {
            for (int k = 3; k >= 1; k--) {
                stroke.setColor(withAlpha(0xFFE53935, (int) (lf * 80 / k)));
                stroke.setStrokeWidth((2f + k * 4f) * s);
                c.drawRoundRect(r, rad, rad, stroke);
            }
        }
        // Classic Power Symbol (circle open at top + vertical bar)
        stroke.setColor(0xFFE53935);
        stroke.setStrokeWidth(4.5f * s);
        float pr = 16f * s;
        RectF arc = new RectF(cx - pr, cy - pr + 2f * s, cx + pr, cy + pr + 2f * s);
        c.drawArc(arc, -55f, 290f, false, stroke);
        c.drawLine(cx, cy - pr - 4f * s, cx, cy + 1f * s, stroke);
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
                Locale es = new Locale("es", "ES");
                String day = new SimpleDateFormat("EEEE", es).format(at).replace(".", "");
                if (!day.isEmpty()) day = day.substring(0, 1).toUpperCase(es) + day.substring(1);
                String month = new SimpleDateFormat("MMMM", es).format(at).replace(".", "");
                if (!month.isEmpty()) month = month.substring(0, 1).toUpperCase(es) + month.substring(1);
                date = day + " " + now.get(Calendar.DAY_OF_MONTH) + " de " + month + " del " + now.get(Calendar.YEAR);
            }
        }
        updateStats();
        if (!date.equals(dateText) || dateScale != s) {
            dateText = date;
            dateScale = s;
            measureDate();
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0);
        } else if (bytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format(Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    private void updateStats() {
        long now = SystemClock.uptimeMillis();
        if (now - lastStatsUpdateMs < 800L) return;
        lastStatsUpdateMs = now;

        long storageUsed = 0, storageFree = 0;
        try {
            File dataDir = Environment.getDataDirectory();
            StatFs stat = new StatFs(dataDir.getPath());
            long blockSize = stat.getBlockSizeLong();
            long total = stat.getBlockCountLong() * blockSize;
            storageFree = stat.getAvailableBlocksLong() * blockSize;
            storageUsed = Math.max(0, total - storageFree);
        } catch (Throwable ignored) {}

        long ramUsed = 0, ramFree = 0;
        try {
            ActivityManager am = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                ramFree = mi.availMem;
                ramUsed = Math.max(0, mi.totalMem - mi.availMem);
            }
        } catch (Throwable ignored) {}

        String newStats = "Almacenamiento: " + formatBytes(storageUsed) + " ocupado / " + formatBytes(storageFree)
                + " libre   •   RAM: " + formatBytes(ramUsed) + " ocupado / " + formatBytes(ramFree) + " libre";

        if (!newStats.equals(statsText)) {
            statsText = newStats;
            measureStats();
        }
    }

    private void statsFont() {
        text.setTypeface(sansBold);
        text.setTextSize(18f * s);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(darkMode ? 0xFF8E99A8 : 0xFF8E8E8E);
        text.setLetterSpacing(0.01f);
        if (statsText != null && !statsText.isEmpty()) {
            float wText = text.measureText(statsText);
            float maxW = 820f * s;
            if (wText > maxW && wText > 0f) {
                text.setTextSize((18f * s) * (maxW / wText));
            }
        }
    }

    private void measureStats() {
        statsFont();
        statsTotal = text.measureText(statsText);
        text.setLetterSpacing(0f);
    }

    private void dateFont() {
        text.setTypeface(sansBold);
        text.setTextSize(36f / 0.711f * s);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(darkMode ? 0xFFB0B8C5 : DATE_COLOR);
        text.setLetterSpacing(0.02f);
        if (dateText != null && !dateText.isEmpty()) {
            float wText = text.measureText(dateText);
            float maxW = 740f * s;
            if (wText > maxW && wText > 0f) text.setTextSize((36f / 0.711f * s) * (maxW / wText));
        }
    }

    private void measureDate() {
        dateDay = "";
        dateRest = dateText;
        dateFont();
        dateRestW = text.measureText(dateRest);
        dateTotal = dateRestW;
        text.setLetterSpacing(0f);
    }

    private void drawDigits(Canvas c) {
        fill.setColor(darkMode ? 0xFFD0D7E2 : CLOCK_COLOR);
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
        fill.setColor(darkMode ? 0xFFD0D7E2 : CLOCK_COLOR);
        c.drawRoundRect((COLON_X - d) * s, (COLON_Y1 - d) * s, (COLON_X + d) * s, (COLON_Y1 + d) * s, 2f * s, 2f * s, fill);
        c.drawRoundRect((COLON_X - d) * s, (COLON_Y2 - d) * s, (COLON_X + d) * s, (COLON_Y2 + d) * s, 2f * s, 2f * s, fill);
    }

    private void drawAmPm(Canvas c) {
        text.setTypeface(sansBold);
        text.setTextSize(AMPM_CAP / 0.711f * s);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(darkMode ? 0xFFD0D7E2 : CLOCK_COLOR);
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

        if (statsText != null && !statsText.isEmpty()) {
            statsFont();
            c.drawText(statsText, WiiBase.CX * s, STATS_BASE * s, text);
            text.setLetterSpacing(0f);
        }
    }

    private void drawMenu(Canvas c) {
        int a = Math.round(menuAlpha * 255);
        int save = a < 255 ? c.saveLayerAlpha(0, 0, w, h, a) : c.save();
        fill.setColor(0x78000000);
        c.drawRect(0, 0, w, h, fill);
        int n = menuItems.size();
        float pw = 1000f * s, itemH = 92f * s, gap = 22f * s, head = 150f * s, pad = 56f * s;
        float totalItemsH = n * itemH + (n - 1) * gap;
        float ph = head + totalItemsH + pad;
        float maxPh = h - 80f * s;
        float actualPh = Math.min(ph, maxPh);
        float px = (w - pw) / 2f, py = (h - actualPh) / 2f;
        c.translate(0f, (1f - menuAlpha) * 36f * s);
        float rad = 40f * s;
        rect.set(px, py, px + pw, py + actualPh);
        if (base != null) {
            fill.setColor(0xFFFFFFFF);
            fill.setShader(baseShader);
            c.drawRoundRect(rect, rad, rad, fill);
            fill.setShader(null);
        } else {
            fill.setColor(darkMode ? 0xFF1F232B : 0xFFEDEDED);
            c.drawRoundRect(rect, rad, rad, fill);
        }
        int borderCol = darkMode ? 0xFF454C59 : TILE_BORDER_COLOR;
        stroke.setColor(borderCol);
        stroke.setStrokeWidth(TILE_BORDER * s);
        c.drawRoundRect(rect, rad, rad, stroke);

        text.setTypeface(sansBold);
        text.setTextSize(48f * s);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(darkMode ? 0xFFD0D7E2 : DATE_COLOR);
        c.drawText(menuTitle, w / 2f, py + 92f * s, text);

        text.setTextSize(40f * s);
        if (menuShaders == null || menuShaders.length != n) menuShaders = new LinearGradient[n];
        float viewTop = py + head - 12f * s;
        float viewBottom = py + actualPh - pad / 2f;
        float viewH = viewBottom - viewTop;
        float maxScroll = Math.max(0f, totalItemsH - viewH + 20f * s);
        float idealScroll = (menuFocus * (itemH + gap)) - (viewH / 2f) + (itemH / 2f);
        float scroll = Math.max(0f, Math.min(maxScroll, idealScroll));

        int saveItems = c.save();
        c.clipRect(px, viewTop, px + pw, viewBottom);
        c.translate(0f, -scroll);
        for (int i = 0; i < n; i++) {
            float iy = py + head + i * (itemH + gap);
            tmp.set(px + 90f * s, iy, px + pw - 90f * s, iy + itemH);
            float lf = i < menuLift.length ? menuLift[i] : 0f;
            float ir = itemH / 2f;
            fill.setColor(0xFFFFFFFF);
            if (menuShaders[i] == null) {
                int c0 = darkMode ? 0xFF2E3440 : 0xFFFFFFFF;
                int c1 = darkMode ? 0xFF232832 : 0xFFE2E2E2;
                menuShaders[i] = new LinearGradient(0, tmp.top, 0, tmp.bottom, c0, c1,
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
            stroke.setColor(blend(borderCol, BLUE, lf));
            stroke.setStrokeWidth((TILE_BORDER + 2f * lf) * s);
            c.drawRoundRect(tmp, ir, ir, stroke);
            text.setColor(darkMode ? 0xFFD8DEE9 : 0xFF666666);
            CharSequence label = TextUtils.ellipsize(menuItems.get(i).label, text, tmp.width() - 70f * s,
                    TextUtils.TruncateAt.END);
            c.drawText(label, 0, label.length(), tmp.centerX(), tmp.centerY() + 14f * s, text);
        }
        c.restoreToCount(saveItems);
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
