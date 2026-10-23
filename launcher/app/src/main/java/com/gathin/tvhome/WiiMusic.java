package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** Local music, confined to the screen that owns it; no background service. */
final class WiiMusic {
    private static final String TAG = "WiiMusic";
    private final float volume;
    private final Context context;
    private final boolean enabled;
    private final boolean waitForOtherAudio;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AudioManager manager;
    private final AudioAttributes attributes;
    private final AudioFocusRequest focus;
    private MediaPlayer player, jingle;
    private int resource;
    private boolean failed;
    private final Runnable unavailable;
    private boolean resumed, focused, waiting, ducked;
    private final Runnable beginMusic = () -> {
        waiting = false;
        if (resumed && focused) play();
    };

    WiiMusic(Context context, boolean enabled, int volumePercent) {
        this(context, enabled, volumePercent, () -> {}, true);
    }

    WiiMusic(Context context, boolean enabled, int volumePercent, Runnable unavailable) {
        this(context, enabled, volumePercent, unavailable, false);
    }

    private WiiMusic(Context context, boolean enabled, int volumePercent, Runnable unavailable,
                     boolean waitForOtherAudio) {
        // MiiAudio keeps its own silent AudioTrack for effects; only Home waits for other playback.
        this.waitForOtherAudio = waitForOtherAudio;
        this.volume = Math.max(0, Math.min(100, volumePercent)) / 100f;
        this.unavailable = unavailable;
        this.context = context.getApplicationContext();
        this.enabled = enabled;
        manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(this::focusChanged, handler).build();
    }

    void setTrack(int resource) {
        if (resource == this.resource && !failed) return;
        if (player != null) player.release();
        player = null;
        this.resource = resource;
        failed = false;
        if (resumed && focused && !waiting) play();
    }

    boolean hasTrack() {
        return !failed && resource != 0;
    }

    private final Runnable waitForQuiet = new Runnable() {
        @Override public void run() {
            if (!resumed || waiting) return;
            if (waitForOtherAudio && !focused && manager != null && manager.isMusicActive()) {
                handler.postDelayed(this, 500L);
            } else if (requestFocus()) {
                play();
            }
        }
    };

    void resume() {
        if (!enabled || volume <= 0 || resumed) return;
        resumed = true;
        waitForQuiet.run();
    }

    /** The delay is measured from starting the jingle, not from loading the music. */
    void startWithJingle(int jingleResource, long delayMs) {
        if (!enabled || volume <= 0) return;
        resumed = true;
        cancelEntrance();
        pausePlayer(player);
        if (waitForOtherAudio && !focused && manager != null && manager.isMusicActive()) {
            waitForQuiet.run();
            return;
        }
        waiting = true;
        if (!requestFocus()) {
            waiting = false;
            return;
        }
        // Prepare the loop before the jingle so decoding does not add to the requested delay.
        prepareMusic();
        if (player != null) player.seekTo(0);
        try {
            jingle = jingleResource == 0 ? null : MediaPlayer.create(context, jingleResource, attributes, 0);
            if (jingle != null) {
                jingle.setVolume(0.6f, 0.6f);
                jingle.setOnCompletionListener(done -> {
                    if (jingle == done) jingle = null;
                    done.release();
                });
                jingle.setOnErrorListener((failed, what, extra) -> {
                    if (jingle == failed) jingle = null;
                    failed.release();
                    return true;
                });
                jingle.start();
            }
        } catch (RuntimeException error) {
            if (jingle != null) jingle.release();
            jingle = null;
            Log.w(TAG, "Cannot play entrance", error);
        }
        handler.postDelayed(beginMusic, delayMs);
    }

    void pause() {
        resumed = false;
        cancelEntrance();
        pausePlayer(player);
        if (focused) manager.abandonAudioFocusRequest(focus);
        focused = false;
    }

    void release() {
        pause();
        if (player != null) player.release();
        player = null;
    }

    private boolean requestFocus() {
        if (!focused && manager != null) {
            focused = manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if (focused) {
                ducked = false;
                volume();
            }
        }
        return focused;
    }

    private void focusChanged(int change) {
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            if (!resumed) {
                manager.abandonAudioFocusRequest(focus);
                focused = false;
                return;
            }
            focused = true;
            ducked = false;
            volume();
            if (resumed && !waiting) play();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            ducked = true;
            volume();
        } else {
            focused = false;
            cancelEntrance();
            pausePlayer(player);
        }
    }

    private void prepareMusic() {
        if (player != null || !hasTrack()) return;
        try {
            player = MediaPlayer.create(context, resource, attributes, 0);
            if (player == null) {
                failed = true;
                unavailable.run();
                return;
            }
            player.setLooping(true);
            player.setOnErrorListener((failed, what, extra) -> {
                if (player == failed) player = null;
                failed.release();
                this.failed = true;
                unavailable.run();
                return true;
            });
            volume();
        } catch (RuntimeException error) {
            if (player != null) player.release();
            player = null;
            failed = true;
            unavailable.run();
            Log.w(TAG, "Cannot load music", error);
        }
    }

    private void play() {
        prepareMusic();
        if (player == null) return;
        try {
            if (!player.isPlaying()) player.start();
        } catch (RuntimeException error) {
            player.release();
            player = null;
            failed = true;
            unavailable.run();
            Log.w(TAG, "Cannot start music", error);
        }
    }

    private void volume() {
        float gain = ducked ? volume * 0.2f : volume;
        if (player != null) player.setVolume(gain, gain);
        if (jingle != null) jingle.setVolume(ducked ? 0.12f : 0.6f, ducked ? 0.12f : 0.6f);
    }

    private void cancelEntrance() {
        handler.removeCallbacks(beginMusic);
        handler.removeCallbacks(waitForQuiet);
        waiting = false;
        if (jingle != null) jingle.release();
        jingle = null;
    }

    private static void pausePlayer(MediaPlayer player) {
        if (player == null) return;
        try {
            if (player.isPlaying()) player.pause();
        } catch (IllegalStateException ignored) {
            // A decoder error can arrive at the same time as the activity pauses.
        }
    }
}
