package com.gathin.tvhome;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * The Grok channel: grok.com full screen, as if it were an app. The remote steers the Wii hand over the page: the
 * arrows move it, OK touches what is under it (held, it drags), CH+ and CH- scroll, and so does pushing the hand
 * against the top or bottom; Back goes back a page, closes a window the page opened (signing in with X), and then
 * leaves. Its own process (":grok" in the manifest): the page's memory never weighs on the menu, and Android can
 * take all of it back once the channel is closed.
 */
public class GrokActivity extends Activity implements GrokOverlay.Host {

    /** How long the page gets before the picture lifts anyway: some pages never report that they finished. */
    private static final long LOAD_TIMEOUT_MS = 15_000L;
    /** CH+ and CH- scroll this much of the screen. */
    private static final float PAGE = 0.8f;
    /** Scroll per px the hand is pushed past the top or the bottom. */
    private static final float EDGE_SCROLL = 1.6f;

    private FrameLayout root;
    private GrokOverlay overlay;
    /** The channel's page first, then the windows it opened, newest last; the hand works on the last one. */
    private final List<WebView> pages = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String userAgent;
    private boolean resumed, failed, leftHome, rebuildPending;
    /** When OK went down on the page, 0 while it is up. */
    private long touchDown;

    private final WebViewClient client = new WebViewClient() {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (GrokPages.opensInApp(url)) return false;
            String page = GrokPages.fallback(url);
            if (page != null) view.loadUrl(page);
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (view != main()) return;
            if (!GrokPages.isHome(url)) {
                leftHome = true;
            } else if (leftHome) {
                // Back from signing in: Back should leave the channel, not walk through the sign-in pages again.
                leftHome = false;
                view.clearHistory();
            }
            if (!failed) overlay.ready();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (view != main() || !request.isForMainFrame()) return;
            failed = true;
            overlay.fail(getString(R.string.grok_offline));
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // Android took the page's memory back, or the page crashed: start it again rather than close with it.
            if (!rebuildPending) {
                rebuildPending = true;
                if (resumed) handler.post(() -> {
                    if (rebuildPending) rebuild();
                });
            }
            return true;
        }
    };

    private final WebChromeClient chrome = new WebChromeClient() {
        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            ((WebView.WebViewTransport) resultMsg.obj).setWebView(open());
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public void onCloseWindow(WebView window) {
            close(window);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        CookieManager.getInstance().setAcceptCookie(true);
        root = new FrameLayout(this);
        root.setBackgroundColor(GrokBanner.NIGHT);
        overlay = new GrokOverlay(this, this, getString(R.string.grok_channel_name), getString(R.string.grok_hint));
        root.addView(overlay, match());
        setContentView(root);
        open().loadUrl(GrokPages.HOME);
        handler.postDelayed(() -> {
            if (!failed) overlay.ready();
        }, LOAD_TIMEOUT_MS);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    /** A new page under the hand, on top of the others. */
    @SuppressLint("SetJavaScriptEnabled") // grok.com is a JavaScript app; the page gets no bridge into the TV
    private WebView open() {
        WebView w = new WebView(this);
        WebSettings ws = w.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setSupportMultipleWindows(true);
        ws.setJavaScriptCanOpenWindowsAutomatically(true);
        if (userAgent == null) userAgent = GrokPages.userAgent(ws.getUserAgentString());
        ws.setUserAgentString(userAgent);
        // Signing in with X goes through x.ai and x.com and back; their cookies have to come along.
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        w.setBackgroundColor(GrokBanner.NIGHT);
        w.setWebViewClient(client);
        w.setWebChromeClient(chrome);
        root.addView(w, root.indexOfChild(overlay), match());
        pages.add(w);
        if (resumed) w.onResume();
        return w;
    }

    private WebView main() {
        return pages.isEmpty() ? null : pages.get(0);
    }

    /** The page the hand works on; none while a page whose renderer went away waits to be started again. */
    private WebView top() {
        return pages.isEmpty() || rebuildPending ? null : pages.get(pages.size() - 1);
    }

    private void close(WebView w) {
        if (w == main() || !pages.remove(w)) return;
        touchDown = 0L;
        root.removeView(w);
        w.destroy();
    }

    private void destroyPages() {
        for (WebView w : pages) {
            root.removeView(w);
            w.destroy();
        }
        pages.clear();
        touchDown = 0L;
    }

    private void rebuild() {
        rebuildPending = false;
        destroyPages();
        failed = false;
        leftHome = false;
        overlay.loading();
        open().loadUrl(GrokPages.HOME);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (rebuildPending) rebuild();
        for (WebView w : pages) w.onResume();
        if (main() != null) main().resumeTimers();
        overlay.resume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        overlay.pause();
        touchDown = 0L;
        if (!rebuildPending) {
            for (WebView w : pages) w.onPause();
            // Timers are for the whole process, which is only this channel's.
            if (main() != null) main().pauseTimers();
        }
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        destroyPages();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (top() == null) {
            // The page is starting again; there is nothing to send keys to but Back still leaves.
            if (code == KeyEvent.KEYCODE_BACK && !down && !e.isCanceled()) back();
            return code == KeyEvent.KEYCODE_BACK || super.dispatchKeyEvent(e);
        }
        // The on-screen keyboard's own keys (its arrows, its Enter) are typing, not the hand; so is every key while
        // the keyboard is up and lets one through.
        if ((e.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) != 0 || typing()) return super.dispatchKeyEvent(e);
        boolean first = down && e.getRepeatCount() == 0;
        int dir = direction(code);
        if (dir >= 0) {
            if (first) overlay.press(dir);
            else if (!down) overlay.release(dir);
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (failed) {
                    if (!down) retry();
                } else if (first) {
                    touch(MotionEvent.ACTION_DOWN);
                } else if (!down) {
                    touch(MotionEvent.ACTION_UP);
                }
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
                if (down) wheel(-PAGE * root.getHeight());
                return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                if (down) wheel(PAGE * root.getHeight());
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!down && !e.isCanceled()) back();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private boolean typing() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        WindowInsets insets = root.getRootWindowInsets();
        return insets != null && insets.isVisible(WindowInsets.Type.ime());
    }

    private static int direction(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return GrokOverlay.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return GrokOverlay.DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return GrokOverlay.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return GrokOverlay.RIGHT;
            default:
                return -1;
        }
    }

    @Override
    public void onBackPressed() {
        // Back arrives through dispatchKeyEvent; this only catches a system back that never came through it.
        back();
    }

    private void back() {
        WebView w = top();
        if (w != null && !failed && w.canGoBack()) {
            w.goBack();
        } else if (w != null && w != main()) {
            close(w);
        } else {
            finish();
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }
    }

    private void retry() {
        failed = false;
        overlay.loading();
        main().reload();
    }

    /** OK on the page: a finger down or up where the hand points; the hand moving in between drags it. */
    private void touch(int action) {
        WebView w = top();
        if (action == MotionEvent.ACTION_DOWN) {
            w.requestFocus();
            touchDown = SystemClock.uptimeMillis();
        } else if (touchDown == 0L) {
            return;
        }
        send(w, touchDown, action, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_FINGER,
                overlay.handX(), overlay.handY(), 0f);
        if (action == MotionEvent.ACTION_UP) touchDown = 0L;
    }

    @Override
    public void onHandMoved(float x, float y) {
        WebView w = top();
        if (w == null) return;
        if (touchDown != 0L) {
            send(w, touchDown, MotionEvent.ACTION_MOVE, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_FINGER,
                    x, y, 0f);
        } else {
            // A mouse hovering, so what the hand points at lights up as it would under a pointer.
            send(w, SystemClock.uptimeMillis(), MotionEvent.ACTION_HOVER_MOVE, InputDevice.SOURCE_MOUSE,
                    MotionEvent.TOOL_TYPE_MOUSE, x, y, 0f);
        }
    }

    @Override
    public void onEdgePush(float dy) {
        if (touchDown == 0L) wheel(dy * EDGE_SCROLL);
    }

    /** Scrolls px down (up when negative) with a mouse wheel, under the hand's column of the page. */
    private void wheel(float px) {
        WebView w = top();
        if (w == null || failed) return;
        float h = root.getHeight();
        // Not at the very edge, where a page keeps its fixed header and footer that do not scroll.
        float y = Math.max(h * 0.25f, Math.min(h * 0.75f, overlay.handY()));
        float step = ViewConfiguration.get(this).getScaledVerticalScrollFactor();
        send(w, SystemClock.uptimeMillis(), MotionEvent.ACTION_SCROLL, InputDevice.SOURCE_MOUSE,
                MotionEvent.TOOL_TYPE_MOUSE, overlay.handX(), y, -px / step);
    }

    private static void send(WebView w, long downTime, int action, int source, int tool, float x, float y,
            float vscroll) {
        MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
        pp.id = 0;
        pp.toolType = tool;
        MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
        pc.x = x;
        pc.y = y;
        pc.pressure = 1f;
        pc.size = 1f;
        if (vscroll != 0f) pc.setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll);
        MotionEvent ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1,
                new MotionEvent.PointerProperties[] {pp}, new MotionEvent.PointerCoords[] {pc}, 0, 0, 1f, 1f, 0, 0,
                source, 0);
        if (source == InputDevice.SOURCE_TOUCHSCREEN) w.dispatchTouchEvent(ev);
        else w.dispatchGenericMotionEvent(ev);
        ev.recycle();
    }
}
