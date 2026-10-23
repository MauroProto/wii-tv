package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.SystemClock;

/**
 * The channel menu's interface sounds (res/raw/wii_*.ogg).
 * They play on the media stream, so the remote's volume keys set how loud they are.
 */
final class WiiSounds {
    static final int MOVE = 0;
    static final int SELECT = 1;
    static final int PAGE = 2;
    static final int BACK = 3;
    static final int MENU = 4;
    static final int START = 5;
    static final int BIG_BUTTON = 6;
    static final int WOOP = 7;

    /** By sound id. The Mii panels and the big buttons make the same sound, so they share one decoded copy. */
    private static final int[] RES = {
            R.raw.wii_move, R.raw.wii_select, R.raw.wii_page, R.raw.wii_back,
            R.raw.wii_big_buttons, R.raw.wii_start, R.raw.wii_big_buttons, R.raw.wii_woop,
    };
    private static final int[] ALL = {MOVE, SELECT, PAGE, BACK, MENU, START, BIG_BUTTON, WOOP};
    private static final float VOLUME = 0.6f;

    private final SoundPool pool;
    private final int[] ids = new int[RES.length];
    private final boolean enabled;
    private final boolean[] muted = new boolean[RES.length];
    private volatile long lastPlayed;

    WiiSounds(Context context, boolean enabled) {
        this(context, enabled, ALL);
    }

    /** Loads only the sounds a screen plays: each one stays decoded in memory while the screen lives. */
    WiiSounds(Context context, boolean enabled, int... used) {
        this.enabled = enabled;
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        pool = new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build();
        if (!enabled) return;
        for (int which : used) {
            if (ids[which] != 0) continue;
            for (int other = 0; other < RES.length && ids[which] == 0; other++) {
                if (RES[other] == RES[which]) ids[which] = ids[other];
            }
            if (ids[which] == 0) ids[which] = pool.load(context, RES[which], 1);
        }
    }

    void play(int which) {
        if (!enabled || ids[which] == 0 || muted[which]) return;
        pool.play(ids[which], VOLUME, VOLUME, 1, 0, 1f);
        lastPlayed = SystemClock.uptimeMillis();
    }

    /** Leaves one sound out, e.g. the start sound while the menu music brings its own. */
    void mute(int which, boolean on) {
        muted[which] = on;
    }

    /** When the last sound started, in uptime milliseconds; 0 before the first. */
    long lastPlayedMs() {
        return lastPlayed;
    }

    void release() {
        pool.release();
    }
}
