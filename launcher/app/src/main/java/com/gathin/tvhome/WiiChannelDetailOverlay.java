package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Authentic Nintendo Wii Channel Detail Screen:
 * - Adapts cleanly to both Dark and Light themes chosen by the user.
 * - High-contrast, beautifully legible typography across all screens.
 * - Upper stage with complete, static default app artwork and single entrance animation.
 * - Dynamic video window covering 100% of the available stage space without margins or gaps.
 * - Native Android App Info access (force stop, uninstall, permissions).
 * - Classic curved bottom console drawer with textured scanlines and extra-large 3D acrylic bubble buttons.
 * - Wii Hand cursor with bulletproof DPAD, Wiimote, and HOME button support.
 */
public class WiiChannelDetailOverlay extends FrameLayout {

    public interface Listener {
        void onStartApp(WiiMenuView.Channel channel);
        void onChannelClosed();
    }

    private static final int REF_W = 1920;
    private static final int REF_H = 1080;

    // States
    private static final int STATE_CHANNEL = 0;
    private static final int STATE_CONFIG = 1;
    private static final int STATE_VIDEO_PICKER = 2;

    // Colors
    private static final int BLUE_GLOW = 0xFF3EBCF8;

    private int currentState = STATE_CHANNEL;
    private boolean isOpen = false;
    private boolean darkMode = false;

    private final Listener listener;
    private final WiiSounds sounds;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> thumbCache = new LruCache<>(30);

    private WiiMenuView.Channel currentChannel;
    private Drawable cachedChannelDrawable;
    private boolean cachedIsBanner;
    private float scale = 1f;

    // Wii Hand cursor
    private final WiiHand hand;
    private float handX = 960f;
    private float handY = 900f;
    private ValueAnimator handAnimator;

    // Paints
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path barPath = new Path();
    private final Path arrowPath = new Path();
    private final RectF btnRect = new RectF();
    private final RectF specRect = new RectF();

    // Central Video Display (fills 100% of stage space, audio included in video)
    private final TextureView videoTextureView;
    private Surface channelSurface;
    private int currentVideoWidth = 0;
    private int currentVideoHeight = 0;
    private int videoTargetW = 0;
    private int videoTargetH = 0;
    private int videoTargetL = 0;
    private int videoTargetT = 0;

    // Focus tracking
    // Channel mode: 0 = Gear (Config), 1 = Volver / Wii Menu, 2 = Iniciar / Start
    private int channelFocus = 2;
    // Config mode: 0 = Personalizar Video, 1 = Información de la Aplicación, 2 = Volver al Canal
    private int configFocus = 0;
    // Video picker
    private int videoListIndex = 0;
    private int videoScrollOffset = 0;
    private int videoBottomFocus = 0; // 0 = in carousel, 1 = Volver, 2 = Aplicar

    // Media
    private MediaPlayer channelVideoPlayer;
    private MediaPlayer carouselVideoPlayer;

    private List<File> videoFiles = new ArrayList<>();
    private String selectedVideoPath;

    // Single entrance animation: plays once and remains completely static
    private float entranceScale = 1f;
    private float entranceAlpha = 1f;
    private ValueAnimator entranceAnimator;

    // Zoom-in / Zoom-out
    private float zoomProgress = 1f;
    private float barSlideProgress = 1f;
    private ValueAnimator zoomAnimator;

    public WiiChannelDetailOverlay(Context context, Listener listener, WiiSounds sounds) {
        super(context);
        this.listener = listener;
        this.sounds = sounds;

        setFocusable(false);
        setClickable(true);
        setVisibility(View.GONE);
        setWillNotDraw(false);

        Typeface boldTf;
        try {
            boldTf = context.getResources().getFont(R.font.outfit_medium);
        } catch (Throwable ignored) {
            boldTf = Typeface.DEFAULT_BOLD;
        }
        textPaint.setTypeface(boldTf);
        textPaint.setTextAlign(Paint.Align.CENTER);

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setColor(BLUE_GLOW);

        hand = new WiiHand(boldTf);

        // Video TextureView for custom MP4 playback
        videoTextureView = new TextureView(context);
        videoTextureView.setVisibility(View.GONE);
        videoTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                channelSurface = new Surface(surface);
                if (channelVideoPlayer != null) {
                    channelVideoPlayer.setSurface(channelSurface);
                }
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}
            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                if (channelVideoPlayer != null) {
                    channelVideoPlayer.setSurface(null);
                }
                if (channelSurface != null) {
                    channelSurface.release();
                    channelSurface = null;
                }
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
        });
        addView(videoTextureView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public boolean isOpen() {
        return isOpen;
    }

    public void setDarkMode(boolean darkMode) {
        this.darkMode = darkMode;
        invalidate();
    }

    // --- THEME-AWARE COLOR PALETTE ---

    private int getStageBgColor() {
        return darkMode ? 0xFF0E1218 : 0xFFF0F2F5;
    }

    private int getDrawerBgColor() {
        return darkMode ? 0xFF1A1F28 : 0xFFE2E2DE;
    }

    private int getDrawerBorderColor() {
        return darkMode ? 0xFF2A3240 : 0xFFB8B8B4;
    }

    private int getDrawerLineColor() {
        return darkMode ? 0x14FFFFFF : 0x12000000;
    }

    private int getArrowColor() {
        return darkMode ? 0xFF5AC6F8 : 0xFF0098D8;
    }

    private int getButtonBgTop() {
        return darkMode ? 0xFF2E3646 : 0xFFFFFFFF;
    }

    private int getButtonBgBottom() {
        return darkMode ? 0xFF1E2430 : 0xFFDFE2E8;
    }

    private int getButtonBorderColor(boolean focused) {
        if (focused) return BLUE_GLOW;
        return darkMode ? 0xFF465366 : 0xFFB0D0E6;
    }

    private int getButtonTextColor() {
        return darkMode ? 0xFFF2F5F8 : 0xFF1C222A;
    }

    private int getPrimaryTextColor() {
        return darkMode ? 0xFFFFFFFF : 0xFF181E26;
    }

    private int getSecondaryTextColor() {
        return darkMode ? 0xFF9EABB8 : 0xFF5A6674;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        scale = w / (float) REF_W;
        hand.setScale(scale);
        if (currentVideoWidth > 0 && currentVideoHeight > 0) {
            adjustVideoWindowSize(currentVideoWidth, currentVideoHeight);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (videoTextureView.getVisibility() == View.VISIBLE && videoTargetW > 0 && videoTargetH > 0) {
            videoTextureView.layout(videoTargetL, videoTargetT, videoTargetL + videoTargetW, videoTargetT + videoTargetH);
        }
    }

    /**
     * Enters the selected channel with authentic Wii zoom-in and entrance animations.
     */
    public void openChannel(WiiMenuView.Channel channel, RectF slotRect) {
        this.currentChannel = channel;
        this.isOpen = true;
        this.currentState = STATE_CHANNEL;
        this.channelFocus = 2; // Default on Start / Iniciar

        // Pre-cache artwork ONCE to eliminate any IPC or GC during draw
        if (channel != null) {
            if (channel.banner != null) {
                cachedChannelDrawable = channel.banner;
                cachedIsBanner = true;
            } else if (channel.icon != null) {
                cachedChannelDrawable = channel.icon;
                cachedIsBanner = false;
            } else {
                try {
                    cachedChannelDrawable = getContext().getPackageManager().getApplicationIcon(channel.key);
                } catch (Throwable ignored) {
                    cachedChannelDrawable = null;
                }
                cachedIsBanner = false;
            }
        } else {
            cachedChannelDrawable = null;
            cachedIsBanner = false;
        }

        setVisibility(View.VISIBLE);
        refreshMedia();

        // 1. Zoom-in animation (bottom console drawer slides up)
        if (zoomAnimator != null) zoomAnimator.cancel();
        zoomProgress = 0f;
        barSlideProgress = 0f;

        zoomAnimator = ValueAnimator.ofFloat(0f, 1f);
        zoomAnimator.setDuration(360);
        zoomAnimator.setInterpolator(new DecelerateInterpolator(1.4f));
        zoomAnimator.addUpdateListener(anim -> {
            float f = (float) anim.getAnimatedValue();
            zoomProgress = f;
            barSlideProgress = f;
            invalidate();
        });
        zoomAnimator.start();

        // Initial hand position right on Start button
        float btnY = (REF_H - 145f) * scale;
        handX = 1400f * scale;
        handY = btnY - 72f * scale;

        // 2. Single entrance pop-in animation (remains completely static when finished)
        playEntranceAnimation();
    }

    private void playEntranceAnimation() {
        if (entranceAnimator != null) entranceAnimator.cancel();
        entranceScale = 0.5f;
        entranceAlpha = 0f;

        entranceAnimator = ValueAnimator.ofFloat(0f, 1f);
        entranceAnimator.setDuration(380);
        entranceAnimator.setInterpolator(new OvershootInterpolator(1.2f));
        entranceAnimator.addUpdateListener(anim -> {
            float f = (float) anim.getAnimatedValue();
            entranceScale = 0.5f + 0.5f * f;
            entranceAlpha = Math.min(1f, f * 1.5f);
            if (videoTextureView.getVisibility() == View.VISIBLE) {
                videoTextureView.setScaleX(entranceScale);
                videoTextureView.setScaleY(entranceScale);
                videoTextureView.setAlpha(entranceAlpha);
            }
            invalidate();
        });
        entranceAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                entranceScale = 1f;
                entranceAlpha = 1f;
                if (videoTextureView.getVisibility() == View.VISIBLE) {
                    videoTextureView.setScaleX(1f);
                    videoTextureView.setScaleY(1f);
                    videoTextureView.setAlpha(1f);
                }
                invalidate();
            }
        });
        entranceAnimator.start();
    }

    /**
     * Exits the channel with authentic Wii zoom-out animation back to the main menu grid.
     */
    public void startZoomOut() {
        if (zoomAnimator != null) zoomAnimator.cancel();
        if (entranceAnimator != null) entranceAnimator.cancel();
        if (handAnimator != null) handAnimator.cancel();
        stopAllMedia();
        if (sounds != null) sounds.play(WiiSounds.BACK);

        zoomAnimator = ValueAnimator.ofFloat(1f, 0f);
        zoomAnimator.setDuration(280);
        zoomAnimator.setInterpolator(new DecelerateInterpolator(1.4f));
        zoomAnimator.addUpdateListener(anim -> {
            float f = (float) anim.getAnimatedValue();
            zoomProgress = f;
            barSlideProgress = f;
            if (videoTextureView.getVisibility() == View.VISIBLE) {
                videoTextureView.setScaleX(f);
                videoTextureView.setScaleY(f);
                videoTextureView.setAlpha(f);
            }
            invalidate();
        });
        zoomAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                isOpen = false;
                setVisibility(View.GONE);
                if (listener != null) {
                    listener.onChannelClosed();
                }
            }
        });
        zoomAnimator.start();
    }

    /**
     * Closes the overlay immediately without animations, e.g. when HOME is pressed.
     */
    public void closeImmediately() {
        if (zoomAnimator != null) zoomAnimator.cancel();
        if (entranceAnimator != null) entranceAnimator.cancel();
        if (handAnimator != null) handAnimator.cancel();
        stopAllMedia();
        isOpen = false;
        setVisibility(View.GONE);
        if (listener != null) {
            listener.onChannelClosed();
        }
    }

    private void refreshMedia() {
        if (currentChannel == null) return;

        // Custom video (audio is played directly by the video player)
        String customVideo = WiiChannelCustomization.getCustomVideo(getContext(), currentChannel.key);
        if (customVideo != null) {
            videoTextureView.setVisibility(View.VISIBLE);
            probeAndAdjustVideoSize(customVideo);
            playChannelVideo(customVideo);
        } else {
            videoTextureView.setVisibility(View.GONE);
            stopChannelVideo();
            currentVideoWidth = 0;
            currentVideoHeight = 0;
            videoTargetW = 0;
            videoTargetH = 0;
        }
    }

    private void probeAndAdjustVideoSize(String path) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(path);
            String sw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String sh = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (sw != null && sh != null) {
                int vw = Integer.parseInt(sw);
                int vh = Integer.parseInt(sh);
                if ("90".equals(rot) || "270".equals(rot)) {
                    int tmp = vw; vw = vh; vh = tmp;
                }
                if (vw > 0 && vh > 0) {
                    adjustVideoWindowSize(vw, vh);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try { mmr.release(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Adjusts the video window size to occupy 100% of the available stage space
     * without empty margins or black bars, preserving proper proportions (scale-to-fill/cover).
     */
    private void adjustVideoWindowSize(int vw, int vh) {
        currentVideoWidth = vw;
        currentVideoHeight = vh;

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            w = (int) (REF_W * scale);
            h = (int) (REF_H * scale);
        }
        if (vw <= 0 || vh <= 0) {
            vw = 16;
            vh = 9;
        }

        float stageBottom = (REF_H - 290f) * scale;
        int stageW = w;
        int stageH = Math.round(stageBottom);

        float scaleX = (float) stageW / vw;
        float scaleY = (float) stageH / vh;
        float fillScale = Math.max(scaleX, scaleY);

        videoTargetW = Math.round(vw * fillScale);
        videoTargetH = Math.round(vh * fillScale);
        videoTargetL = Math.round((stageW - videoTargetW) / 2f);
        videoTargetT = Math.round((stageH - videoTargetH) / 2f);

        post(() -> {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) videoTextureView.getLayoutParams();
            if (lp == null) {
                lp = new FrameLayout.LayoutParams(videoTargetW, videoTargetH);
            } else {
                lp.width = videoTargetW;
                lp.height = videoTargetH;
            }
            lp.leftMargin = videoTargetL;
            lp.topMargin = videoTargetT;
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            videoTextureView.setLayoutParams(lp);
            videoTextureView.layout(videoTargetL, videoTargetT, videoTargetL + videoTargetW, videoTargetT + videoTargetH);
        });

        invalidate();
    }

    private void animateHandTo(float toX, float toY) {
        if (handAnimator != null) handAnimator.cancel();
        final float fromX = handX;
        final float fromY = handY;
        handAnimator = ValueAnimator.ofFloat(0f, 1f);
        handAnimator.setDuration(160);
        handAnimator.setInterpolator(new DecelerateInterpolator(1.4f));
        handAnimator.addUpdateListener(anim -> {
            float f = (float) anim.getAnimatedValue();
            handX = fromX + (toX - fromX) * f;
            handY = fromY + (toY - fromY) * f;
            invalidate();
        });
        handAnimator.start();
    }

    private void updateHandTarget() {
        float w = getWidth() > 0 ? getWidth() : REF_W * scale;
        float h = getHeight() > 0 ? getHeight() : REF_H * scale;

        float btnY = (REF_H - 145f) * scale;
        float tx, ty;

        if (currentState == STATE_CHANNEL) {
            if (channelFocus == 0) {
                tx = 220f * scale;
                ty = btnY - 72f * scale;
            } else if (channelFocus == 1) {
                tx = 640f * scale;
                ty = btnY - 72f * scale;
            } else {
                tx = 1400f * scale;
                ty = btnY - 72f * scale;
            }
        } else if (currentState == STATE_CONFIG) {
            if (configFocus == 0) {
                tx = w / 2f;
                ty = 320f * scale - 60f * scale;
            } else if (configFocus == 1) {
                tx = w / 2f;
                ty = 480f * scale - 60f * scale;
            } else {
                tx = w / 2f;
                ty = btnY - 72f * scale;
            }
        } else if (currentState == STATE_VIDEO_PICKER) {
            if (videoBottomFocus == 1) {
                tx = 640f * scale;
                ty = btnY - 72f * scale;
            } else if (videoBottomFocus == 2) {
                tx = 1400f * scale;
                ty = btnY - 72f * scale;
            } else {
                tx = w / 2f;
                ty = 420f * scale;
            }
        } else {
            tx = handX;
            ty = handY;
        }

        animateHandTo(tx, ty);
    }

    // --- BULLETPROOF REMOTE CONTROL DPAD & WIIMOTE NAVIGATION ---

    public void pointerMove(float dx, float dy) {
        if (!isOpen) return;
        float w = getWidth() > 0 ? getWidth() : REF_W * scale;
        float h = getHeight() > 0 ? getHeight() : REF_H * scale;
        handX = Math.max(0f, Math.min(w, handX + dx * scale));
        handY = Math.max(0f, Math.min(h, handY + dy * scale));
        checkPointerHover(handX, handY);
        invalidate();
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (!isOpen) return false;
        if (event.getAction() == MotionEvent.ACTION_HOVER_MOVE) {
            handX = event.getX();
            handY = event.getY();
            checkPointerHover(handX, handY);
            invalidate();
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isOpen) return false;
        handX = event.getX();
        handY = event.getY();
        int action = event.getAction();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            checkPointerHover(handX, handY);
            invalidate();
            return true;
        } else if (action == MotionEvent.ACTION_UP) {
            checkPointerClick(handX, handY);
            invalidate();
            return true;
        }
        return true;
    }

    private void checkPointerHover(float px, float py) {
        float btnY = (REF_H - 145f) * scale;
        float btnW = 560f * scale;
        float btnH = 144f * scale;
        float gearR = 62f * scale;
        float gearX = 220f * scale;
        float backX = 640f * scale;
        float startX = 1400f * scale;

        if (currentState == STATE_CHANNEL) {
            if (Math.hypot(px - gearX, py - btnY) <= gearR * 1.25f) {
                if (channelFocus != 0) {
                    channelFocus = 0;
                    if (sounds != null) sounds.play(WiiSounds.MOVE);
                }
            } else if (Math.abs(px - backX) <= btnW / 2f && Math.abs(py - btnY) <= btnH / 2f) {
                if (channelFocus != 1) {
                    channelFocus = 1;
                    if (sounds != null) sounds.play(WiiSounds.MOVE);
                }
            } else if (Math.abs(px - startX) <= btnW / 2f && Math.abs(py - btnY) <= btnH / 2f) {
                if (channelFocus != 2) {
                    channelFocus = 2;
                    if (sounds != null) sounds.play(WiiSounds.MOVE);
                }
            }
        }
    }

    private void checkPointerClick(float px, float py) {
        checkPointerHover(px, py);
        onSelectPressed();
    }

    public boolean handleKeyDown(int keyCode, KeyEvent event) {
        if (!isOpen) return false;

        // Never consume HOME or APP_SWITCH; let system/activity return to all apps!
        if (keyCode == KeyEvent.KEYCODE_HOME || keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
            closeImmediately();
            return false;
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                onDpadLeft();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                onDpadRight();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                onDpadUp();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                onDpadDown();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_X:
                onSelectPressed();
                return true;
            case KeyEvent.KEYCODE_BACK:
                handleBack();
                return true;
        }
        return true;
    }

    public boolean handleKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_HOME || keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
            return true;
        }
        return isOpen;
    }

    private void onDpadLeft() {
        if (currentState == STATE_CHANNEL) {
            if (channelFocus > 0) {
                channelFocus--;
                if (sounds != null) sounds.play(WiiSounds.MOVE);
                updateHandTarget();
            }
        } else if (currentState == STATE_VIDEO_PICKER) {
            if (videoBottomFocus > 0) {
                if (videoBottomFocus == 2) {
                    videoBottomFocus = 1;
                    if (sounds != null) sounds.play(WiiSounds.MOVE);
                    updateHandTarget();
                }
            } else {
                if (videoListIndex > 0) {
                    videoListIndex--;
                    if (sounds != null) sounds.play(WiiSounds.MOVE);
                    onVideoIndexChanged();
                    updateHandTarget();
                }
            }
        }
    }

    private void onDpadRight() {
        if (currentState == STATE_CHANNEL) {
            if (channelFocus < 2) {
                channelFocus++;
                if (sounds != null) sounds.play(WiiSounds.MOVE);
                updateHandTarget();
            }
        } else if (currentState == STATE_VIDEO_PICKER) {
            if (videoBottomFocus > 0) {
                if (videoBottomFocus == 1) {
                    videoBottomFocus = 2;
                    if (sounds != null) sounds.play(WiiSounds.MOVE);
                    updateHandTarget();
                }
            } else {
                int count = 1 + videoFiles.size();
                if (videoListIndex < count - 1) {
                    videoListIndex++;
                    if (sounds != null) sounds.play(WiiSounds.MOVE);
                    onVideoIndexChanged();
                    updateHandTarget();
                }
            }
        }
    }

    private void onDpadUp() {
        if (currentState == STATE_CHANNEL) {
            if (channelFocus == 0) channelFocus = 1;
            else if (channelFocus == 2) channelFocus = 1;
            if (sounds != null) sounds.play(WiiSounds.MOVE);
            updateHandTarget();
        } else if (currentState == STATE_CONFIG) {
            if (configFocus > 0) {
                configFocus--;
                if (sounds != null) sounds.play(WiiSounds.MOVE);
                updateHandTarget();
            }
        } else if (currentState == STATE_VIDEO_PICKER) {
            if (videoBottomFocus > 0) {
                videoBottomFocus = 0;
                if (sounds != null) sounds.play(WiiSounds.MOVE);
                onVideoIndexChanged();
                updateHandTarget();
            }
        }
    }

    private void onDpadDown() {
        if (currentState == STATE_CHANNEL) {
            if (channelFocus == 1) channelFocus = 2;
            else if (channelFocus == 0) channelFocus = 1;
            if (sounds != null) sounds.play(WiiSounds.MOVE);
            updateHandTarget();
        } else if (currentState == STATE_CONFIG) {
            if (configFocus < 2) {
                configFocus++;
                if (sounds != null) sounds.play(WiiSounds.MOVE);
                updateHandTarget();
            }
        } else if (currentState == STATE_VIDEO_PICKER) {
            if (videoBottomFocus == 0) {
                videoBottomFocus = 2; // Default to Aplicar
                stopCarouselVideo();
                if (sounds != null) sounds.play(WiiSounds.MOVE);
                updateHandTarget();
            }
        }
    }

    private void onSelectPressed() {
        if (currentState == STATE_CHANNEL) {
            if (channelFocus == 0) {
                // Gear -> Open Settings
                if (sounds != null) sounds.play(WiiSounds.SELECT);
                switchToState(STATE_CONFIG);
            } else if (channelFocus == 1) {
                // Wii Menu / Volver -> Zoom Out
                startZoomOut();
            } else if (channelFocus == 2) {
                // Start / Iniciar -> Launch App
                if (listener != null && currentChannel != null) {
                    if (sounds != null) sounds.play(WiiSounds.START);
                    stopAllMedia();
                    listener.onStartApp(currentChannel);
                }
            }
        } else if (currentState == STATE_CONFIG) {
            if (configFocus == 0) {
                if (sounds != null) sounds.play(WiiSounds.SELECT);
                switchToState(STATE_VIDEO_PICKER);
            } else if (configFocus == 1) {
                // Open native Android App Info screen
                openAppInfo();
            } else {
                if (sounds != null) sounds.play(WiiSounds.BACK);
                switchToState(STATE_CHANNEL);
            }
        } else if (currentState == STATE_VIDEO_PICKER) {
            if (videoBottomFocus == 1) {
                stopCarouselVideo();
                if (sounds != null) sounds.play(WiiSounds.BACK);
                switchToState(STATE_CONFIG);
            } else if (videoBottomFocus == 2) {
                applyVideoSelection();
            } else {
                if (sounds != null) sounds.play(WiiSounds.SELECT);
                if (videoListIndex == 0) {
                    selectedVideoPath = null;
                } else {
                    selectedVideoPath = videoFiles.get(videoListIndex - 1).getAbsolutePath();
                }
                videoBottomFocus = 2;
                updateHandTarget();
            }
        }
    }

    /**
     * Opens the native Android Application Info screen (force stop, uninstall, permissions).
     */
    private void openAppInfo() {
        if (currentChannel == null) return;
        if (sounds != null) sounds.play(WiiSounds.SELECT);
        String pkg = currentChannel.key;

        // If it's a standard Android package (not an internal hdmi port)
        if (pkg != null && !pkg.startsWith("hdmi.")) {
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.fromParts("package", pkg, null));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
                closeImmediately();
                return;
            } catch (Throwable t) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    getContext().startActivity(intent);
                    closeImmediately();
                    return;
                } catch (Throwable ignored) {}
            }
        }

        // Fallback to system settings
        try {
            Intent intent = new Intent(Settings.ACTION_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            closeImmediately();
        } catch (Throwable ignored) {}
    }

    public void handleBack() {
        if (currentState == STATE_VIDEO_PICKER) {
            stopCarouselVideo();
            if (sounds != null) sounds.play(WiiSounds.BACK);
            switchToState(STATE_CONFIG);
        } else if (currentState == STATE_CONFIG) {
            if (sounds != null) sounds.play(WiiSounds.BACK);
            switchToState(STATE_CHANNEL);
        } else if (currentState == STATE_CHANNEL) {
            startZoomOut();
        }
    }

    private void switchToState(int state) {
        this.currentState = state;
        if (state == STATE_CHANNEL) {
            channelFocus = 2;
            refreshMedia();
            playEntranceAnimation();
        } else {
            videoTextureView.setVisibility(View.GONE);
            stopChannelVideo();
            if (state == STATE_CONFIG) {
                configFocus = 0;
            } else if (state == STATE_VIDEO_PICKER) {
                videoFiles = WiiChannelCustomization.listVideoFiles();
                selectedVideoPath = WiiChannelCustomization.getCustomVideo(getContext(), currentChannel != null ? currentChannel.key : "");
                videoListIndex = 0;
                videoScrollOffset = 0;
                videoBottomFocus = 0;
                if (selectedVideoPath != null) {
                    for (int i = 0; i < videoFiles.size(); i++) {
                        if (videoFiles.get(i).getAbsolutePath().equals(selectedVideoPath)) {
                            videoListIndex = i + 1;
                            break;
                        }
                    }
                }
                onVideoIndexChanged();
            }
        }
        updateHandTarget();
        invalidate();
    }

    private void onVideoIndexChanged() {
        stopCarouselVideo();
        if (videoListIndex == 0) return;
        if (videoListIndex - 1 < videoFiles.size()) {
            File f = videoFiles.get(videoListIndex - 1);
            playCarouselVideo(f.getAbsolutePath());
        }
    }

    // --- MEDIA PLAYBACK ---

    private void playChannelVideo(String path) {
        stopChannelVideo();
        try {
            channelVideoPlayer = new MediaPlayer();
            channelVideoPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
            channelVideoPlayer.setDataSource(path);
            channelVideoPlayer.setLooping(true);
            channelVideoPlayer.setOnVideoSizeChangedListener((mp, vw, vh) -> {
                if (vw > 0 && vh > 0) {
                    adjustVideoWindowSize(vw, vh);
                }
            });
            channelVideoPlayer.setOnPreparedListener(mp -> {
                if (channelSurface != null && channelVideoPlayer != null) {
                    channelVideoPlayer.setSurface(channelSurface);
                    channelVideoPlayer.start();
                }
                if (mp.getVideoWidth() > 0 && mp.getVideoHeight() > 0) {
                    adjustVideoWindowSize(mp.getVideoWidth(), mp.getVideoHeight());
                }
            });
            channelVideoPlayer.setOnErrorListener((mp, what, extra) -> true);
            channelVideoPlayer.prepareAsync();
        } catch (Throwable ignored) {
            stopChannelVideo();
        }
    }

    private void stopChannelVideo() {
        if (channelVideoPlayer != null) {
            try {
                if (channelVideoPlayer.isPlaying()) channelVideoPlayer.stop();
                channelVideoPlayer.release();
            } catch (Throwable ignored) {}
            channelVideoPlayer = null;
        }
    }

    private void playCarouselVideo(String path) {
        stopCarouselVideo();
        try {
            carouselVideoPlayer = new MediaPlayer();
            carouselVideoPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
            carouselVideoPlayer.setDataSource(path);
            carouselVideoPlayer.setLooping(true);
            carouselVideoPlayer.setVolume(0f, 0f);
            carouselVideoPlayer.setOnPreparedListener(MediaPlayer::start);
            carouselVideoPlayer.setOnErrorListener((mp, what, extra) -> true);
            carouselVideoPlayer.prepareAsync();
        } catch (Throwable ignored) {
            stopCarouselVideo();
        }
    }

    private void stopCarouselVideo() {
        if (carouselVideoPlayer != null) {
            try {
                if (carouselVideoPlayer.isPlaying()) carouselVideoPlayer.stop();
                carouselVideoPlayer.release();
            } catch (Throwable ignored) {}
            carouselVideoPlayer = null;
        }
    }

    public void stopAllMedia() {
        stopChannelVideo();
        stopCarouselVideo();
    }

    private void applyVideoSelection() {
        stopCarouselVideo();
        if (sounds != null) sounds.play(WiiSounds.SELECT);
        if (currentChannel != null) {
            WiiChannelCustomization.setCustomVideo(getContext(), currentChannel.key, selectedVideoPath);
        }
        switchToState(STATE_CHANNEL);
        refreshMedia();
    }

    // --- DRAWING: STAGE BACKGROUND & APP ARTWORK IN onDraw ---

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // 1. Stage Background: adapts to Dark or Light theme
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(getStageBgColor());
        canvas.drawRect(0, 0, w, h, paint);

        if (currentState == STATE_CHANNEL) {
            // Draw static complete app artwork (when custom video is not active)
            if (videoTextureView.getVisibility() != View.VISIBLE) {
                drawChannelCenterArtwork(canvas, w, h);
                drawChannelStageDecorations(canvas, w, h);
            }
        } else if (currentState == STATE_CONFIG) {
            drawConfigStage(canvas, w, h);
        } else if (currentState == STATE_VIDEO_PICKER) {
            drawVideoPickerStage(canvas, w, h);
        }
    }

    // --- DRAWING: CONTROLS, BOTTOM DRAWER & HAND IN dispatchDraw (ON TOP OF TextureView) ---

    @Override
    protected void dispatchDraw(Canvas canvas) {
        // Draw children (videoTextureView) first
        super.dispatchDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // If custom video is playing, overlay arrows and Nintendo badge on top of video
        if (currentState == STATE_CHANNEL && videoTextureView.getVisibility() == View.VISIBLE) {
            drawChannelStageDecorations(canvas, w, h);
        }

        // Draw curved console drawer & buttons on top of everything
        drawBottomConsoleBar(canvas, w, h);

        if (currentState == STATE_CHANNEL) {
            drawChannelButtons(canvas, w, h);
        } else if (currentState == STATE_CONFIG) {
            drawConfigBottomBar(canvas, w, h);
        } else {
            drawPickerBottomBar(canvas, w, h);
        }

        // Draw the iconic Wii Hand cursor hovering over the selection
        if (isOpen && zoomProgress > 0.4f) {
            hand.draw(canvas, handX, handY);
        }
    }

    /**
     * Draws the complete default app artwork (banner/icon) occupying the available stage space.
     * Completely static after single entrance pop-in animation (zero flickering, zero shifts).
     */
    private void drawChannelCenterArtwork(Canvas canvas, int w, int h) {
        if (cachedChannelDrawable == null) return;

        float stageBottom = (REF_H - 290f) * scale;
        float cx = w / 2f;
        float cy = stageBottom / 2f;

        int dw = cachedChannelDrawable.getIntrinsicWidth();
        int dh = cachedChannelDrawable.getIntrinsicHeight();
        if (dw <= 0) dw = cachedIsBanner ? 16 : 1;
        if (dh <= 0) dh = cachedIsBanner ? 9 : 1;
        float ar = (float) dw / dh;

        // Occupy the full available stage space cleanly without cropping (FIT_CENTER)
        float maxW = w * 0.94f;
        float maxH = stageBottom * 0.90f;

        float targetW = maxW;
        float targetH = targetW / ar;
        if (targetH > maxH) {
            targetH = maxH;
            targetW = targetH * ar;
        }

        float left = cx - targetW / 2f;
        float top = cy - targetH / 2f;

        int save = canvas.save();
        canvas.scale(entranceScale, entranceScale, cx, cy);

        // Soft drop shadow behind artwork for depth on both dark and light stages
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(darkMode ? 0x40000000 : 0x20000000);
        btnRect.set(left, top + 6f * scale, left + targetW, top + targetH + 6f * scale);
        canvas.drawRoundRect(btnRect, 24f * scale, 24f * scale, paint);

        cachedChannelDrawable.setBounds(Math.round(left), Math.round(top),
                Math.round(left + targetW), Math.round(top + targetH));
        cachedChannelDrawable.setAlpha(Math.round(255 * entranceAlpha));
        cachedChannelDrawable.draw(canvas);

        // If it's a square app icon (not a full Leanback banner), draw the title below it
        if (!cachedIsBanner && currentChannel != null && currentChannel.label != null) {
            textPaint.setTextSize(34f * scale);
            textPaint.setColor(getPrimaryTextColor());
            textPaint.setAlpha(Math.round(255 * entranceAlpha));
            if (darkMode) {
                textPaint.setShadowLayer(6f * scale, 0, 3f * scale, 0x80000000);
            } else {
                textPaint.setShadowLayer(4f * scale, 0, 2f * scale, 0x60FFFFFF);
            }
            canvas.drawText(currentChannel.label, cx, top + targetH + 44f * scale, textPaint);
            textPaint.clearShadowLayer();
        }

        canvas.restoreToCount(save);
    }

    /**
     * Draws side cyan arrows (< and >) and "Licensed by Nintendo" badge matching Wii channel screen.
     */
    private void drawChannelStageDecorations(Canvas canvas, int w, int h) {
        float alpha = zoomProgress;
        int a255 = Math.round(255 * alpha);

        // 1. Top Right "Licensed by Nintendo"
        textPaint.setTextSize(14f * scale);
        textPaint.setColor(getSecondaryTextColor());
        textPaint.setAlpha(Math.round(200 * alpha));
        canvas.drawText("Licensed by", (REF_W - 170f) * scale, 85f * scale, textPaint);

        // Oval Nintendo pill badge
        float ncx = (REF_W - 170f) * scale;
        float ncy = 118f * scale;
        float nw = 120f * scale;
        float nh = 36f * scale;
        btnRect.set(ncx - nw / 2f, ncy - nh / 2f, ncx + nw / 2f, ncy + nh / 2f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.2f * scale);
        paint.setColor(darkMode ? 0xFF7A8696 : 0xFF8894A4);
        paint.setAlpha(Math.round(220 * alpha));
        canvas.drawRoundRect(btnRect, nh / 2f, nh / 2f, paint);

        textPaint.setTextSize(18f * scale);
        textPaint.setColor(darkMode ? 0xFFE2E8F0 : 0xFF2C3440);
        textPaint.setAlpha(a255);
        float ty = ncy - ((textPaint.descent() + textPaint.ascent()) / 2f);
        canvas.drawText("Nintendo", ncx, ty, textPaint);

        // 2. Cyan Side Arrows (< and >)
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(getArrowColor());
        paint.setAlpha(Math.round(230 * alpha));

        // Left Arrow (<)
        float arrowY = (REF_H * 0.44f) * scale;
        arrowPath.reset();
        arrowPath.moveTo(68f * scale, arrowY - 26f * scale);
        arrowPath.lineTo(44f * scale, arrowY);
        arrowPath.lineTo(68f * scale, arrowY + 26f * scale);
        arrowPath.close();
        canvas.drawPath(arrowPath, paint);

        // Right Arrow (>)
        arrowPath.reset();
        arrowPath.moveTo((REF_W - 68f) * scale, arrowY - 26f * scale);
        arrowPath.lineTo((REF_W - 44f) * scale, arrowY);
        arrowPath.lineTo((REF_W - 68f) * scale, arrowY + 26f * scale);
        arrowPath.close();
        canvas.drawPath(arrowPath, paint);
    }

    /**
     * Draws authentic Wii bottom console drawer with curved lip and horizontal scanline grooves.
     */
    private void drawBottomConsoleBar(Canvas canvas, int w, int h) {
        float slideY = (1f - barSlideProgress) * 280f * scale;
        float topEdgeY = (REF_H - 290f) * scale + slideY;

        barPath.reset();
        barPath.moveTo(0, topEdgeY);
        barPath.cubicTo(w * 0.28f, topEdgeY + 28f * scale, w * 0.72f, topEdgeY + 28f * scale, w, topEdgeY);
        barPath.lineTo(w, h);
        barPath.lineTo(0, h);
        barPath.close();

        // Fill drawer background
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(getDrawerBgColor());
        canvas.drawPath(barPath, paint);

        // Horizontal scanlines / ridges across the bar
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.2f * scale);
        paint.setColor(getDrawerLineColor());
        float lineStep = 6f * scale;
        for (float y = topEdgeY + lineStep; y < h; y += lineStep) {
            canvas.drawLine(0, y, w, y, paint);
        }

        // Top border lip highlight & shadow
        paint.setStrokeWidth(2.5f * scale);
        paint.setColor(darkMode ? 0xFF2E3746 : 0xFFFFFFFF);
        canvas.drawPath(barPath, paint);

        paint.setStrokeWidth(1.5f * scale);
        paint.setColor(getDrawerBorderColor());
        canvas.drawPath(barPath, paint);
    }

    /**
     * Draws EXTRA-LARGE authentic 3D acrylic bubble buttons matching original Wii console proportions.
     */
    private void drawChannelButtons(Canvas canvas, int w, int h) {
        float slideY = (1f - barSlideProgress) * 280f * scale;
        float btnY = (REF_H - 145f) * scale + slideY;

        float btnW = 560f * scale;
        float btnH = 144f * scale;

        // Button 0: Gear Button (Circular, diameter 124)
        float gearX = 220f * scale;
        float gearR = 62f * scale;
        drawWiiCircleButton(canvas, gearX, btnY, gearR, channelFocus == 0);

        // Button 1: "Wii Menu" / "Volver" (Pill, on the left)
        float backX = 640f * scale;
        drawWiiBubblePill(canvas, backX, btnY, btnW, btnH, "Wii Menu", channelFocus == 1);

        // Button 2: "Start" / "Iniciar" (Pill, on the right)
        float startX = 1400f * scale;
        drawWiiBubblePill(canvas, startX, btnY, btnW, btnH, "Start", channelFocus == 2);
    }

    private void drawWiiBubblePill(Canvas canvas, float cx, float cy, float bw, float bh, String label, boolean focused) {
        float r = bh / 2f;
        btnRect.set(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f);

        // 1. Blue Glowing Halo when focused
        if (focused) {
            for (int i = 4; i >= 1; i--) {
                glowPaint.setStrokeWidth((5f + i * 5f) * scale);
                glowPaint.setAlpha(190 / i);
                canvas.drawRoundRect(btnRect, r, r, glowPaint);
            }
        }

        // 2. Drop Shadow
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x35000000);
        canvas.drawRoundRect(btnRect.left, btnRect.top + 5f * scale, btnRect.right, btnRect.bottom + 5f * scale, r, r, paint);

        // 3. Pill Background Gradient
        paint.setShader(new LinearGradient(0, btnRect.top, 0, btnRect.bottom,
                getButtonBgTop(), getButtonBgBottom(), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(btnRect, r, r, paint);
        paint.setShader(null);

        // 4. Top Specular Gloss Reflection (Signature Wii acrylic glass look)
        float specH = bh * 0.46f;
        specRect.set(btnRect.left + 6f * scale, btnRect.top + 4f * scale,
                btnRect.right - 6f * scale, btnRect.top + specH);
        int specTop = darkMode ? 0x25FFFFFF : 0xD8FFFFFF;
        int specBottom = darkMode ? 0x04FFFFFF : 0x18FFFFFF;
        paint.setShader(new LinearGradient(0, specRect.top, 0, specRect.bottom,
                specTop, specBottom, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(specRect, specH / 2f, specH / 2f, paint);
        paint.setShader(null);

        // 5. Outer Bevel Border
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3.5f * scale);
        paint.setColor(getButtonBorderColor(focused));
        canvas.drawRoundRect(btnRect, r, r, paint);

        // 6. Label Text (Dynamically scaled so long text never clips, highly legible)
        float textSize = 42f * scale;
        if (label.length() > 24) {
            textSize = 28f * scale;
        } else if (label.length() > 16) {
            textSize = 32f * scale;
        }
        textPaint.setTextSize(textSize);
        textPaint.setColor(getButtonTextColor());
        textPaint.setAlpha(255);

        // Soft drop shadow behind button text for maximum readability
        if (darkMode) {
            textPaint.setShadowLayer(4f * scale, 0, 2f * scale, 0x90000000);
        } else {
            textPaint.setShadowLayer(3f * scale, 0, 1.5f * scale, 0x40FFFFFF);
        }

        float ty = cy - ((textPaint.descent() + textPaint.ascent()) / 2f);
        canvas.drawText(label, cx, ty, textPaint);
        textPaint.clearShadowLayer();
    }

    private void drawWiiCircleButton(Canvas canvas, float cx, float cy, float cr, boolean focused) {
        btnRect.set(cx - cr, cy - cr, cx + cr, cy + cr);

        // Glow
        if (focused) {
            for (int i = 4; i >= 1; i--) {
                glowPaint.setStrokeWidth((5f + i * 5f) * scale);
                glowPaint.setAlpha(190 / i);
                canvas.drawCircle(cx, cy, cr, glowPaint);
            }
        }

        // Shadow
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x35000000);
        canvas.drawCircle(cx, cy + 5f * scale, cr, paint);

        // Background
        paint.setShader(new LinearGradient(0, cy - cr, 0, cy + cr,
                getButtonBgTop(), getButtonBgBottom(), Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, cr, paint);
        paint.setShader(null);

        // Gloss
        float specH = cr * 0.88f;
        specRect.set(cx - cr * 0.85f, cy - cr + 4f * scale, cx + cr * 0.85f, cy - cr + specH);
        int specTop = darkMode ? 0x25FFFFFF : 0xD8FFFFFF;
        int specBottom = darkMode ? 0x04FFFFFF : 0x18FFFFFF;
        paint.setShader(new LinearGradient(0, specRect.top, 0, specRect.bottom,
                specTop, specBottom, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(specRect, specH / 2f, specH / 2f, paint);
        paint.setShader(null);

        // Border
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3.5f * scale);
        paint.setColor(getButtonBorderColor(focused));
        canvas.drawCircle(cx, cy, cr, paint);

        // Gear Icon
        int gearColor;
        if (focused) {
            gearColor = darkMode ? 0xFF5AC6F8 : 0xFF0088CC;
        } else {
            gearColor = darkMode ? 0xFFD0D8E4 : 0xFF3A4452;
        }
        drawGearIcon(canvas, cx, cy, cr * 0.52f, gearColor);
    }

    private void drawGearIcon(Canvas canvas, float cx, float cy, float r, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        int teeth = 8;
        for (int i = 0; i < teeth; i++) {
            float angle = (float) (i * 2 * Math.PI / teeth);
            float tx = cx + (float) Math.cos(angle) * r * 1.05f;
            float ty = cy + (float) Math.sin(angle) * r * 1.05f;
            canvas.drawCircle(tx, ty, r * 0.28f, paint);
        }
        canvas.drawCircle(cx, cy, r * 0.82f, paint);

        // Center hole
        paint.setColor(darkMode ? 0xFF242A36 : 0xFFFFFFFF);
        canvas.drawCircle(cx, cy, r * 0.36f, paint);
    }

    // --- CONFIG & VIDEO PICKER SCREENS ---

    private void drawConfigStage(Canvas canvas, int w, int h) {
        textPaint.setTextSize(36f * scale);
        textPaint.setColor(getPrimaryTextColor());
        if (darkMode) {
            textPaint.setShadowLayer(6f * scale, 0, 3f * scale, 0x80000000);
        } else {
            textPaint.setShadowLayer(4f * scale, 0, 2f * scale, 0x60FFFFFF);
        }
        canvas.drawText("Configuración: " + (currentChannel != null ? currentChannel.label : ""), w / 2f, 180f * scale, textPaint);
        textPaint.clearShadowLayer();

        String curVideo = WiiChannelCustomization.getCustomVideo(getContext(), currentChannel != null ? currentChannel.key : "");
        String videoSub;
        if (curVideo != null) {
            videoSub = new File(curVideo).getName();
            if (videoSub.length() > 20) {
                videoSub = videoSub.substring(0, 18) + "…";
            }
        } else {
            videoSub = "Predeterminado";
        }

        drawWiiBubblePill(canvas, w / 2f, 320f * scale, 720f * scale, 120f * scale, "🎬  Personalizar Video (" + videoSub + ")", configFocus == 0);
        drawWiiBubblePill(canvas, w / 2f, 480f * scale, 720f * scale, 120f * scale, "ℹ️  Información de la Aplicación", configFocus == 1);
    }

    private void drawConfigBottomBar(Canvas canvas, int w, int h) {
        float btnY = (REF_H - 145f) * scale;
        drawWiiBubblePill(canvas, w / 2f, btnY, 560f * scale, 144f * scale, "Volver al Canal", configFocus == 2);
    }

    private void drawVideoPickerStage(Canvas canvas, int w, int h) {
        textPaint.setTextSize(34f * scale);
        textPaint.setColor(getPrimaryTextColor());
        if (darkMode) {
            textPaint.setShadowLayer(6f * scale, 0, 3f * scale, 0x80000000);
        } else {
            textPaint.setShadowLayer(4f * scale, 0, 2f * scale, 0x60FFFFFF);
        }
        canvas.drawText("Personalizar Video", w / 2f, 130f * scale, textPaint);
        textPaint.clearShadowLayer();

        textPaint.setTextSize(16f * scale);
        textPaint.setColor(getSecondaryTextColor());
        canvas.drawText(WiiChannelCustomization.PRIMARY_MEDIA_ROOT + "/videos/", w / 2f, 165f * scale, textPaint);

        int total = 1 + videoFiles.size();
        float cardW = 340f * scale;
        float cardH = 192f * scale;
        float cy = 420f * scale;

        for (int i = 0; i < total; i++) {
            float offsetIdx = i - videoListIndex;
            float cx = (w / 2f) + offsetIdx * (cardW + 40f * scale);
            if (cx < -cardW || cx > w + cardW) continue;

            boolean isCurFocus = (videoBottomFocus == 0 && i == videoListIndex);
            btnRect.set(cx - cardW / 2f, cy - cardH / 2f, cx + cardW / 2f, cy + cardH / 2f);

            if (isCurFocus) {
                glowPaint.setStrokeWidth(4f * scale);
                glowPaint.setAlpha(220);
                canvas.drawRoundRect(btnRect, 18f * scale, 18f * scale, glowPaint);
            }

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(darkMode ? 0xFF1E2126 : 0xFFE0E4EB);
            canvas.drawRoundRect(btnRect, 18f * scale, 18f * scale, paint);

            if (i > 0) {
                File f = videoFiles.get(i - 1);
                Bitmap thumb = thumbCache.get(f.getAbsolutePath());
                if (thumb != null) {
                    canvas.drawBitmap(thumb, null, btnRect, null);
                } else {
                    loadThumbAsync(f.getAbsolutePath());
                }
            }

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f * scale);
            paint.setColor(isCurFocus ? BLUE_GLOW : (darkMode ? 0xFF555F6E : 0xFFA0B0C4));
            canvas.drawRoundRect(btnRect, 18f * scale, 18f * scale, paint);

            String label = (i == 0) ? "Predeterminado" : videoFiles.get(i - 1).getName();
            boolean isSelected = (i == 0 && selectedVideoPath == null)
                    || (i > 0 && videoFiles.get(i - 1).getAbsolutePath().equals(selectedVideoPath));

            textPaint.setTextSize(18f * scale);
            int labelColor;
            if (isCurFocus) {
                labelColor = darkMode ? 0xFFFFFFFF : 0xFF0078D4;
            } else {
                labelColor = getSecondaryTextColor();
            }
            textPaint.setColor(labelColor);
            canvas.drawText((isSelected ? "✓ " : "") + label, cx, cy + cardH / 2f + 32f * scale, textPaint);
        }
    }

    private void loadThumbAsync(String path) {
        executor.execute(() -> {
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            Bitmap b = null;
            try {
                r.setDataSource(path);
                b = r.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (b == null) b = r.getFrameAtTime();
            } catch (Throwable ignored) {
            } finally {
                try { r.release(); } catch (Throwable ignored) {}
            }
            if (b != null) {
                Bitmap scaled = Bitmap.createScaledBitmap(b, 340, 192, true);
                thumbCache.put(path, scaled);
                mainHandler.post(this::invalidate);
            }
        });
    }

    private void drawPickerBottomBar(Canvas canvas, int w, int h) {
        float btnY = (REF_H - 145f) * scale;
        float btnW = 520f * scale;
        float btnH = 140f * scale;

        boolean isVolver = (currentState == STATE_VIDEO_PICKER && videoBottomFocus == 1);
        boolean isAplicar = (currentState == STATE_VIDEO_PICKER && videoBottomFocus == 2);

        drawWiiBubblePill(canvas, 640f * scale, btnY, btnW, btnH, "Volver", isVolver);
        drawWiiBubblePill(canvas, 1400f * scale, btnY, btnW, btnH, "Aplicar", isAplicar);
    }
}
