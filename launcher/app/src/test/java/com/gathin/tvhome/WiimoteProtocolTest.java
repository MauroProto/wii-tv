package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

public class WiimoteProtocolTest {

    @Test
    public void readsTheRelaysLines() {
        WiimoteProtocol.Message m = WiimoteProtocol.parse("m -12.5 3");
        assertEquals(WiimoteProtocol.MOVE, m.kind);
        assertEquals(-12.5f, m.dx, 0f);
        assertEquals(3f, m.dy, 0f);

        m = WiimoteProtocol.parse("b a 1");
        assertEquals(WiimoteProtocol.BUTTON, m.kind);
        assertEquals("a", m.button);
        assertTrue(m.down);
        m = WiimoteProtocol.parse("b home 0");
        assertEquals(WiimoteProtocol.HOME, m.button);
        assertFalse(m.down);

        m = WiimoteProtocol.parse("t 1 hola, ¿qué tal?  ");
        assertEquals(WiimoteProtocol.TEXT, m.kind);
        assertEquals("hola, ¿qué tal?  ", m.text);
        assertTrue(m.enter);
        m = WiimoteProtocol.parse("t 1");
        assertEquals("", m.text);
        assertTrue(m.enter);
        m = WiimoteProtocol.parse("t 0 ");
        assertEquals("", m.text);
        assertFalse(m.enter);

        m = WiimoteProtocol.parse("p 2");
        assertEquals(WiimoteProtocol.PHONES, m.kind);
        assertEquals(2, m.phones);
    }

    @Test
    public void ignoresAnythingElse() {
        String[] bad = {null, "", "m", "m ", "m 1", "m 1 x", "m NaN 1", "m 1 Infinity", "mm 1 2", "b a", "b a 2",
                "b z 1", "b  1", "t 2 x", "t 1x", "p", "p -1", "p x", "pong", "x 1 2"};
        for (String line : bad) assertNull(line, WiimoteProtocol.parse(line));
    }

    @Test
    public void everyButtonPressesAKey() {
        assertEquals(WiimoteProtocol.BUTTONS.length, WiimoteProtocol.KEYS.length);
        for (int i = 0; i < WiimoteProtocol.BUTTONS.length; i++) {
            assertEquals(i, WiimoteProtocol.index(WiimoteProtocol.BUTTONS[i]));
        }
        assertEquals(-1, WiimoteProtocol.index(WiimoteProtocol.HOME));
        assertEquals(-1, WiimoteProtocol.index("A"));
    }

    @Test
    public void codesAndKeysHaveTheRelaysShape() {
        Random random = new Random(7);
        for (int i = 0; i < 50; i++) {
            assertTrue(WiimoteProtocol.code(random).matches("[a-z2-7]{16}"));
            assertTrue(WiimoteProtocol.key(random).matches("[0-9a-f]{32}"));
        }
    }

    @Test
    public void addresses() {
        assertEquals("https://wiimote.example.com", WiimoteProtocol.base(" https://wiimote.example.com// "));
        assertEquals("http://10.0.2.2:8799", WiimoteProtocol.base("http://10.0.2.2:8799"));
        assertEquals("", WiimoteProtocol.base(""));
        assertEquals("", WiimoteProtocol.base(null));
        assertEquals("", WiimoteProtocol.base("wiimote.example.com"));
        assertEquals("", WiimoteProtocol.base("ftp://wiimote.example.com"));
        assertEquals("/ws/room?role=tv&key=k", WiimoteProtocol.tvPath("room", "k", null));
        assertEquals("/ws/room?role=tv&key=k&pair=p", WiimoteProtocol.tvPath("room", "k", "p"));
        assertEquals("https://w.example/#room.pair", WiimoteProtocol.pairUrl("https://w.example", "room", "pair"));
    }
}
