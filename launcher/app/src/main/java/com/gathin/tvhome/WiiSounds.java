package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.SystemClock;
import android.util.Log;

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

    private static final float VOLUME = 0.6f;

    private final SoundPool pool;
    private final int[] ids = new int[8];
    private final boolean enabled;
    private final boolean[] muted = new boolean[8];
    private volatile long lastPlayed;

    WiiSounds(Context context, boolean enabled) {
        this.enabled = enabled;
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        pool = new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build();
        if (!enabled) return;
        ids[MOVE] = pool.load(context, R.raw.wii_move, 1);
        ids[SELECT] = pool.load(context, R.raw.wii_select, 1);
        ids[PAGE] = pool.load(context, R.raw.wii_page, 1);
        ids[BACK] = pool.load(context, R.raw.wii_back, 1);
        ids[MENU] = pool.load(context, R.raw.wii_menu, 1);
        ids[START] = pool.load(context, R.raw.wii_start, 1);
        ids[BIG_BUTTON] = pool.load(context, R.raw.wii_big_buttons, 1);
        ids[WOOP] = pool.load(context, R.raw.wii_woop, 1);
    }

    void play(int which) {
        if (!enabled || ids[which] == 0 || muted[which]) return;
        int stream = pool.play(ids[which], VOLUME, VOLUME, 1, 0, 1f);
        lastPlayed = SystemClock.uptimeMillis();
        if (stream != 0) Log.d("WiiSounds", "played=" + which + " uptime=" + lastPlayed);
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
