package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Your own menu music, from files tools/music/add.py copies to the TV; it plays instead of the bundled track.
 * music/menu.wav plays from the top and then repeats its loop: the one in its smpl chunk, or else the whole file.
 * music/menu_start.wav plays when the menu appears after the welcome screen, and the music comes in a second
 * later, as with the bundled track. Both are
 * 16-bit PCM, streamed from the file a block at a time: no decoder, and a few kilobytes of memory.
 */
final class MenuMusic {
    static final String DIR = "music";
    static final String LOOP = "menu.wav";
    static final String START = "menu_start.wav";

    private static final String TAG = "MenuMusic";
    private static final int BLOCK = 2048;
    private static final float FADE_OUT_S = 0.4f;
    /** Back from an app, its sound can take a moment to stop; until then it would count as music playing. */
    private static final long SETTLE_MS = 800;
    /** The menu's own sounds also count as music playing, so wait for them to end before looking. */
    private static final long SOUND_MS = 1200;

    private final Context context;
    private final boolean enabled;
    private final float gain;
    private final WiiSounds sounds;
    private Player player;

    MenuMusic(Context context, boolean enabled, int volumePercent, WiiSounds sounds) {
        this.context = context.getApplicationContext();
        this.enabled = enabled;
        this.gain = Math.max(0, Math.min(200, volumePercent)) / 100f;
        this.sounds = sounds;
    }

    /** True when your own music is on the TV; it then plays instead of the bundled track. */
    boolean hasMusic() {
        File loop = file(LOOP);
        return enabled && gain > 0f && loop != null && loop.isFile();
    }

    /** True when the music comes with its start jingle, which then takes the place of the menu's start sound. */
    boolean hasStart() {
        File loop = file(LOOP), start = file(START);
        return enabled && loop != null && loop.isFile() && start != null && start.isFile();
    }

    /**
     * Plays the music from the top, with the start jingle over it when withStart. It waits while another app
     * plays sound (music left on in the background), and starts once that stops.
     */
    synchronized void play(boolean withStart) {
        if (player != null) player.kill();
        player = null;
        File loop = file(LOOP);
        if (!enabled || gain <= 0f || loop == null || !loop.isFile()) return;
        player = new Player(loop, withStart ? file(START) : null, withStart ? 0 : SETTLE_MS);
        new Thread(player, "menu-music").start();
    }

    /** Fades the music out. play() starts it again from the top. */
    synchronized void stop() {
        if (player != null) player.fadeOut();
        player = null;
    }

    private File file(String name) {
        File dir = Config.dir(context);
        return dir == null ? null : new File(new File(dir, DIR), name);
    }

    private final class Player implements Runnable {
        private final File loopFile;
        private final File startFile;
        private final long notBefore;
        private volatile boolean alive = true;
        private volatile boolean fading;

        Player(File loopFile, File startFile, long delayMs) {
            this.loopFile = loopFile;
            this.startFile = startFile;
            notBefore = SystemClock.uptimeMillis() + delayMs;
        }

        void kill() {
            alive = false;
        }

        void fadeOut() {
            fading = true;
        }

        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            AudioTrack track = null;
            RandomAccessFile in = null, jin = null;
            try {
                if (!waitForQuiet()) return;
                Wav wav = Wav.read(loopFile);
                if (wav == null) {
                    Log.w(TAG, loopFile + " is not a 16-bit PCM WAV file");
                    return;
                }
                Wav jingle = startFile != null && startFile.isFile() ? Wav.read(startFile) : null;
                if (jingle != null && (jingle.rate != wav.rate || jingle.channels != wav.channels)) {
                    Log.w(TAG, startFile + " has another sample rate or channel count than " + loopFile);
                    jingle = null;
                }
                track = newTrack(wav);
                if (track == null) return;
                int ch = wav.channels;
                in = new RandomAccessFile(loopFile, "r");
                in.seek(wav.dataOffset);
                if (jingle != null) {
                    jin = new RandomAccessFile(startFile, "r");
                    jin.seek(jingle.dataOffset);
                }
                byte[] raw = new byte[BLOCK * ch * 2];
                byte[] jraw = new byte[BLOCK * ch * 2];
                short[] out = new short[BLOCK * ch];
                long pos = 0, jpos = 0;
                long leadIn = jingle == null ? 0 : wav.rate;
                float fade = 1f, step = 1f / (FADE_OUT_S * wav.rate);
                track.play();
                boolean done = false;
                while (alive && !done) {
                    int got = 0;
                    while (got < BLOCK) {
                        if (leadIn > 0) {
                            int n = (int) Math.min(BLOCK - got, leadIn);
                            java.util.Arrays.fill(raw, got * ch * 2, (got + n) * ch * 2, (byte) 0);
                            got += n;
                            leadIn -= n;
                            continue;
                        }
                        if (pos >= wav.loopEnd) {
                            pos = wav.loopStart;
                            in.seek(wav.dataOffset + pos * ch * 2);
                        }
                        int n = (int) Math.min(BLOCK - got, wav.loopEnd - pos);
                        in.readFully(raw, got * ch * 2, n * ch * 2);
                        got += n;
                        pos += n;
                    }
                    int jn = 0;
                    if (jingle != null && jpos < jingle.frames) {
                        jn = (int) Math.min(BLOCK, jingle.frames - jpos);
                        jin.readFully(jraw, 0, jn * ch * 2);
                        jpos += jn;
                    }
                    for (int f = 0; f < BLOCK; f++) {
                        if (fading && !done) {
                            fade -= step;
                            if (fade <= 0f) {
                                fade = 0f;
                                done = true;
                            }
                        }
                        for (int c = 0; c < ch; c++) {
                            int i = f * ch + c;
                            int v = sample(raw, i);
                            if (f < jn) v += sample(jraw, i);
                            out[i] = (short) Math.max(-32768, Math.min(32767, Math.round(v * gain * fade)));
                        }
                    }
                    if (track.write(out, 0, out.length) < 0) break;
                }
                if (!alive) {
                    track.pause();
                    track.flush();
                }
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "the menu music stopped", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (track != null) {
                    try {
                        track.stop();
                    } catch (IllegalStateException ignored) {
                        // Never started.
                    }
                    track.release();
                }
                close(in);
                close(jin);
            }
        }

        /** Waits until nothing else is playing; false when the music was stopped meanwhile. */
        private boolean waitForQuiet() throws InterruptedException {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            while (alive && !fading) {
                long now = SystemClock.uptimeMillis();
                long ready = Math.max(notBefore, sounds == null ? 0 : sounds.lastPlayedMs() + SOUND_MS);
                if (now < ready) {
                    Thread.sleep(Math.min(100, ready - now));
                } else if (am == null || !am.isMusicActive()) {
                    return true;
                } else {
                    Thread.sleep(500);
                }
            }
            return false;
        }
    }

    private static AudioTrack newTrack(Wav wav) {
        int mask = wav.channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int min = AudioTrack.getMinBufferSize(wav.rate, mask, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(wav.rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(mask)
                        .build())
                // Four blocks, about 0.2 s: enough that a slow read on a busy TV does not break up the sound.
                .setBufferSizeInBytes(Math.max(min, BLOCK * wav.channels * 2 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        if (t.getState() != AudioTrack.STATE_INITIALIZED) {
            t.release();
            return null;
        }
        return t;
    }

    private static int sample(byte[] b, int i) {
        return (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8));
    }

    private static void close(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
            // Read-only.
        }
    }

    /** Where a 16-bit PCM WAV file keeps its samples, and which of them loop (in frames, end exclusive). */
    static final class Wav {
        final int rate;
        final int channels;
        final long dataOffset;
        final long frames;
        final long loopStart;
        final long loopEnd;

        private Wav(int rate, int channels, long dataOffset, long frames, long loopStart, long loopEnd) {
            this.rate = rate;
            this.channels = channels;
            this.dataOffset = dataOffset;
            this.frames = frames;
            this.loopStart = loopStart;
            this.loopEnd = loopEnd;
        }

        /** The file's layout, or null when it is not mono or stereo 16-bit PCM with samples in it. */
        static Wav read(File file) throws IOException {
            try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
                long len = f.length();
                byte[] head = new byte[12];
                if (len < head.length) return null;
                f.readFully(head);
                if (!tag(head, 0, "RIFF") || !tag(head, 8, "WAVE")) return null;
                int rate = 0, channels = 0;
                boolean pcm16 = false;
                long dataOffset = -1, dataSize = 0, loopStart = -1, loopEnd = -1;
                byte[] chunk = new byte[8];
                long at = head.length;
                while (at + chunk.length <= len) {
                    f.seek(at);
                    f.readFully(chunk);
                    long size = u32(chunk, 4), body = at + chunk.length;
                    if (tag(chunk, 0, "fmt ") && size >= 16) {
                        byte[] b = new byte[16];
                        f.readFully(b);
                        channels = u16(b, 2);
                        rate = (int) u32(b, 4);
                        pcm16 = u16(b, 0) == 1 && u16(b, 14) == 16 && (channels == 1 || channels == 2)
                                && rate >= 8000 && rate <= 96000;
                    } else if (tag(chunk, 0, "smpl") && size >= 60) {
                        byte[] b = new byte[60];
                        f.readFully(b);
                        // The first loop of the list; the format counts its end sample as part of it.
                        if (u32(b, 28) > 0) {
                            loopStart = u32(b, 44);
                            loopEnd = u32(b, 48) + 1;
                        }
                    } else if (tag(chunk, 0, "data")) {
                        dataOffset = body;
                        dataSize = Math.min(size, len - body);
                    }
                    at = body + size + (size & 1);
                }
                if (!pcm16 || dataOffset < 0) return null;
                long frames = dataSize / (2L * channels);
                if (frames == 0) return null;
                if (loopStart < 0 || loopStart >= loopEnd || loopEnd > frames) {
                    loopStart = 0;
                    loopEnd = frames;
                }
                return new Wav(rate, channels, dataOffset, frames, loopStart, loopEnd);
            }
        }

        private static boolean tag(byte[] b, int at, String id) {
            return new String(b, at, 4, StandardCharsets.US_ASCII).equals(id);
        }

        private static int u16(byte[] b, int at) {
            return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8;
        }

        private static long u32(byte[] b, int at) {
            return (b[at] & 0xffL) | (b[at + 1] & 0xffL) << 8 | (b[at + 2] & 0xffL) << 16 | (b[at + 3] & 0xffL) << 24;
        }
    }
}
