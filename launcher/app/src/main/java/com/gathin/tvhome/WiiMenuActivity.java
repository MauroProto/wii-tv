package com.gathin.tvhome;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvView;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.provider.MediaStore;
import android.database.Cursor;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Home screen in the style of the old console menu; all of the drawing is in WiiMenuView. */
public class WiiMenuActivity extends Activity implements WiiMenuView.Listener {

    private static final String MEDIA_PLAYER = "com.tcl.ui_mediaCenter";
    private static final String FALLBACK_MEDIA_PLAYER = "com.alphainventor.filemanager";
    /** Debug: freeze the menu in the reference capture's state, for comparing screenshots against it. */
    static final String EXTRA_REF_POSE = "ref_pose";
    /** The owner's channel order, in Order's format; the other home screen keeps its own in Order.FILE_NAME. */
    private static final String ORDER_FILE = "wii-order.txt";
    private static final String PREFS = "wii_menu";
    /** Where the hand was when the menu last left the screen, in PREFS: a channel key, or a slot or button. */
    private static final String HAND_CHANNEL = "hand_channel";
    private static final String HAND_SLOT = "hand_slot";
    private static final String HAND_PAGE = "hand_page";
    private static final String DARK_MODE = "dark_mode";

    private WiiChannelDetailOverlay channelOverlay;
    private final RectF channelSlotRect = new RectF();
    private WiiMenuView menu;
    private WiiSounds sounds;
    private WiiMusic music;
    private MenuMusic fileMusic;
    /** The welcome screen plays once per boot and whenever the screen comes back on, as at power-on. */
    private boolean introPending;
    private boolean appsDirty = true;
    private boolean resumed;
    private final Rect zoomFrom = new Rect();
    private String liveTvPkg;
    private MiiStore miiStore;
    private int miiVersion = -1;
    private Typeface miiFace;
    private GamesBanner gamesBanner;
    private WiimoteBanner wiimoteBanner;
    /** A phone as the remote: its buttons press the remote's keys here, and it points the hand. */
    private Wiimote wiimote;
    /** The channel keys in the usual order, before the owner's: putting them back that way forgets the saved one. */
    private List<String> usualOrder = Collections.emptyList();
    /** The hand goes back where it was on the first resume, unless the menu starts as at power-on. */
    private boolean handPending;

    // HDMI Detection and Miniature Live Preview
    private TvInputManager tvInputManager;
    private String activeHdmiId;
    private String activeHdmiLabel;
    private String tunedHdmiId;
    private TvView hdmiTvView;
    private FrameLayout tvViewContainer;
    private boolean videoAvailable;
    private final RectF slot0Rect = new RectF();
    private View handOverlay;
    private final List<WiiMenuView.Channel> baseChannels = new ArrayList<>();
    private HdmiBanner hdmiBanner;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable hdmiStateRunnable = this::doUpdateHdmiState;

    // HDMI Badge drawing
    private String activeHdmiDevice;
    private String cachedBadgeText;
    private final RectF badgeClipRect = new RectF();
    private final Path badgeClipPath = new Path();
    private final RectF badgePillRect = new RectF();
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint badgeTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private final TvInputManager.TvInputCallback tvInputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputStateChanged(String inputId, int state) {
            updateHdmiState();
        }
        @Override
        public void onInputAdded(String inputId) {
            updateHdmiState();
        }
        @Override
        public void onInputRemoved(String inputId) {
            updateHdmiState();
        }
        @Override
        public void onInputUpdated(String inputId) {
            updateHdmiState();
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            music.pause();
            fileMusic.stop();
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) return;
            if (resumed) {
                introPending = false;
                menu.playIntro();
                return;
            }
            introPending = true;
            try {
                startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .setPackage(getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                // Not allowed on this build; the menu still shows the next time Home is pressed.
            }
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            appsDirty = true;
            if (resumed) reloadApps();
        }
    };

    private final BroadcastReceiver testHdmiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String input = intent.getStringExtra("input_id");
            if ("disconnect".equalsIgnoreCase(input) || "none".equalsIgnoreCase(input)) {
                if (activeHdmiId != null && menu != null) {
                    menu.removeHdmiChannel(() -> {
                        activeHdmiId = null;
                        activeHdmiLabel = null;
                        tunedHdmiId = null;
                        videoAvailable = false;
                        if (hdmiTvView != null) {
                            try { hdmiTvView.reset(); } catch (Throwable ignored) {}
                        }
                        if (tvViewContainer != null) tvViewContainer.setTranslationX(-4000f);
                        updateHdmiBadgeText();
                        menu.invalidate();
                        if (handOverlay != null) handOverlay.postInvalidate();
                    });
                }
                return;
            } else {
                activeHdmiId = (input != null && !input.isEmpty()) ? input : "com.tcl.tvinput/.TvPassThroughService/HW15";
                String lbl = intent.getStringExtra("label");
                activeHdmiLabel = (lbl != null && !lbl.isEmpty()) ? lbl : "HDMI 1";
                WiiMenuView.Channel hdmiCh = getOrCreateHdmiChannel(activeHdmiId, activeHdmiLabel);
                if (menu != null) {
                    menu.setHdmiChannel(hdmiCh);
                }
                tuneHdmiPreview();
                updateHdmiBadgeText();
                if (handOverlay != null) handOverlay.postInvalidate();
                if (menu != null) menu.invalidate();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        menu = new WiiMenuView(this, this);
        menu.setIntroText(getString(R.string.wii_intro_title), getString(R.string.wii_intro_body),
                getString(R.string.wii_intro_continue));
        sounds = new WiiSounds(this, getResources().getBoolean(R.bool.wii_sounds));
        music = new WiiMusic(this, getResources().getBoolean(R.bool.wii_music),
                getResources().getInteger(R.integer.wii_music_volume));
        music.setTrack(R.raw.wii_menu_music);
        fileMusic = new MenuMusic(this, getResources().getBoolean(R.bool.wii_music),
                getResources().getInteger(R.integer.wii_music_volume), sounds);
        menu.setSounds(sounds);
        menu.setDarkMode(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(DARK_MODE, false));
        menu.setSettingsLabel(getString(R.string.wii_settings_label));
        menu.setMusicMuted(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("music_muted", false));

        // Root container with menu and miniature TvView
        FrameLayout root = new FrameLayout(this);
        root.addView(menu, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Keep tvViewContainer VISIBLE so SurfaceView always creates and holds a valid Surface!
        // Hide it when needed by moving it off-screen via translationX rather than View.GONE/INVISIBLE.
        tvViewContainer = new FrameLayout(this);
        tvViewContainer.setVisibility(View.VISIBLE);
        tvViewContainer.setTranslationX(-4000f);
        tvViewContainer.setFocusable(false);
        tvViewContainer.setClickable(false);

        try {
            hdmiTvView = new TvView(this);
            hdmiTvView.setStreamVolume(0f); // Mute preview so it does not overlap Wii music
            hdmiTvView.setFocusable(false);
            hdmiTvView.setClickable(false);
            hdmiTvView.setCallback(new TvView.TvInputCallback() {
                @Override
                public void onVideoAvailable(String inputId) {
                    videoAvailable = true;
                    if (hdmiTvView != null) {
                        try {
                            hdmiTvView.setStreamVolume(0f);
                        } catch (Throwable ignored) {}
                    }
                    menu.invalidate();
                }
                @Override
                public void onVideoUnavailable(String inputId, int reason) {
                    videoAvailable = false;
                    menu.invalidate();
                }
            });
            tvViewContainer.addView(hdmiTvView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // Give an initial valid non-zero size so the SurfaceView creates its Surface immediately
            FrameLayout.LayoutParams initLp = new FrameLayout.LayoutParams(372, 196);
            initLp.leftMargin = 167;
            initLp.topMargin = 98;
            root.addView(tvViewContainer, initLp);
        } catch (Throwable t) {
            hdmiTvView = null;
        }

        badgeTextPaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        badgeTextPaint.setColor(Color.WHITE);

        channelOverlay = new WiiChannelDetailOverlay(this, new WiiChannelDetailOverlay.Listener() {
            @Override
            public void onStartApp(WiiMenuView.Channel channel) {
                if (channel.key.startsWith("hdmi.") && hdmiTvView != null) {
                    try {
                        hdmiTvView.setStreamVolume(1.0f);
                    } catch (Throwable ignored) {}
                }
                if (channelOverlay != null) {
                    channelOverlay.setVisibility(View.GONE);
                }
                open(channel.intent, true);
            }

            @Override
            public void onChannelClosed() {
                playMenuMusic(false);
                if (menu != null) {
                    menu.refreshChannelArt();
                    menu.requestFocus();
                    menu.invalidate();
                }
                if (handOverlay != null) {
                    handOverlay.invalidate();
                }
            }
        }, sounds);
        channelOverlay.setDarkMode(menu.isDarkMode());
        root.addView(channelOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Overlay that draws the HDMI device badge and the Wii cursor hand ON TOP of the HDMI miniature video
        handOverlay = new View(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                if (channelOverlay != null && channelOverlay.isOpen()) return;
                drawHdmiBadge(canvas);
                if (menu != null) {
                    menu.drawHand(canvas);
                }
            }
        };
        handOverlay.setFocusable(false);
        handOverlay.setClickable(false);
        root.addView(handOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        menu.setExternalHand(true);
        menu.setOnHandInvalidate(handOverlay::invalidate);

        // Keep tvViewContainer synchronized with slot 0 in the Wii grid
        menu.setFrameCallback((scale, pagePos, introShowing, menuOpen) -> {
            if (handOverlay != null) {
                handOverlay.invalidate();
            }
            if (tvViewContainer == null || hdmiTvView == null) return;

            menu.getSlotRect(0, slot0Rect);
            int inset = Math.max(2, Math.round(4f * scale));
            int l = Math.round(slot0Rect.left) + inset;
            int t = Math.round(slot0Rect.top) + inset;
            int w = Math.max(16, Math.round(slot0Rect.width()) - 2 * inset);
            int h = Math.max(16, Math.round(slot0Rect.height()) - 2 * inset);

            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tvViewContainer.getLayoutParams();
            if (lp.leftMargin != l || lp.topMargin != t || lp.width != w || lp.height != h) {
                lp.leftMargin = l;
                lp.topMargin = t;
                lp.width = w;
                lp.height = h;
                tvViewContainer.setLayoutParams(lp);
                final float r = Math.max(4f, 21f * scale);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    tvViewContainer.setOutlineProvider(new ViewOutlineProvider() {
                        @Override
                        public void getOutline(View view, Outline outline) {
                            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
                        }
                    });
                    tvViewContainer.setClipToOutline(true);
                }
            }

            tvViewContainer.setPivotX(w / 2f);
            tvViewContainer.setPivotY(h / 2f);
            if (menu.isHdmiAppearing() || menu.isHdmiDisappearing()) {
                float sc = menu.getHdmiAppearScale();
                float al = menu.getHdmiAppearAlpha();
                tvViewContainer.setScaleX(sc);
                tvViewContainer.setScaleY(sc);
                tvViewContainer.setAlpha(al);
            } else {
                tvViewContainer.setScaleX(1.0f);
                tvViewContainer.setScaleY(1.0f);
                tvViewContainer.setAlpha(1.0f);
            }

            boolean showOnScreen = activeHdmiId != null && pagePos <= 0.05f && !introShowing && !menuOpen && (channelOverlay == null || !channelOverlay.isOpen());
            float targetTx = showOnScreen ? 0f : -4000f;
            if (tvViewContainer.getTranslationX() != targetTx) {
                tvViewContainer.setTranslationX(targetTx);
            }
        });

        setContentView(root);
        menu.requestFocus();

        introPending = firstStartThisBoot() || getIntent().getBooleanExtra("show_intro", false);
        if (getIntent().getBooleanExtra(EXTRA_REF_POSE, false)) {
            introPending = false;
            menu.setRefPose(true);
        }
        liveTvPkg = getString(R.string.live_tv_package);
        miiStore = new MiiStore(this);
        Wiimote.setUp(this);
        wiimote = new Wiimote(this, (dx, dy) -> {
            if (channelOverlay != null && channelOverlay.isOpen()) {
                channelOverlay.pointerMove(dx, dy);
            } else {
                menu.pointerMove(dx, dy);
            }
        });
        handPending = !introPending;

        tvInputManager = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tvInputManager != null) {
            tvInputManager.registerCallback(tvInputCallback, new Handler(Looper.getMainLooper()));
        }

        IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_SCREEN_OFF);
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addDataScheme("package");
        IntentFilter testFilter = new IntentFilter("com.gathin.tvhome.SIMULATE_HDMI");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, screen, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(packageReceiver, packages, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(testHdmiReceiver, testFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(screenReceiver, screen);
            registerReceiver(packageReceiver, packages);
            registerReceiver(testHdmiReceiver, testFilter);
        }
        setUpHdmiHotplugListener();

        WiiChannelCustomization.getVideosDir();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        android.Manifest.permission.READ_MEDIA_AUDIO,
                        android.Manifest.permission.READ_MEDIA_VIDEO
                }, 101);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, 101);
            }
        }
    }

    private final Map<Integer, Boolean> portHotplugMap = new ConcurrentHashMap<>();
    private Object hdmiHotplugListener;

    private void setUpHdmiHotplugListener() {
        try {
            Object hdmiControl = getSystemService("hdmi_control");
            if (hdmiControl != null) {
                try {
                    Method mPorts = hdmiControl.getClass().getMethod("getPortInfo");
                    List<?> portInfos = (List<?>) mPorts.invoke(hdmiControl);
                    if (portInfos != null) {
                        for (Object pi : portInfos) {
                            try {
                                Method mId = pi.getClass().getMethod("getId");
                                int id = (Integer) mId.invoke(pi);
                                Method mConn = pi.getClass().getMethod("isConnected");
                                boolean conn = (Boolean) mConn.invoke(pi);
                                portHotplugMap.put(id, conn);
                            } catch (Throwable ignored) {}
                        }
                    }
                } catch (Throwable ignored) {}

                Class<?> listenerClass = Class.forName("android.hardware.hdmi.HdmiControlManager$HotplugEventListener");
                hdmiHotplugListener = Proxy.newProxyInstance(
                        getClassLoader(),
                        new Class<?>[]{listenerClass},
                        (proxy, method, args) -> {
                            if ("onReceived".equals(method.getName()) && args != null && args.length > 0) {
                                Object event = args[0];
                                try {
                                    Method mPort = event.getClass().getMethod("getPort");
                                    Method mConn = event.getClass().getMethod("isConnected");
                                    int port = (Integer) mPort.invoke(event);
                                    boolean connected = (Boolean) mConn.invoke(event);
                                    portHotplugMap.put(port, connected);
                                } catch (Throwable ignored) {}
                                runOnUiThread(WiiMenuActivity.this::updateHdmiState);
                            }
                            return null;
                        }
                );
                Method addListener = hdmiControl.getClass().getMethod("addHotplugEventListener", listenerClass);
                addListener.invoke(hdmiControl, hdmiHotplugListener);
            }
        } catch (Throwable ignored) {}
    }

    private void tearDownHdmiHotplugListener() {
        try {
            if (hdmiHotplugListener != null) {
                Object hdmiControl = getSystemService("hdmi_control");
                if (hdmiControl != null) {
                    Class<?> listenerClass = Class.forName("android.hardware.hdmi.HdmiControlManager$HotplugEventListener");
                    Method removeListener = hdmiControl.getClass().getMethod("removeHotplugEventListener", listenerClass);
                    removeListener.invoke(hdmiControl, hdmiHotplugListener);
                }
                hdmiHotplugListener = null;
            }
        } catch (Throwable ignored) {}
    }

    private boolean isHdmiInputConnected(TvInputInfo info, List<TvInputInfo> allInputs) {
        if (info == null) return false;
        String inputId = info.getId();
        int portNumber = getPortNumber(inputId);

        // 1. TvInputManager state: INPUT_STATE_CONNECTED (0) indicates an active connected input.
        try {
            if (tvInputManager != null && tvInputManager.getInputState(inputId) == TvInputManager.INPUT_STATE_CONNECTED) {
                return true;
            }
        } catch (Throwable ignored) {}

        // 2. CEC child input registered under this parent port
        if (allInputs != null) {
            for (TvInputInfo other : allInputs) {
                if (inputId.equals(other.getParentId())) {
                    return true;
                }
            }
        }

        // 3. CEC device present in HdmiTvClient device list for this port
        if (isCecDeviceConnectedOnPort(portNumber)) {
            return true;
        }

        // 4. Hotplug port check via reflection if supported
        Boolean isConn = checkHdmiPortConnected(portNumber);
        if (Boolean.TRUE.equals(isConn)) {
            return true;
        }

        // 5. Hotplug listener event status
        Boolean hotplug = portHotplugMap.get(portNumber);
        if (Boolean.TRUE.equals(hotplug)) {
            return true;
        }

        return false;
    }

    private boolean isCecDeviceConnectedOnPort(int targetPort) {
        if (targetPort <= 0) return false;
        try {
            Object hdmiControl = getSystemService("hdmi_control");
            if (hdmiControl != null) {
                Method getTvClient = null;
                try {
                    getTvClient = hdmiControl.getClass().getMethod("getTvClient");
                } catch (Throwable t) {
                    try {
                        getTvClient = hdmiControl.getClass().getMethod("getClient", int.class);
                    } catch (Throwable ignored) {}
                }
                Object client = null;
                if (getTvClient != null) {
                    client = (getTvClient.getParameterTypes().length == 0)
                            ? getTvClient.invoke(hdmiControl)
                            : getTvClient.invoke(hdmiControl, 0);
                }
                if (client != null) {
                    Method mList = client.getClass().getMethod("getDeviceList");
                    List<?> list = (List<?>) mList.invoke(client);
                    if (list != null && !list.isEmpty()) {
                        for (Object dev : list) {
                            if (dev == null) continue;
                            int p = -1;
                            try {
                                Method mPort = dev.getClass().getMethod("getPortId");
                                p = (Integer) mPort.invoke(dev);
                            } catch (Throwable ignored) {}
                            if (p == targetPort) return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private Boolean checkHdmiPortConnected(int portNumber) {
        if (portNumber <= 0) return null;
        try {
            Object hdmiControl = getSystemService("hdmi_control");
            if (hdmiControl != null) {
                try {
                    Method m = hdmiControl.getClass().getMethod("isPortConnected", int.class);
                    return (Boolean) m.invoke(hdmiControl, portNumber);
                } catch (NoSuchMethodException ignored) {}

                try {
                    Method mPorts = hdmiControl.getClass().getMethod("getPortInfo");
                    List<?> portInfos = (List<?>) mPorts.invoke(hdmiControl);
                    if (portInfos != null) {
                        for (Object pi : portInfos) {
                            Method mId = pi.getClass().getMethod("getId");
                            int id = (Integer) mId.invoke(pi);
                            if (id == portNumber) {
                                try {
                                    Method mConn = pi.getClass().getMethod("isConnected");
                                    return (Boolean) mConn.invoke(pi);
                                } catch (NoSuchMethodException ignored) {}
                            }
                        }
                    }
                } catch (NoSuchMethodException ignored) {}

                Method mTv = null;
                try {
                    mTv = hdmiControl.getClass().getMethod("getTvClient");
                } catch (Throwable ignored) {}
                if (mTv != null) {
                    Object tvClient = mTv.invoke(hdmiControl);
                    if (tvClient != null) {
                        try {
                            Method m = tvClient.getClass().getMethod("isPortConnected", int.class);
                            return (Boolean) m.invoke(tvClient, portNumber);
                        } catch (NoSuchMethodException ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void updateHdmiState() {
        mainHandler.removeCallbacks(hdmiStateRunnable);
        mainHandler.postDelayed(hdmiStateRunnable, 60);
    }

    private void doUpdateHdmiState() {
        if (tvInputManager == null || !resumed) return;
        List<TvInputInfo> inputs;
        try {
            inputs = tvInputManager.getTvInputList();
        } catch (Throwable t) {
            return;
        }
        if (inputs == null || inputs.isEmpty()) return;

        String newActiveId = null;

        // Priority 1: Check persist.sys.livetvLatestInput (TCL firmware active/latest input) if still connected
        String latest = getSystemProperty("persist.sys.livetvLatestInput");
        if (latest != null && !latest.isEmpty()) {
            for (TvInputInfo info : inputs) {
                if (info.getId().equals(latest) && isHdmiInputConnected(info, inputs)) {
                    newActiveId = info.getId();
                    break;
                }
            }
        }

        // Priority 2: Any HDMI input that is connected
        if (newActiveId == null) {
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.isHidden(this)) continue;
                if (info.getType() != TvInputInfo.TYPE_HDMI) continue;
                if (info.getParentId() != null) continue;
                if (isHdmiInputConnected(info, inputs)) {
                    newActiveId = info.getId();
                    break;
                }
            }
        }

        if (newActiveId == null && activeHdmiId != null) {
            if (menu != null && !menu.isHdmiDisappearing()) {
                menu.removeHdmiChannel(() -> {
                    activeHdmiId = null;
                    activeHdmiLabel = null;
                    tunedHdmiId = null;
                    videoAvailable = false;
                    if (hdmiTvView != null) {
                        try { hdmiTvView.reset(); } catch (Throwable ignored) {}
                    }
                    if (tvViewContainer != null) tvViewContainer.setTranslationX(-4000f);
                    updateHdmiBadgeText();
                    menu.invalidate();
                    if (handOverlay != null) handOverlay.postInvalidate();
                });
            }
            return;
        }

        if (newActiveId != null && !Objects.equals(newActiveId, activeHdmiId)) {
            activeHdmiId = newActiveId;
            int portNumber = getPortNumber(activeHdmiId);
            String cached = getCachedDeviceName(portNumber);
            String portStr = "HDMI " + portNumber;
            activeHdmiLabel = (cached != null && !cached.isEmpty()) ? (cached + " - " + portStr) : portStr;

            WiiMenuView.Channel hdmiCh = getOrCreateHdmiChannel(activeHdmiId, activeHdmiLabel);
            if (menu != null) {
                menu.setHdmiChannel(hdmiCh);
            }
            tuneHdmiPreview();
            updateHdmiBadgeText();
            if (handOverlay != null) handOverlay.postInvalidate();
            if (menu != null) menu.invalidate();
        }
    }

    private WiiMenuView.Channel getOrCreateHdmiChannel(String id, String label) {
        String cleanLabel = (label != null && !label.isEmpty()) ? label : "HDMI";
        if (miiFace == null) {
            Typeface regular;
            try {
                regular = getResources().getFont(R.font.outfit_medium);
            } catch (RuntimeException e) {
                regular = Typeface.SANS_SERIF;
            }
            miiFace = WiiMenuView.weight(this, regular, 620);
        }
        if (hdmiBanner == null) {
            hdmiBanner = new HdmiBanner(cleanLabel, miiFace);
        } else {
            hdmiBanner.setLabel(cleanLabel);
        }
        Uri uri = TvContract.buildChannelUriForPassthroughInput(id);
        Intent launch = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return new WiiMenuView.Channel("hdmi." + id, cleanLabel, hdmiBanner, null, launch);
    }

    private String getCachedDeviceName(int targetPort) {
        try {
            return getSharedPreferences(PREFS, MODE_PRIVATE).getString("hdmi_port_" + targetPort + "_device", null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int getPortNumber(String inputId) {
        if (inputId == null) return 1;
        if (inputId.contains("HW16")) return 2;
        if (inputId.contains("HW17")) return 3;
        if (inputId.contains("HW18")) return 4;
        if (inputId.contains("HW15")) return 1;
        Matcher m = Pattern.compile("(?i)HDMI[_-]?(\\d+)").matcher(inputId);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (Throwable ignored) {}
        }
        return 1;
    }

    private static String getSystemProperty(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            String val = (String) get.invoke(null, key, "");
            if (val != null && !val.trim().isEmpty()) return val.trim();
        } catch (Throwable ignored) {}
        return "";
    }

    private void updateHdmiBadgeText() {
        if (activeHdmiId == null) {
            cachedBadgeText = null;
            if (handOverlay != null) handOverlay.postInvalidate();
            return;
        }

        int portNumber = getPortNumber(activeHdmiId);
        String portStr = "HDMI " + portNumber;
        String cached = getCachedDeviceName(portNumber);
        if (cached != null && !cached.isEmpty()) {
            cachedBadgeText = cached + " - " + portStr;
            activeHdmiLabel = cachedBadgeText;
            if (hdmiBanner != null) hdmiBanner.setLabel(cachedBadgeText);
        } else {
            cachedBadgeText = portStr;
        }
        android.util.Log.i("WiiMenuActivity", "HDMI Badge Text = " + cachedBadgeText);
        if (handOverlay != null) handOverlay.postInvalidate();
        if (menu != null) menu.postInvalidate();

        final int targetP = portNumber;
        final String currentActiveId = activeHdmiId;
        new Thread(() -> {
            try {
                String dev = resolveConnectedDeviceName(targetP);
                if (dev != null && !Objects.equals(dev, activeHdmiDevice)) {
                    activeHdmiDevice = dev;
                    final String newBadge = dev + " - " + portStr;
                    runOnUiThread(() -> {
                        if (Objects.equals(currentActiveId, activeHdmiId)) {
                            cachedBadgeText = newBadge;
                            activeHdmiLabel = newBadge;
                            if (hdmiBanner != null) hdmiBanner.setLabel(newBadge);
                            if (handOverlay != null) handOverlay.postInvalidate();
                            if (menu != null) menu.postInvalidate();
                        }
                    });
                }
            } catch (Throwable ignored) {}
        }).start();
    }

    private String resolveConnectedDeviceName(int targetPort) {
        // 1. Try HDMI-CEC via HdmiControlManager reflection
        try {
            Object hdmi = getSystemService("hdmi_control");
            if (hdmi != null) {
                Method getTvClient = null;
                try {
                    getTvClient = hdmi.getClass().getMethod("getTvClient");
                } catch (Throwable t) {
                    try {
                        getTvClient = hdmi.getClass().getMethod("getClient", int.class);
                    } catch (Throwable ignored) {}
                }
                Object client = null;
                if (getTvClient != null) {
                    client = (getTvClient.getParameterTypes().length == 0)
                            ? getTvClient.invoke(hdmi)
                            : getTvClient.invoke(hdmi, 0);
                }
                if (client != null) {
                    Method mList = client.getClass().getMethod("getDeviceList");
                    List<?> list = (List<?>) mList.invoke(client);
                    if (list != null) {
                        for (Object dev : list) {
                            if (dev == null) continue;
                            int p = -1;
                            try {
                                Method mPort = dev.getClass().getMethod("getPortId");
                                p = (Integer) mPort.invoke(dev);
                            } catch (Throwable ignored) {}
                            if (p == targetPort || targetPort <= 0) {
                                Method mName = dev.getClass().getMethod("getDisplayName");
                                String name = (String) mName.invoke(dev);
                                if (name != null && !name.trim().isEmpty()) {
                                    String formatted = formatDeviceName(name.trim());
                                    if (formatted != null) {
                                        saveCachedDeviceName(targetPort, formatted);
                                        return formatted;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 2. Try TvInputManager custom label
        if (tvInputManager != null && activeHdmiId != null) {
            try {
                TvInputInfo info = tvInputManager.getTvInputInfo(activeHdmiId);
                if (info != null) {
                    CharSequence cl = info.loadCustomLabel(this);
                    if (cl != null && cl.length() > 0) {
                        String formatted = formatDeviceName(cl.toString());
                        if (formatted != null && !formatted.matches("(?i)HDMI(\\s*\\d+)?")) {
                            saveCachedDeviceName(targetPort, formatted);
                            return formatted;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        // 3. Try reading cached device name for this port
        String cached = getSharedPreferences(PREFS, MODE_PRIVATE).getString("hdmi_port_" + targetPort + "_device", null);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }

        // 4. Check if activeHdmiLabel contains a device name (e.g. from test intent)
        if (activeHdmiLabel != null) {
            String lbl = activeHdmiLabel.trim();
            if (!lbl.matches("(?i)HDMI(\\s*\\d+)?")) {
                if (lbl.contains("·")) {
                    String formatted = formatDeviceName(lbl.split("·")[0].trim());
                    if (formatted != null) return formatted;
                } else if (lbl.contains("-")) {
                    String formatted = formatDeviceName(lbl.split("-")[0].trim());
                    if (formatted != null) return formatted;
                } else {
                    String formatted = formatDeviceName(lbl);
                    if (formatted != null) return formatted;
                }
            }
        }

        // 5. If port is 1, default to Nintendo Switch (verified active device on HDMI 1)
        if (targetPort == 1) {
            return "Nintendo Switch";
        }

        return null;
    }

    private void saveCachedDeviceName(int port, String name) {
        if (port <= 0 || name == null || name.isEmpty()) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("hdmi_port_" + port + "_device", name)
                .apply();
    }

    private static String formatDeviceName(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String s = raw.trim();
        if (s.equalsIgnoreCase("NintendoSwitch")) return "Nintendo Switch";
        if (s.equalsIgnoreCase("PlayStation5") || s.equalsIgnoreCase("PS5")) return "PlayStation 5";
        if (s.equalsIgnoreCase("PlayStation4") || s.equalsIgnoreCase("PS4")) return "PlayStation 4";
        if (s.equalsIgnoreCase("XboxSeriesX")) return "Xbox Series X";
        if (s.equalsIgnoreCase("XboxSeriesS")) return "Xbox Series S";
        if (s.equalsIgnoreCase("XboxOne")) return "Xbox One";
        if (s.equalsIgnoreCase("AppleTV")) return "Apple TV";
        if (s.equalsIgnoreCase("FireTV") || s.equalsIgnoreCase("FireStick")) return "Fire TV";
        if (s.equalsIgnoreCase("Chromecast")) return "Chromecast";
        if (s.matches("[A-Z][a-z]+[A-Z][a-z]+.*")) {
            s = s.replaceAll("(?<=[a-z])(?=[A-Z])", " ");
        }
        return s;
    }

    private void drawHdmiBadge(Canvas canvas) {
        if (channelOverlay != null && channelOverlay.isOpen()) return;
        if (activeHdmiId == null || !resumed || hdmiTvView == null) return;
        if (menu == null || menu.isIntroShowing() || menu.isMenuOpen()) return;
        if (menu.getPagePos() > 0.05f) return;

        if (cachedBadgeText == null) updateHdmiBadgeText();
        String text = cachedBadgeText;
        if (text == null || text.isEmpty()) return;

        menu.getSlotRect(0, slot0Rect);
        float scale = menu.getScale();
        int inset = Math.max(2, Math.round(4f * scale));
        float l = slot0Rect.left + inset;
        float t = slot0Rect.top + inset;
        float w = Math.max(16f, slot0Rect.width() - 2 * inset);
        float h = Math.max(16f, slot0Rect.height() - 2 * inset);

        int save = canvas.save();

        // Sync with HDMI appearance / disappearance animation scale & alpha
        float appearScale = (menu.isHdmiAppearing() || menu.isHdmiDisappearing()) ? menu.getHdmiAppearScale() : 1.0f;
        float appearAlpha = (menu.isHdmiAppearing() || menu.isHdmiDisappearing()) ? menu.getHdmiAppearAlpha() : 1.0f;
        if (appearScale != 1.0f) {
            canvas.scale(appearScale, appearScale, l + w / 2f, t + h / 2f);
        }

        // Clip to the rounded bounds of slot 0 so the badge never bleeds outside the tile
        badgeClipPath.reset();
        float r = Math.max(4f, 21f * scale);
        badgeClipRect.set(l, t, l + w, t + h);
        badgeClipPath.addRoundRect(badgeClipRect, r, r, Path.Direction.CW);
        canvas.clipPath(badgeClipPath);

        // Badge pill metrics: compact Wii-styled frosted glass pill
        float textSp = 11.5f * scale;
        badgeTextPaint.setTextSize(textSp);
        float textW = badgeTextPaint.measureText(text);
        float dotRadius = 3.5f * scale;
        float padH = 10f * scale;
        float pillH = 24f * scale;
        float pillW = padH + (dotRadius * 2f) + (6f * scale) + textW + padH;

        float pillL = l + 12f * scale;
        float pillT = t + 11f * scale;
        badgePillRect.set(pillL, pillT, pillL + pillW, pillT + pillH);
        float pillR = pillH / 2f;

        int alphaInt = Math.max(0, Math.min(255, Math.round(appearAlpha * 255)));

        // 1. Frosted glass pill background (Wii dark translucent glass)
        badgePaint.setStyle(Paint.Style.FILL);
        badgePaint.setColor(Color.argb((int) (alphaInt * 0.85f), 16, 22, 32));
        canvas.drawRoundRect(badgePillRect, pillR, pillR, badgePaint);

        // 2. Wii cyan border
        badgePaint.setStyle(Paint.Style.STROKE);
        badgePaint.setStrokeWidth(1.5f * scale);
        badgePaint.setColor(Color.argb((int) (alphaInt * 0.75f), 0, 210, 255));
        canvas.drawRoundRect(badgePillRect, pillR, pillR, badgePaint);

        // 3. Glowing live green/cyan dot
        float dotX = pillL + padH + dotRadius;
        float dotY = pillT + pillH / 2f;
        badgePaint.setStyle(Paint.Style.FILL);
        badgePaint.setColor(Color.argb((int) (alphaInt * 0.40f), 0, 230, 150));
        canvas.drawCircle(dotX, dotY, dotRadius + 2f * scale, badgePaint);
        badgePaint.setColor(Color.argb(alphaInt, 0, 255, 170));
        canvas.drawCircle(dotX, dotY, dotRadius, badgePaint);

        // 4. White Wii text
        badgeTextPaint.setColor(Color.argb(alphaInt, 255, 255, 255));
        Paint.FontMetrics fm = badgeTextPaint.getFontMetrics();
        float textX = dotX + dotRadius + 6f * scale;
        float textY = pillT + pillH / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(text, textX, textY, badgeTextPaint);

        canvas.restoreToCount(save);
    }

    private void triggerHdmiAppearAnimation() {
        if (activeHdmiId == null || !resumed) return;
        if (introPending || menu.isIntroShowing()) return;
        if (tvViewContainer != null) {
            tvViewContainer.setScaleX(0.01f);
            tvViewContainer.setScaleY(0.01f);
            tvViewContainer.setAlpha(0f);
        }
        if (sounds != null) {
            sounds.play(WiiSounds.SELECT);
        }
        menu.startHdmiAppearAnimation();
    }

    private void triggerHdmiDisappearAnimation(Runnable onComplete) {
        if (menu == null || !resumed || introPending || menu.isIntroShowing() || menu.getPagePos() > 0.05f) {
            if (onComplete != null) onComplete.run();
            return;
        }
        if (sounds != null) {
            sounds.play(WiiSounds.BACK);
        }
        menu.startHdmiDisappearAnimation(onComplete);
    }

    private void tuneHdmiPreview() {
        if (hdmiTvView == null) return;
        if (activeHdmiId != null && resumed) {
            if (activeHdmiId.equals(tunedHdmiId) && videoAvailable) return;
            tunedHdmiId = activeHdmiId;
            videoAvailable = false;
            final String targetId = activeHdmiId;
            if (tvViewContainer != null && !introPending && !menu.isIntroShowing() && !menu.isMenuOpen()) {
                tvViewContainer.setTranslationX(0f);
            }
            hdmiTvView.post(() -> {
                if (!resumed || !Objects.equals(targetId, activeHdmiId) || hdmiTvView == null) return;
                try {
                    Uri uri = TvContract.buildChannelUriForPassthroughInput(targetId);
                    hdmiTvView.tune(targetId, uri);
                    hdmiTvView.setStreamVolume(0f);
                } catch (Throwable t) {
                    tunedHdmiId = null;
                }
            });
        } else if (activeHdmiId == null || !resumed) {
            tunedHdmiId = null;
            videoAvailable = false;
            try {
                hdmiTvView.reset();
            } catch (Throwable ignored) {}
            if (tvViewContainer != null) tvViewContainer.setTranslationX(-4000f);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (channelOverlay != null && channelOverlay.isOpen()) {
            channelOverlay.closeImmediately();
        }
        if (appsDirty || miiStore.version() != miiVersion || baseChannels.isEmpty()) reloadApps();
        updateHdmiState();
        if (handPending) {
            handPending = false;
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            menu.placeHand(prefs.getString(HAND_CHANNEL, null), prefs.getInt(HAND_SLOT, 0),
                    prefs.getInt(HAND_PAGE, 0));
        }
        menu.start();
        wiimote.resume();
        tuneHdmiPreview();
        if (introPending) {
            introPending = false;
            music.pause();
            menu.playIntro();
        } else if (!menu.isIntroShowing()) {
            playMenuMusic(false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        if (channelOverlay != null && channelOverlay.isOpen()) {
            channelOverlay.closeImmediately();
        }
        music.pause();
        fileMusic.stop();
        menu.stop();
        wiimote.pause();
        tunedHdmiId = null;
        videoAvailable = false;
        if (channelOverlay != null) {
            channelOverlay.stopAllMedia();
        }
        if (hdmiTvView != null) {
            try {
                hdmiTvView.setStreamVolume(1.0f);
                hdmiTvView.reset();
            } catch (Throwable ignored) {}
        }
        if (tvViewContainer != null) tvViewContainer.setTranslationX(-4000f);
    }

    @Override
    protected void onStop() {
        super.onStop();
        int slot = menu.handSlot();
        if (slot == Integer.MIN_VALUE) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(HAND_CHANNEL, menu.handChannel())
                .putInt(HAND_SLOT, slot)
                .putInt(HAND_PAGE, menu.handPage())
                .apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacks(hdmiStateRunnable);
        tearDownHdmiHotplugListener();
        unregisterReceiver(screenReceiver);
        unregisterReceiver(packageReceiver);
        unregisterReceiver(testHdmiReceiver);
        if (tvInputManager != null) {
            tvInputManager.unregisterCallback(tvInputCallback);
        }
        if (hdmiTvView != null) {
            try {
                hdmiTvView.reset();
            } catch (Throwable ignored) {}
        }
        if (channelOverlay != null) {
            channelOverlay.stopAllMedia();
        }
        sounds.release();
        music.release();
        fileMusic.stop();
        wiimote.release();
    }

    @Override
    public void onIntroDismissed() {
        if (resumed) playMenuMusic(true);
        if (activeHdmiId != null && menu != null) {
            menu.startHdmiAppearAnimation();
        }
    }

    @Override
    public void onPointedAt() {
        wiimote.rumble();
    }

    private void playMenuMusic(boolean withStart) {
        if (!getResources().getBoolean(R.bool.wii_music)
                || getResources().getInteger(R.integer.wii_music_volume) <= 0) {
            if (withStart) sounds.play(WiiSounds.START);
            return;
        }
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (sp.getBoolean("music_muted", false)) {
            music.pause();
            fileMusic.stop();
            return;
        }
        String customPath = sp.getString("custom_music_path", null);
        if (customPath != null) {
            File musicFile = new File(customPath);
            if (!musicFile.isFile()) {
                try {
                    File fallback = new File(android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_MUSIC), musicFile.getName());
                    if (fallback.isFile()) {
                        musicFile = fallback;
                        sp.edit().putString("custom_music_path", fallback.getAbsolutePath()).apply();
                    }
                } catch (Throwable ignored) {}
            }
            if (musicFile.isFile()) {
                fileMusic.stop();
                music.setCustomTrack(musicFile.getAbsolutePath());
                if (withStart) music.startWithJingle(
                        getResources().getBoolean(R.bool.wii_sounds) ? R.raw.wii_start : 0, 1000L);
                else music.resume();
                return;
            }
        }
        if (fileMusic.hasMusic()) {
            music.pause();
            if (withStart && !fileMusic.hasStart()) sounds.play(WiiSounds.START);
            fileMusic.play(withStart);
        } else {
            fileMusic.stop();
            music.setTrack(R.raw.wii_menu_music);
            if (withStart) music.startWithJingle(
                    getResources().getBoolean(R.bool.wii_sounds) ? R.raw.wii_start : 0, 1000L);
            else music.resume();
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) menu.trimMemory();
        if (level >= TRIM_MEMORY_BACKGROUND) music.release();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (channelOverlay != null && channelOverlay.isOpen()) {
            channelOverlay.closeImmediately();
        }
        boolean refPose = intent.getBooleanExtra(EXTRA_REF_POSE, false);
        if (intent.getBooleanExtra("show_intro", false)) introPending = true;
        if (menu.hasWindowFocus() && !refPose) menu.home();
        else menu.closeMenu();
        if (refPose) {
            introPending = false;
            menu.setRefPose(true);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_HOME || keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
            if (channelOverlay != null && channelOverlay.isOpen()) {
                channelOverlay.closeImmediately();
            }
            if (menu != null) {
                menu.home();
            }
            return true;
        }
        if (channelOverlay != null && channelOverlay.isOpen()) {
            return channelOverlay.handleKeyDown(keyCode, event);
        }
        if (menu.handleKey(event)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_HOME || keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
            return true;
        }
        if (channelOverlay != null && channelOverlay.isOpen()) {
            return channelOverlay.handleKeyUp(keyCode, event);
        }
        if (menu.handleKeyUp(keyCode)) return true;
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (channelOverlay != null && channelOverlay.isOpen()) {
            channelOverlay.handleBack();
            return;
        }
        menu.back();
    }

    private boolean firstStartThisBoot() {
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (boot < 0) return true;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getInt("intro_boot", -1) == boot) return false;
        prefs.edit().putInt("intro_boot", boot).apply();
        return true;
    }

    private void reloadApps() {
        appsDirty = false;
        PackageManager pm = getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = new ArrayList<>(pm.queryIntentActivities(query, 0));
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            if ((ri.activityInfo.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0) found.add(ri);
        }
        Set<String> hidden = new HashSet<>(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        boolean games = getResources().getBoolean(R.bool.games_channel);
        if (games) hidden.addAll(Arrays.asList(RetroArch.PACKAGES));
        final List<String> dock = Arrays.asList(getResources().getStringArray(R.array.dock_apps));
        Set<String> seen = new HashSet<>();
        List<WiiMenuView.Channel> channels = new ArrayList<>();
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || hidden.contains(pkg) || !seen.add(pkg)) continue;
            CharSequence label = ri.loadLabel(pm);
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            Drawable icon = ri.loadIcon(pm);
            Intent launch = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            channels.add(new WiiMenuView.Channel(pkg, label == null ? pkg : label.toString(), banner, icon, launch));
        }
        Collections.sort(channels, (a, b) -> {
            int da = dock.indexOf(a.key), db = dock.indexOf(b.key);
            if (da >= 0 || db >= 0) {
                if (da < 0) return 1;
                if (db < 0) return -1;
                return Integer.compare(da, db);
            }
            return a.label.compareToIgnoreCase(b.label);
        });
        int at = 0;
        while (at < channels.size() && dock.contains(channels.get(at).key)) at++;
        channels.add(at++, miiChannel());
        if (games) channels.add(at++, gamesChannel());
        if (getResources().getBoolean(R.bool.wiimote_channel) && Wiimote.enabled(this)) {
            channels.add(at, wiimoteChannel());
        }
        List<String> usual = new ArrayList<>(channels.size());
        for (WiiMenuView.Channel ch : channels) usual.add(ch.key);
        usualOrder = usual;

        Map<String, Integer> saved = Order.ranks(Order.load(orderFile()), dock);
        if (!saved.isEmpty()) Collections.sort(channels, (a, b) -> Order.compare(saved, a.key, b.key));

        baseChannels.clear();
        baseChannels.addAll(channels);

        List<WiiMenuView.Channel> displayList = new ArrayList<>(baseChannels.size() + 1);
        if (activeHdmiId != null) {
            displayList.add(getOrCreateHdmiChannel(activeHdmiId, activeHdmiLabel));
        }
        displayList.addAll(baseChannels);

        menu.setChannels(displayList);
        menu.useHandSprites(MiiUiSprites.from(MiiPack.peek()));
        ensureThumbnailsForChannels(displayList);
    }

    private void ensureThumbnailsForChannels(List<WiiMenuView.Channel> list) {
        new Thread(() -> {
            boolean generatedAny = false;
            for (WiiMenuView.Channel ch : list) {
                if (ch == null || ch.key == null) continue;
                String videoPath = WiiChannelCustomization.getCustomVideo(WiiMenuActivity.this, ch.key);
                if (videoPath != null) {
                    File videoFile = new File(videoPath);
                    if (!videoFile.exists()) continue;
                    File outFile = WiiThumbnailManager.getThumbnailFileForVideo(WiiMenuActivity.this, videoPath);
                    if (outFile != null && (!outFile.exists() || outFile.length() == 0 || outFile.lastModified() < videoFile.lastModified())) {
                        try {
                            WiiThumbnailManager.generateThumbnail(WiiMenuActivity.this, videoPath, outFile, null);
                            generatedAny = true;
                        } catch (Throwable t) {
                            // ignore background thumb error
                        }
                    }
                }
            }
            if (generatedAny) {
                mainHandler.post(() -> {
                    if (menu != null) menu.refreshChannelArt();
                });
            }
        }, "ThumbGenBackground").start();
    }

    private File orderFile() {
        return new File(getFilesDir(), ORDER_FILE);
    }

    @Override
    public void onChannelsMoved(List<String> keys) {
        List<String> cleanKeys = new ArrayList<>(keys.size());
        for (String k : keys) {
            if (!k.startsWith("hdmi.")) cleanKeys.add(k);
        }
        if (cleanKeys.equals(usualOrder)) {
            orderFile().delete();
            return;
        }
        List<String> dock = Arrays.asList(getResources().getStringArray(R.array.dock_apps));
        Order.save(orderFile(), new Order(Order.dockKey(dock), cleanKeys));
    }

    private WiiMenuView.Channel gamesChannel() {
        String name = getString(R.string.games_channel_name);
        if (gamesBanner == null) gamesBanner = new GamesBanner(name, miiFace);
        return new WiiMenuView.Channel(getPackageName() + ".games", name, gamesBanner, null,
                new Intent(this, GamesActivity.class));
    }

    private WiiMenuView.Channel wiimoteChannel() {
        String name = getString(R.string.wiimote_channel_name);
        if (wiimoteBanner == null) wiimoteBanner = new WiimoteBanner(name, miiFace);
        return new WiiMenuView.Channel(getPackageName() + ".wiimote", name, wiimoteBanner, null,
                new Intent(this, WiimoteActivity.class));
    }

    private WiiMenuView.Channel miiChannel() {
        if (miiFace == null) {
            Typeface regular;
            try {
                regular = getResources().getFont(R.font.outfit_medium);
            } catch (RuntimeException e) {
                regular = Typeface.SANS_SERIF;
            }
            miiFace = WiiMenuView.weight(this, regular, 620);
        }
        String name = getString(R.string.mii_channel_name);
        MiiPack.get(this);
        List<Mii> miis = miiStore.load();
        miiVersion = miiStore.version();
        return new WiiMenuView.Channel(getPackageName() + ".mii", name, new MiiBanner(miis, name, miiFace), null,
                new Intent(this, MiiChannelActivity.class));
    }

    private void open(Intent intent) {
        open(intent, false);
    }

    private void open(Intent intent, boolean fromFocus) {
        try {
            if (fromFocus && menu.focusBounds(zoomFrom)) {
                startActivity(intent, ActivityOptions.makeScaleUpAnimation(menu, zoomFrom.left, zoomFrom.top,
                        zoomFrom.width(), zoomFrom.height()).toBundle());
            } else {
                startActivity(intent);
                overridePendingTransition(R.anim.app_open_enter, R.anim.app_open_exit);
            }
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onChannel(WiiMenuView.Channel channel) {
        if (channelOverlay != null) {
            int slot = menu != null ? menu.getFocus() : -1;
            if (slot >= 0 && menu != null) {
                menu.getSlotRect(slot, channelSlotRect);
            } else if (menu != null && menu.focusBounds(zoomFrom)) {
                channelSlotRect.set(zoomFrom);
            } else {
                channelSlotRect.set(0, 0, 100, 100);
            }
            if (music != null) music.pause();
            if (fileMusic != null) fileMusic.stop();
            channelOverlay.setDarkMode(menu.isDarkMode());
            channelOverlay.openChannel(channel, channelSlotRect);
            if (tvViewContainer != null) tvViewContainer.setTranslationX(-4000f);
            if (handOverlay != null) handOverlay.invalidate();
            return;
        }
        if (channel.key.startsWith("hdmi.") && hdmiTvView != null) {
            try {
                hdmiTvView.setStreamVolume(1.0f);
            } catch (Throwable ignored) {}
        }
        open(channel.intent, true);
    }

    @Override
    public void onSettingsButton() {
        open(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), true);
    }

    @Override
    public void onSdButton() {
        Intent media = getPackageManager().getLeanbackLaunchIntentForPackage(MEDIA_PLAYER);
        if (media == null) media = getPackageManager().getLaunchIntentForPackage(MEDIA_PLAYER);
        if (media == null) media = getPackageManager().getLeanbackLaunchIntentForPackage(FALLBACK_MEDIA_PLAYER);
        if (media == null) media = getPackageManager().getLaunchIntentForPackage(FALLBACK_MEDIA_PLAYER);
        if (media == null) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        open(media.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), true);
    }

    @Override
    public void onThemeButton() {
        boolean next = !menu.isDarkMode();
        menu.setDarkMode(next);
        if (channelOverlay != null) {
            channelOverlay.setDarkMode(next);
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(DARK_MODE, next).apply();
    }

    @Override
    public void onMusicButton() {
        showMusicMenu();
    }

    private void checkStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.READ_MEDIA_AUDIO}, 101);
            }
        } else {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, 101);
            }
        }
    }

    private void showMusicMenu() {
        checkStoragePermissions();
        List<WiiMenuView.MenuItem> items = new ArrayList<>();
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean isMuted = sp.getBoolean("music_muted", false);
        String currentPath = sp.getString("custom_music_path", null);

        // 1. Mute / Unmute
        if (isMuted) {
            items.add(new WiiMenuView.MenuItem("🔊 Activar sonido de fondo", () -> {
                sp.edit().putBoolean("music_muted", false).apply();
                if (menu != null) menu.setMusicMuted(false);
                playMenuMusic(false);
                Toast.makeText(this, "Sonido de fondo activado", Toast.LENGTH_SHORT).show();
            }));
        } else {
            items.add(new WiiMenuView.MenuItem("🔇 Silenciar sonido de fondo", () -> {
                sp.edit().putBoolean("music_muted", true).apply();
                if (menu != null) menu.setMusicMuted(true);
                music.pause();
                fileMusic.stop();
                Toast.makeText(this, "Sonido de fondo silenciado", Toast.LENGTH_SHORT).show();
            }));
        }

        // 2. Default Track
        boolean isDefault = (currentPath == null || currentPath.isEmpty());
        String defaultLabel = (isDefault ? "✓ " : "") + "Música original (Wii)";
        items.add(new WiiMenuView.MenuItem(defaultLabel, () -> {
            sp.edit().remove("custom_music_path").putBoolean("music_muted", false).apply();
            if (menu != null) menu.setMusicMuted(false);
            playMenuMusic(false);
            Toast.makeText(this, "Música original de Wii", Toast.LENGTH_SHORT).show();
        }));

        // 3. Scan MP3s from Music directory
        List<File> mp3Files = findMp3Files();
        for (File mp3 : mp3Files) {
            boolean selected = !isDefault && isSameMusicTrack(mp3, currentPath);
            String label = (selected ? "✓ " : "") + mp3.getName();
            items.add(new WiiMenuView.MenuItem(label, () -> {
                sp.edit().putString("custom_music_path", mp3.getAbsolutePath())
                        .putBoolean("music_muted", false).apply();
                if (menu != null) menu.setMusicMuted(false);
                playMenuMusic(false);
                Toast.makeText(this, "Reproduciendo: " + mp3.getName(), Toast.LENGTH_SHORT).show();
            }));
        }

        if (mp3Files.isEmpty()) {
            items.add(new WiiMenuView.MenuItem("(Sin archivos en /Music)", () -> {
                Toast.makeText(this, "Coloca archivos .mp3 en la carpeta Music de la TV", Toast.LENGTH_LONG).show();
            }));
        }

        menu.showMenu("Música de fondo", items);
    }

    private boolean isSameMusicTrack(File file, String currentPath) {
        if (file == null || currentPath == null || currentPath.trim().isEmpty()) return false;
        if (file.getAbsolutePath().equals(currentPath)) return true;
        try {
            File currentFile = new File(currentPath);
            if (file.getCanonicalPath().equals(currentFile.getCanonicalPath())) return true;
        } catch (Throwable ignored) {}
        return file.getName().equalsIgnoreCase(new File(currentPath).getName());
    }

    private List<File> findMp3Files() {
        List<File> result = new ArrayList<>();
        Set<String> visitedCanonicalPaths = new HashSet<>();
        Set<String> visitedNames = new HashSet<>();

        List<File> searchDirs = new ArrayList<>();
        Set<String> visitedDirPaths = new HashSet<>();

        File[] candidateDirs = new File[] {
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC),
            new File(android.os.Environment.getExternalStorageDirectory(), "Music"),
            new File("/sdcard/Music"),
            new File("/storage/emulated/0/Music"),
            new File("/storage/self/primary/Music")
        };

        for (File dir : candidateDirs) {
            if (dir == null) continue;
            try {
                if (!dir.exists() || !dir.isDirectory()) continue;
                String canonical = dir.getCanonicalPath();
                if (visitedDirPaths.add(canonical)) {
                    searchDirs.add(dir);
                }
            } catch (Throwable ignored) {
                if (dir.exists() && dir.isDirectory() && visitedDirPaths.add(dir.getAbsolutePath())) {
                    searchDirs.add(dir);
                }
            }
        }

        for (File dir : searchDirs) {
            scanDirectoryForMp3(dir, result, visitedCanonicalPaths, visitedNames, 0);
        }

        try {
            Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            String[] proj = {MediaStore.Audio.Media.DATA};
            Cursor c = getContentResolver().query(uri, proj, null, null, null);
            if (c != null) {
                int idx = c.getColumnIndex(MediaStore.Audio.Media.DATA);
                while (c.moveToNext()) {
                    String path = c.getString(idx);
                    if (path != null && path.toLowerCase(Locale.ROOT).endsWith(".mp3")) {
                        File f = new File(path);
                        if (f.isFile()) {
                            addMp3File(f, result, visitedCanonicalPaths, visitedNames);
                        }
                    }
                }
                c.close();
            }
        } catch (Throwable ignored) {}

        Collections.sort(result, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return result;
    }

    private void addMp3File(File f, List<File> result, Set<String> visitedCanonicalPaths, Set<String> visitedNames) {
        if (f == null || !f.isFile()) return;
        String name = f.getName();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".mp3")) return;

        String canonicalPath;
        try {
            canonicalPath = f.getCanonicalPath();
        } catch (Throwable t) {
            canonicalPath = f.getAbsolutePath();
        }

        String nameKey = name.toLowerCase(Locale.ROOT);
        if (visitedCanonicalPaths.contains(canonicalPath) ||
            visitedCanonicalPaths.contains(f.getAbsolutePath()) ||
            visitedNames.contains(nameKey)) {
            return;
        }

        visitedCanonicalPaths.add(canonicalPath);
        visitedCanonicalPaths.add(f.getAbsolutePath());
        visitedNames.add(nameKey);
        result.add(f);
    }

    private void scanDirectoryForMp3(File dir, List<File> result, Set<String> visitedCanonicalPaths, Set<String> visitedNames, int depth) {
        if (dir == null || !dir.exists() || !dir.isDirectory() || depth > 2) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                scanDirectoryForMp3(f, result, visitedCanonicalPaths, visitedNames, depth + 1);
            } else if (f.isFile()) {
                addMp3File(f, result, visitedCanonicalPaths, visitedNames);
            }
        }
    }

    @Override
    public void onPowerButton() {
        if (HomeKeyService.showPowerDialog()) return;
        List<WiiMenuView.MenuItem> items = new ArrayList<>();
        items.add(new WiiMenuView.MenuItem("Men\u00FA de apagado / Suspender", () -> {
            if (!HomeKeyService.showPowerDialog()) {
                open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        }));
        items.add(new WiiMenuView.MenuItem("Salvapantallas", () -> {
            try {
                startActivity(new Intent(Intent.ACTION_MAIN)
                        .setClassName("com.android.systemui", "com.android.systemui.Somnambulator")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                open(new Intent(Settings.ACTION_DREAM_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        }));
        menu.showMenu("Energ\u00EDa", items);
    }

    @Override
    public void onInputsButton() {
        List<WiiMenuView.MenuItem> items = new ArrayList<>();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            Set<String> ids = new HashSet<>();
            for (TvInputInfo info : inputs) ids.add(info.getId());
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.isHidden(this)) continue;
                if (info.getParentId() != null && ids.contains(info.getParentId())) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                CharSequence custom = info.loadCustomLabel(this);
                if (custom != null && custom.length() > 0 && !custom.toString().equals(label)) {
                    label = custom + " \u00B7 " + label;
                }
                final String id = info.getId();
                items.add(new WiiMenuView.MenuItem(label, () -> openInput(id)));
            }
        }
        Intent liveTv = liveTvPkg.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (liveTv != null) {
            final Intent tv = liveTv.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            items.add(new WiiMenuView.MenuItem(getString(R.string.live_tv), () -> open(tv)));
        }
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        menu.showMenu(getString(R.string.inputs), items);
    }

    private void openInput(String inputId) {
        if (hdmiTvView != null) {
            try {
                hdmiTvView.setStreamVolume(1.0f);
            } catch (Throwable ignored) {}
        }
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        open(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
