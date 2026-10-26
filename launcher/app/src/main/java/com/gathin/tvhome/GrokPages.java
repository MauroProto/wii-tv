package com.gathin.tvhome;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Locale;

/** The Grok channel's rules that need no Android: which links stay in it, how it names itself, how fast the hand goes. */
final class GrokPages {
    static final String HOME = "https://grok.com/";
    /** Seconds an arrow is held before the hand starts to glide; a shorter press only nudges it. */
    static final float GLIDE_AFTER = 0.18f;
    /** Gliding speed in reference px (1920 wide) per second: at first, and after a long hold. */
    static final float SLOW = 380f, FAST = 1500f;
    /** Seconds from the slow glide to the fast one. */
    private static final float RAMP = 1.1f;
    private static final String FALLBACK = "S.browser_fallback_url=";

    private GrokPages() { }

    /**
     * WebView's user agent without the marks of an embedded view ("; wv" and "Version/4.0"): sign-in pages turn
     * those away, and what is left names the Chrome that really draws the page.
     */
    static String userAgent(String webView) {
        if (webView == null) return null;
        return webView.replaceFirst(";\\s*wv\\)", ")").replaceFirst("\\s*Version/[0-9.]+", "");
    }

    /** Links that open inside the channel; the rest are for other apps (the X app, mail), which the TV hasn't got. */
    static boolean opensInApp(String url) {
        String s = scheme(url);
        return s.equals("https") || s.equals("http") || s.equals("about") || s.equals("blob");
    }

    /** The web page an intent: link falls back to (its S.browser_fallback_url), or null. */
    static String fallback(String url) {
        if (!scheme(url).equals("intent")) return null;
        int at = url.indexOf(FALLBACK);
        if (at < 0) return null;
        int start = at + FALLBACK.length(), end = url.indexOf(';', start);
        try {
            String page = URLDecoder.decode(url.substring(start, end < 0 ? url.length() : end), "UTF-8");
            String s = scheme(page);
            return s.equals("https") || s.equals("http") ? page : null;
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return null;
        }
    }

    /** grok.com's own pages, not the sign-in pages on other sites. */
    static boolean isHome(String url) {
        String host = host(url);
        return host.equals("grok.com") || host.endsWith(".grok.com");
    }

    /** How fast the hand glides, in reference px per second, once an arrow has been held for held seconds. */
    static float speed(float held) {
        if (held < GLIDE_AFTER) return 0f;
        float t = Math.min(1f, (held - GLIDE_AFTER) / RAMP);
        return SLOW + (FAST - SLOW) * t * t;
    }

    private static String scheme(String url) {
        if (url == null) return "";
        int colon = url.indexOf(':');
        return colon <= 0 ? "" : url.substring(0, colon).trim().toLowerCase(Locale.ROOT);
    }

    private static String host(String url) {
        if (url == null) return "";
        int start = url.indexOf("://");
        if (start < 0) return "";
        start += 3;
        int end = start;
        while (end < url.length() && "/?#".indexOf(url.charAt(end)) < 0) end++;
        String host = url.substring(start, end);
        host = host.substring(host.lastIndexOf('@') + 1);
        int colon = host.indexOf(':');
        return (colon < 0 ? host : host.substring(0, colon)).toLowerCase(Locale.ROOT);
    }
}
