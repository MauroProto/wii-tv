package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GrokPagesTest {

    @Test
    public void userAgentLosesTheWebViewMarksOnly() {
        assertEquals("Mozilla/5.0 (Linux; Android 11; 65PUS8506/12 Build/RTM6.230109.142) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/155.0.8059.43 Safari/537.36",
                GrokPages.userAgent("Mozilla/5.0 (Linux; Android 11; 65PUS8506/12 Build/RTM6.230109.142; wv) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/155.0.8059.43 Safari/537.36"));
        String chrome = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0 Safari/537.36";
        assertEquals(chrome, GrokPages.userAgent(chrome));
        assertNull(GrokPages.userAgent(null));
    }

    @Test
    public void onlyWebLinksOpenInTheChannel() {
        assertTrue(GrokPages.opensInApp("https://grok.com/"));
        assertTrue(GrokPages.opensInApp("HTTPS://x.com/i/oauth2/authorize?client_id=1"));
        assertTrue(GrokPages.opensInApp("http://example.com"));
        assertTrue(GrokPages.opensInApp("about:blank"));
        assertFalse(GrokPages.opensInApp("intent://x.com/#Intent;scheme=https;package=com.twitter.android;end"));
        assertFalse(GrokPages.opensInApp("twitter://login"));
        assertFalse(GrokPages.opensInApp("mailto:someone@example.com"));
        assertFalse(GrokPages.opensInApp("javascript:alert(1)"));
        assertFalse(GrokPages.opensInApp("no scheme"));
        assertFalse(GrokPages.opensInApp(null));
    }

    @Test
    public void intentLinksFallBackToTheirWebPage() {
        assertEquals("https://x.com/home?a=1",
                GrokPages.fallback("intent://x.com/home#Intent;scheme=https;package=com.twitter.android;"
                        + "S.browser_fallback_url=https%3A%2F%2Fx.com%2Fhome%3Fa%3D1;end"));
        assertNull(GrokPages.fallback("intent://x#Intent;S.browser_fallback_url=javascript%3Aalert(1);end"));
        assertNull(GrokPages.fallback("intent://x#Intent;package=com.twitter.android;end"));
        assertNull(GrokPages.fallback("intent://x#Intent;S.browser_fallback_url=%zz;end"));
        assertNull(GrokPages.fallback("https://x.com/?S.browser_fallback_url=https%3A%2F%2Fevil.example"));
        assertNull(GrokPages.fallback(null));
    }

    @Test
    public void homeIsGrokComAndNothingElse() {
        assertTrue(GrokPages.isHome("https://grok.com/"));
        assertTrue(GrokPages.isHome("https://grok.com/c/1234?rid=5"));
        assertTrue(GrokPages.isHome("https://GROK.com:443/chat"));
        assertTrue(GrokPages.isHome("https://www.grok.com"));
        assertFalse(GrokPages.isHome("https://accounts.x.ai/sign-in?redirect=grok-com"));
        assertFalse(GrokPages.isHome("https://x.com/i/oauth2/authorize?redirect_uri=https://grok.com/"));
        assertFalse(GrokPages.isHome("https://grok.com.example.net/"));
        assertFalse(GrokPages.isHome("https://notgrok.com/"));
        assertFalse(GrokPages.isHome("https://grok.com@example.net/"));
        assertFalse(GrokPages.isHome("about:blank"));
        assertFalse(GrokPages.isHome(null));
    }

    @Test
    public void handNudgesThenGlidesFasterUpToItsTopSpeed() {
        assertEquals(0f, GrokPages.speed(0f), 0f);
        assertEquals(0f, GrokPages.speed(GrokPages.GLIDE_AFTER - 0.01f), 0f);
        assertEquals(GrokPages.SLOW, GrokPages.speed(GrokPages.GLIDE_AFTER), 0.01f);
        float last = 0f;
        for (float t = GrokPages.GLIDE_AFTER; t < 3f; t += 0.1f) {
            float v = GrokPages.speed(t);
            assertTrue(v >= last);
            last = v;
        }
        assertEquals(GrokPages.FAST, GrokPages.speed(10f), 0.01f);
    }
}
