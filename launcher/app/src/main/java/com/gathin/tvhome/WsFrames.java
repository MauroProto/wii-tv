package com.gathin.tvhome;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** WebSocket frames (RFC 6455) as a client writes and reads them: what WiimoteLink needs, and no more. */
final class WsFrames {
    static final int CONTINUATION = 0, TEXT = 1, BINARY = 2, CLOSE = 8, PING = 9, PONG = 10;
    /** The close code a frame without one stands for. */
    static final int NO_CODE = 1005;
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private WsFrames() { }

    static final class Frame {
        final boolean fin;
        final int opcode;
        final byte[] payload;

        Frame(boolean fin, int opcode, byte[] payload) {
            this.fin = fin;
            this.opcode = opcode;
            this.payload = payload;
        }
    }

    /** One whole frame from the client; a client always masks what it sends. */
    static byte[] encode(int opcode, byte[] payload, byte[] mask) {
        int n = payload.length;
        int at = n < 126 ? 2 : n < 65536 ? 4 : 10;
        byte[] out = new byte[at + 4 + n];
        out[0] = (byte) (0x80 | opcode);
        if (n < 126) {
            out[1] = (byte) (0x80 | n);
        } else if (n < 65536) {
            out[1] = (byte) (0x80 | 126);
            out[2] = (byte) (n >>> 8);
            out[3] = (byte) n;
        } else {
            out[1] = (byte) (0x80 | 127);
            for (int k = 0; k < 8; k++) out[2 + k] = (byte) ((long) n >>> (56 - 8 * k));
        }
        System.arraycopy(mask, 0, out, at, 4);
        for (int k = 0; k < n; k++) out[at + 4 + k] = (byte) (payload[k] ^ mask[k & 3]);
        return out;
    }

    /** The next frame from the server, longer than max refused; unmasked, should the server have masked it. */
    static Frame read(InputStream in, int max) throws IOException {
        int b0 = next(in), b1 = next(in);
        long n = b1 & 0x7F;
        if (n == 126) {
            n = (next(in) << 8) | next(in);
        } else if (n == 127) {
            n = 0;
            for (int k = 0; k < 8; k++) n = (n << 8) | next(in);
        }
        if (n < 0 || n > max) throw new IOException("frame of " + n + " bytes");
        byte[] mask = null;
        if ((b1 & 0x80) != 0) {
            mask = new byte[4];
            fill(in, mask);
        }
        byte[] payload = new byte[(int) n];
        fill(in, payload);
        if (mask != null) for (int k = 0; k < payload.length; k++) payload[k] ^= mask[k & 3];
        return new Frame((b0 & 0x80) != 0, b0 & 0x0F, payload);
    }

    /** The Sec-WebSocket-Accept a server answers to this Sec-WebSocket-Key. */
    static String accept(String key) {
        try {
            byte[] sha = MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(sha);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The code in a close frame's payload. */
    static int closeCode(byte[] payload) {
        return payload.length >= 2 ? ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF) : NO_CODE;
    }

    private static int next(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException();
        return b;
    }

    private static void fill(InputStream in, byte[] into) throws IOException {
        int at = 0;
        while (at < into.length) {
            int got = in.read(into, at, into.length - at);
            if (got < 0) throw new EOFException();
            at += got;
        }
    }
}
