package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.Toast;

/** The Games channel, opened from its tile in the channel menu; its games start in RetroArch. */
public class GamesActivity extends Activity implements GamesView.Host {

    private GamesView view;
    private WiiSounds sounds;
    /** A phone as the remote: its buttons press the remote's keys here. */
    private Wiimote wiimote;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sounds = new WiiSounds(this, getResources().getBoolean(R.bool.wii_sounds),
                WiiSounds.MOVE, WiiSounds.SELECT, WiiSounds.BACK, WiiSounds.START);
        view = new GamesView(this, this, sounds);
        setContentView(view);
        view.requestFocus();
        wiimote = new Wiimote(this, null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Read again on every visit: tools/games/add.py changes the list while the channel may be open.
        view.setGames(GameList.load(this), RetroArch.installed(getPackageManager()) != null,
                RetroArch.lastPlayed(this));
        view.resume();
        wiimote.resume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        view.pause();
        wiimote.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sounds.release();
        wiimote.release();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (view.keyDown(keyCode, event.getRepeatCount())) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        // Back arrives through onKeyDown; this only catches a system back the view never saw.
        view.keyDown(KeyEvent.KEYCODE_BACK, 0);
    }

    @Override
    public boolean play(GameList.Game game) {
        String pkg = RetroArch.installed(getPackageManager());
        if (pkg == null) return fail();
        try {
            RetroArch.close(this, pkg);
            final Intent intent = RetroArch.play(this, pkg, game);
            startActivity(intent);
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            RetroArch.remember(this, game.rom);
            return true;
        } catch (PackageManager.NameNotFoundException | ActivityNotFoundException | SecurityException e) {
            return fail();
        }
    }

    @Override
    public boolean openRetroArch() {
        String pkg = RetroArch.installed(getPackageManager());
        Intent intent = pkg == null ? null : RetroArch.menu(this, pkg);
        if (intent == null) return fail();
        try {
            RetroArch.close(this, pkg);
            RetroArch.forget(this);
            startActivity(intent);
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return fail();
        }
    }

    @Override
    public void exit() {
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private boolean fail() {
        Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        return false;
    }
}
