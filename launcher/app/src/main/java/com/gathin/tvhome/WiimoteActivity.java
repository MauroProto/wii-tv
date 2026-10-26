package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import java.security.SecureRandom;

/**
 * The Wiimote channel: the QR code that turns a phone into the remote. Its pairing code is new each time the
 * channel opens and works only while it is open; once a phone is in, the menu comes back with the hand following it.
 */
public class WiimoteActivity extends Activity implements Wiimote.Pointer {

    /** Long enough to see the light come on, as on a remote that synced, before the menu comes back. */
    private static final long BACK_TO_MENU_MS = 1500L;

    private WiimoteView view;
    private Wiimote wiimote;
    private WiiSounds sounds;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String pair = WiimoteProtocol.code(new SecureRandom());
        view = new WiimoteView(this, Wiimote.pairUrl(this, pair));
        setContentView(view);
        wiimote = new Wiimote(this, this, pair);
        sounds = new WiiSounds(this, getResources().getBoolean(R.bool.wii_sounds), WiiSounds.SELECT, WiiSounds.BACK);
    }

    @Override
    protected void onResume() {
        super.onResume();
        wiimote.resume();
        view.resume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacksAndMessages(null);
        wiimote.pause();
        view.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        wiimote.release();
        sounds.release();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.getRepeatCount() == 0) {
                sounds.play(WiiSounds.BACK);
                leave();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        // Back arrives through onKeyDown; this only catches a system back that never came through it.
        leave();
    }

    private void leave() {
        if (isFinishing()) return;
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    public void onWiimoteMove(float dx, float dy) {
        view.nudge(dx);
    }

    @Override
    public void onWiimotePhones(int count, int before) {
        boolean joined = before >= 0 && count > before;
        view.setPhones(count, joined);
        if (!joined) return;
        sounds.play(WiiSounds.SELECT);
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::leave, BACK_TO_MENU_MS);
    }

    @Override
    public void onWiimoteLink(boolean up) {
        view.setOnline(up);
    }
}
