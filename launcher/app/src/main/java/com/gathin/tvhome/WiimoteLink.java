package com.gathin.tvhome;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * The TV's WebSocket to the Wiimote relay, kept up between start and stop: it connects again after a drop, a little
 * later each time. Lines arrive on the main thread; sending never blocks it. Plain Java sockets, so the launcher needs
 * no HTTP library.
 */
final class WiimoteLink {

    interface Listener {
        void onLine(String line);
        void onLink(boolean up);
    }

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    /** The relay answers every ping, so a minute without a word means the connection died without closing. */
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final long PING_MS = 25_000L;
    private static final long[] RETRY_MS = {1_000L, 2_000L, 4_000L, 8_000L, 15_000L};
    /** The relay closes with this when another screen of the TV connected in its place. */
    private static final int REPLACED = 4000;
    private static final int MAX_MESSAGE = 64 * 1024;

    private final String host, hostHeader, path;
    private final int port;
    private final boolean tls;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();
    private final HandlerThread outThread = new HandlerThread("wiimote-out");
    /** Every write, and closing the socket, happen here: a TLS socket must not be used on the main thread. */
    private final Handler out;
    /** Bumped by start and stop: a connection of an older generation lets go and tells no one. */
    private volatile int gen;
    private Socket socket;
    private OutputStream sink;

    WiimoteLink(String base, String pathAndQuery, Listener listener) {
        URI uri = URI.create(base);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        tls = scheme.equals("https");
        host = uri.getHost();
        port = uri.getPort() > 0 ? uri.getPort() : tls ? 443 : 80;
        hostHeader = uri.getPort() > 0 ? host + ":" + uri.getPort() : host;
        String prefix = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (prefix.endsWith("/")) prefix = prefix.substring(0, prefix.length() - 1);
        path = prefix + pathAndQuery;
        this.listener = listener;
        outThread.start();
        out = new Handler(outThread.getLooper());
    }

    void start() {
        final int g = ++gen;
        Thread t = new Thread(() -> run(g), "wiimote-in");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        gen++;
        out.removeCallbacksAndMessages(null);
        final Socket s;
        synchronized (this) {
            s = socket;
            socket = null;
            sink = null;
        }
        if (s != null) out.post(() -> close(s));
    }

    void release() {
        stop();
        outThread.quitSafely();
    }

    void send(String text) {
        final Socket s;
        final OutputStream o;
        synchronized (this) {
            s = socket;
            o = sink;
        }
        if (o == null) return;
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.post(() -> write(s, o, WsFrames.TEXT, bytes));
    }

    private void run(int g) {
        int fails = 0;
        Boolean told = null;
        while (g == gen) {
            Socket s = null;
            int code = 0;
            try {
                s = open();
                InputStream in = handshake(s);
                synchronized (this) {
                    if (g != gen) return;
                    socket = s;
                    sink = s.getOutputStream();
                }
                fails = 0;
                told = Boolean.TRUE;
                tell(g, true);
                final Socket live = s;
                out.postDelayed(() -> ping(live), PING_MS);
                code = read(g, s, in);
            } catch (IOException | RuntimeException e) {
                // Connects again below.
            } finally {
                synchronized (this) {
                    if (socket == s) {
                        socket = null;
                        sink = null;
                    }
                }
                if (s != null) close(s);
            }
            if (g != gen) return;
            if (!Boolean.FALSE.equals(told)) {
                told = Boolean.FALSE;
                tell(g, false);
            }
            // Another screen took over: it is the one in front now, so this one waits longer before trying again.
            if (code == REPLACED) fails = Math.max(fails, 3);
            long until = System.currentTimeMillis() + RETRY_MS[Math.min(fails++, RETRY_MS.length - 1)];
            while (g == gen && System.currentTimeMillis() < until) {
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private Socket open() throws IOException {
        Socket raw = new Socket();
        try {
            raw.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            raw.setTcpNoDelay(true);
            raw.setSoTimeout(CONNECT_TIMEOUT_MS);
            if (!tls) return raw;
            SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(raw, host, port, true);
            ssl.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.getSession())) {
                throw new SSLPeerUnverifiedException("not " + host);
            }
            return ssl;
        } catch (IOException | RuntimeException e) {
            close(raw);
            throw e;
        }
    }

    private InputStream handshake(Socket s) throws IOException {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String key = Base64.getEncoder().encodeToString(nonce);
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + hostHeader + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "User-Agent: wiiOS\r\n\r\n";
        OutputStream o = s.getOutputStream();
        o.write(request.getBytes(StandardCharsets.US_ASCII));
        o.flush();
        InputStream in = new BufferedInputStream(s.getInputStream());
        String status = line(in);
        if (!status.startsWith("HTTP/1.1 101")) throw new IOException(status);
        String accept = null;
        for (String h = line(in); !h.isEmpty(); h = line(in)) {
            int colon = h.indexOf(':');
            if (colon > 0 && h.substring(0, colon).trim().equalsIgnoreCase("Sec-WebSocket-Accept")) {
                accept = h.substring(colon + 1).trim();
            }
        }
        if (!WsFrames.accept(key).equals(accept)) throw new IOException("not a WebSocket answer");
        s.setSoTimeout(READ_TIMEOUT_MS);
        return in;
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        while (true) {
            int c = in.read();
            if (c < 0) throw new EOFException();
            if (c == '\n') return b.toString();
            if (c != '\r') b.append((char) c);
            if (b.length() > 8192) throw new IOException("header too long");
        }
    }

    /** Reads until the relay closes; its close code, or 0 when this generation ended first. */
    private int read(int g, Socket s, InputStream in) throws IOException {
        ByteArrayOutputStream parts = null;
        OutputStream o = s.getOutputStream();
        while (g == gen) {
            WsFrames.Frame f = WsFrames.read(in, MAX_MESSAGE);
            switch (f.opcode) {
                case WsFrames.TEXT:
                    if (f.fin) {
                        deliver(g, f.payload);
                    } else {
                        parts = new ByteArrayOutputStream();
                        parts.write(f.payload);
                    }
                    break;
                case WsFrames.CONTINUATION:
                    if (parts == null) break;
                    parts.write(f.payload);
                    if (parts.size() > MAX_MESSAGE) throw new IOException("message too long");
                    if (f.fin) {
                        deliver(g, parts.toByteArray());
                        parts = null;
                    }
                    break;
                case WsFrames.PING: {
                    byte[] p = f.payload;
                    out.post(() -> write(s, o, WsFrames.PONG, p));
                    break;
                }
                case WsFrames.CLOSE:
                    return WsFrames.closeCode(f.payload);
                default:
                    break;
            }
        }
        return 0;
    }

    private void deliver(int g, byte[] payload) {
        String text = new String(payload, StandardCharsets.UTF_8);
        if (text.equals("pong")) return;
        main.post(() -> {
            if (g == gen) listener.onLine(text);
        });
    }

    private void tell(int g, boolean up) {
        main.post(() -> {
            if (g == gen) listener.onLink(up);
        });
    }

    /** Keeps this connection's pings going while it is the one up; each new connection starts its own. */
    private void ping(Socket s) {
        final OutputStream o;
        synchronized (this) {
            o = socket == s ? sink : null;
        }
        if (o == null) return;
        write(s, o, WsFrames.TEXT, "ping".getBytes(StandardCharsets.US_ASCII));
        out.postDelayed(() -> ping(s), PING_MS);
    }

    private void write(Socket s, OutputStream o, int opcode, byte[] payload) {
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        try {
            o.write(WsFrames.encode(opcode, payload, mask));
            o.flush();
        } catch (IOException e) {
            // The reader notices the closed socket and connects again.
            close(s);
        }
    }

    private static void close(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
