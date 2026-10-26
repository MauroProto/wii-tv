package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * A phone as the Wii Remote, in the screen in front. The relay (wiimote/, its address in wiimote.url) passes on what
 * the phone does; its buttons come in here as the remote's keys, sent into the screen as if pressed, and Home brings
 * the menu back. Connected only while the screen is resumed, so the screen in front is the only one listening. Moving
 * the hand and typing belong to the screen, through Pointer.
 */
final class Wiimote implements WiimoteLink.Listener {

    interface Pointer {
        /** The phone moved the hand dx, dy reference px (a 1920-wide screen). */
        void onWiimoteMove(float dx, float dy);

        default void onWiimoteText(String text, boolean enter) { }

        /** Phones connected, and how many there were before (-1 just after connecting, when that is not known). */
        default void onWiimotePhones(int count, int before) { }

        default void onWiimoteLink(boolean up) { }
    }

    private static final String PREFS = "wiimote", ROOM = "room", KEY = "key";

    private final Activity activity;
    private final Pointer pointer;
    private final String pair;
    /** When each phone button went down, 0 while it is up. */
    private final long[] downAt = new long[WiimoteProtocol.BUTTONS.length];
    private WiimoteLink link;
    private boolean resumed, typing, linked;
    private int phones = -1;

    Wiimote(Activity activity, Pointer pointer) {
        this(activity, pointer, null);
    }

    /** pair: the code the screen's QR code shows; phones get in with it while this screen is in front. */
    Wiimote(Activity activity, Pointer pointer, String pair) {
        this.activity = activity;
        this.pointer = pointer;
        this.pair = pair;
    }

    static boolean enabled(Context context) {
        return !WiimoteProtocol.base(context.getString(R.string.wiimote_url)).isEmpty();
    }

    /** Makes up the TV's room and key the first time. Only from the menu's process, so no other makes up a second. */
    static void setUp(Context context) {
        if (!enabled(context)) return;
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.contains(ROOM) && p.contains(KEY)) return;
        SecureRandom random = new SecureRandom();
        p.edit().putString(ROOM, WiimoteProtocol.code(random)).putString(KEY, WiimoteProtocol.key(random)).apply();
    }

    /** The page this pairing code opens on the phone; null before setUp. */
    static String pairUrl(Context context, String pair) {
        String room = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ROOM, null);
        String base = WiimoteProtocol.base(context.getString(R.string.wiimote_url));
        return room == null || base.isEmpty() ? null : WiimoteProtocol.pairUrl(base, room, pair);
    }

    void resume() {
        resumed = true;
        if (link == null) {
            SharedPreferences p = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String room = p.getString(ROOM, null), key = p.getString(KEY, null);
            String base = WiimoteProtocol.base(activity.getString(R.string.wiimote_url));
            if (room == null || key == null || base.isEmpty()) return;
            link = new WiimoteLink(base, WiimoteProtocol.tvPath(room, key, pair), this);
        }
        link.start();
    }

    void pause() {
        resumed = false;
        // The screen lets go of its own held keys as it leaves; there is nothing to send them to.
        Arrays.fill(downAt, 0L);
        linked = false;
        phones = -1;
        if (link != null) link.stop();
    }

    void release() {
        if (link != null) link.release();
        link = null;
    }

    /** Whether text from the phone goes anywhere here; the phone shows its keyboard button only then. */
    void typing(boolean on) {
        typing = on;
        if (linked) link.send(on ? "k 1" : "k 0");
    }

    /** A short buzz on the phones, when the hand lands on something. */
    void rumble() {
        if (linked && phones > 0) link.send("r");
    }

    int phones() {
        return Math.max(0, phones);
    }

    @Override
    public void onLink(boolean up) {
        if (!resumed) return;
        linked = up;
        if (up) {
            if (typing) link.send("k 1");
        } else {
            setPhones(0);
        }
        if (pointer != null) pointer.onWiimoteLink(up);
    }

    @Override
    public void onLine(String line) {
        if (!resumed) return;
        WiimoteProtocol.Message m = WiimoteProtocol.parse(line);
        if (m == null) return;
        switch (m.kind) {
            case WiimoteProtocol.MOVE:
                if (pointer != null) pointer.onWiimoteMove(m.dx, m.dy);
                break;
            case WiimoteProtocol.BUTTON:
                button(m.button, m.down);
                break;
            case WiimoteProtocol.TEXT:
                if (pointer != null) pointer.onWiimoteText(m.text, m.enter);
                break;
            case WiimoteProtocol.PHONES:
                setPhones(m.phones);
                break;
            default:
                break;
        }
    }

    private void setPhones(int count) {
        int before = phones;
        phones = count;
        if (count == 0) releaseKeys();
        if (pointer != null && count != before) pointer.onWiimotePhones(count, before);
    }

    private void button(String name, boolean down) {
        if (WiimoteProtocol.HOME.equals(name)) {
            if (down) home();
            return;
        }
        int i = WiimoteProtocol.index(name);
        if (i < 0 || down == (downAt[i] != 0L)) return;
        long now = SystemClock.uptimeMillis();
        if (down) downAt[i] = now;
        KeyEvent e = new KeyEvent(downAt[i], now, down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
                WiimoteProtocol.KEYS[i], 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
        if (!down) downAt[i] = 0L;
        activity.dispatchKeyEvent(e);
    }

    /** The phones went away with buttons held: they come up cancelled, so a held A opens nothing. */
    private void releaseKeys() {
        long now = SystemClock.uptimeMillis();
        for (int i = 0; i < downAt.length; i++) {
            if (downAt[i] == 0L) continue;
            KeyEvent e = new KeyEvent(downAt[i], now, KeyEvent.ACTION_UP, WiimoteProtocol.KEYS[i], 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_CANCELED, InputDevice.SOURCE_KEYBOARD);
            downAt[i] = 0L;
            activity.dispatchKeyEvent(e);
        }
    }

    private void home() {
        try {
            activity.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setPackage(activity.getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
            // No menu to go back to; nothing else to do.
        }
    }
}
