package com.gathin.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class WsFramesTest {

    private static final byte[] MASK = {0x37, (byte) 0xFA, 0x21, 0x3D};

    @Test
    public void acceptMatchesTheRfcExample() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WsFrames.accept("dGhlIHNhbXBsZSBub25jZQ=="));
    }

    @Test
    public void encodesTheRfcsMaskedHello() {
        byte[] hello = "Hello".getBytes(StandardCharsets.US_ASCII);
        assertArrayEquals(new byte[] {(byte) 0x81, (byte) 0x85, 0x37, (byte) 0xFA, 0x21, 0x3D, 0x7F, (byte) 0x9F,
                0x4D, 0x51, 0x58}, WsFrames.encode(WsFrames.TEXT, hello, MASK));
    }

    @Test
    public void readsBackWhatItWritesAtEveryLength() throws IOException {
        for (int n : new int[] {0, 1, 125, 126, 127, 65535, 65536, 70000}) {
            byte[] payload = new byte[n];
            for (int i = 0; i < n; i++) payload[i] = (byte) (i * 31);
            byte[] frame = WsFrames.encode(WsFrames.BINARY, payload, MASK);
            WsFrames.Frame f = WsFrames.read(new ByteArrayInputStream(frame), 1 << 20);
            assertTrue(f.fin);
            assertEquals(WsFrames.BINARY, f.opcode);
            assertArrayEquals(payload, f.payload);
        }
    }

    @Test
    public void readsTheServersUnmaskedFrames() throws IOException {
        WsFrames.Frame f = WsFrames.read(new ByteArrayInputStream(new byte[] {0x01, 0x03, 'H', 'e', 'l'}), 100);
        assertEquals(false, f.fin);
        assertEquals(WsFrames.TEXT, f.opcode);
        assertEquals("Hel", new String(f.payload, StandardCharsets.US_ASCII));
        f = WsFrames.read(new ByteArrayInputStream(new byte[] {(byte) 0x88, 0x02, 0x0F, (byte) 0xA0}), 100);
        assertEquals(WsFrames.CLOSE, f.opcode);
        assertEquals(4000, WsFrames.closeCode(f.payload));
        assertEquals(WsFrames.NO_CODE, WsFrames.closeCode(new byte[0]));
    }

    @Test
    public void refusesFramesTooLongOrCutShort() {
        try {
            WsFrames.read(new ByteArrayInputStream(new byte[] {(byte) 0x81, 126, 0x10, 0x00}), 1000);
            fail();
        } catch (IOException expected) {
            // 4096 bytes is over the limit.
        }
        try {
            WsFrames.read(new ByteArrayInputStream(new byte[] {(byte) 0x81, 0x05, 'H'}), 1000);
            fail();
        } catch (EOFException expected) {
            // Ended in the middle of the payload.
        } catch (IOException e) {
            fail(e.toString());
        }
    }
}
