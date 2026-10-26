package com.gathin.tvhome;

import android.view.KeyEvent;

import java.util.Locale;
import java.util.Random;

/**
 * The Wiimote's rules that need no Android: the lines the relay (wiimote/src/index.js) sends the TV, the keys the
 * phone's buttons press, and the codes the TV makes up.
 */
final class WiimoteProtocol {
    static final int MOVE = 1, BUTTON = 2, TEXT = 3, PHONES = 4;
    /** Not a key: it brings the menu back from any screen, as Home does. */
    static final String HOME = "home";
    /** The phone's buttons, and the remote's keys they press. */
    static final String[] BUTTONS = {"a", "b", "up", "down", "left", "right", "plus", "minus", "one", "two"};
    static final int[] KEYS = {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_PROG_GREEN,
    };
    private static final String CODE_CHARS = "abcdefghijklmnopqrstuvwxyz234567";
    private static final int CODE_LENGTH = 16;

    private WiimoteProtocol() { }

    static final class Message {
        final int kind;
        final float dx, dy;
        final String button;
        final boolean down;
        final String text;
        final boolean enter;
        final int phones;

        private Message(int kind, float dx, float dy, String button, boolean down, String text, boolean enter,
                int phones) {
            this.kind = kind;
            this.dx = dx;
            this.dy = dy;
            this.button = button;
            this.down = down;
            this.text = text;
            this.enter = enter;
            this.phones = phones;
        }
    }

    /**
     * One line from the relay: "m dx dy" (the hand moved), "b button 0|1" (a button went up or down), "t 0|1 text"
     * (text to type, 1 presses Enter after it) or "p count" (phones connected). Null for anything else.
     */
    static Message parse(String line) {
        if (line == null || line.length() < 3 || line.charAt(1) != ' ') return null;
        String rest = line.substring(2);
        try {
            switch (line.charAt(0)) {
                case 'm': {
                    int space = rest.indexOf(' ');
                    if (space < 0) return null;
                    float dx = Float.parseFloat(rest.substring(0, space)), dy = Float.parseFloat(rest.substring(space + 1));
                    if (!Float.isFinite(dx) || !Float.isFinite(dy)) return null;
                    return new Message(MOVE, dx, dy, null, false, null, false, 0);
                }
                case 'b': {
                    int space = rest.lastIndexOf(' ');
                    if (space < 0) return null;
                    String button = rest.substring(0, space), state = rest.substring(space + 1);
                    if (!state.equals("0") && !state.equals("1")) return null;
                    if (!button.equals(HOME) && index(button) < 0) return null;
                    return new Message(BUTTON, 0f, 0f, button, state.equals("1"), null, false, 0);
                }
                case 't': {
                    char enter = rest.charAt(0);
                    if (enter != '0' && enter != '1' || rest.length() > 1 && rest.charAt(1) != ' ') return null;
                    String text = rest.length() > 2 ? rest.substring(2) : "";
                    return new Message(TEXT, 0f, 0f, null, false, text, enter == '1', 0);
                }
                case 'p': {
                    int phones = Integer.parseInt(rest);
                    return phones < 0 ? null : new Message(PHONES, 0f, 0f, null, false, null, false, phones);
                }
                default:
                    return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The button's place in BUTTONS, or -1. */
    static int index(String button) {
        for (int i = 0; i < BUTTONS.length; i++) if (BUTTONS[i].equals(button)) return i;
        return -1;
    }

    /** A room or pairing code: 16 of a-z and 2-7, 80 random bits. */
    static String code(Random random) {
        StringBuilder b = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) b.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
        return b.toString();
    }

    /** The TV's key, which only it and the relay know: 32 hex digits. */
    static String key(Random random) {
        StringBuilder b = new StringBuilder(32);
        for (int i = 0; i < 16; i++) b.append(String.format(Locale.ROOT, "%02x", random.nextInt(256)));
        return b.toString();
    }

    /** The relay's address without the slashes at its end; empty unless it is an http or https address. */
    static String base(String url) {
        if (url == null) return "";
        String u = url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        String lower = u.toLowerCase(Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://") ? u : "";
    }

    /** Where the TV connects; pair is the code its QR code shows, null where none is shown. */
    static String tvPath(String room, String key, String pair) {
        return "/ws/" + room + "?role=tv&key=" + key + (pair == null ? "" : "&pair=" + pair);
    }

    /** The page the QR code opens on the phone. */
    static String pairUrl(String base, String room, String pair) {
        return base + "/#" + room + "." + pair;
    }
}
