package com.gathin.tvhome;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.provider.Settings;

import java.io.File;

/** Starts games in RetroArch, the emulator the Games channel hands them to. */
final class RetroArch {
    private RetroArch() {}

    /** The 64-bit builds first; 32-bit-only TVs (most of them) can only run com.retroarch.ra32. */
    static final String[] PACKAGES = {"com.retroarch.aarch64", "com.retroarch", "com.retroarch.ra32"};
    private static final String GAME_ACTIVITY = "com.retroarch.browser.retroactivity.RetroActivityFuture";
    private static final String PREFS = "games";
    /** The game last started, which the shelf opens on. */
    private static final String LAST_PLAYED = "last_played";

    /** The installed RetroArch package, or null. */
    static String installed(PackageManager pm) {
        for (String pkg : PACKAGES) {
            try {
                if (pm.getApplicationInfo(pkg, 0).enabled) return pkg;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return null;
    }

    /** Closes an open game / background RetroArch session so another game or menu can start clean. */
    static void close(Context ctx, String pkg) {
        if (pkg == null) return;
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            try {
                am.killBackgroundProcesses(pkg);
            } catch (SecurityException ignored) {
            }
        }
    }

    @SuppressWarnings("deprecation")
    static Intent play(Context ctx, String pkg, GameList.Game game) throws PackageManager.NameNotFoundException {
        ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(pkg, 0);
        File sdcard = Environment.getExternalStorageDirectory();
        File external = new File(sdcard, "Android/data/" + pkg + "/files");
        // The extras RetroArch's own menu passes when it starts a game; without CONFIGFILE it would ignore the
        // settings (and the remote's button map) saved in its external folder.
        // QUITFOCUS tells RetroArch to exit on losing focus, ensuring clean process restarts when switching games.
        return new Intent(Intent.ACTION_MAIN)
                .setClassName(pkg, GAME_ACTIVITY)
                .putExtra("ROM", game.rom)
                .putExtra("LIBRETRO", ai.dataDir + "/cores/" + game.core + "_libretro_android.so")
                .putExtra("CONFIGFILE", new File(external, "retroarch.cfg").getPath())
                .putExtra("IME", Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD))
                .putExtra("DATADIR", ai.dataDir)
                .putExtra("APK", ai.sourceDir)
                .putExtra("SDCARD", sdcard.getPath())
                .putExtra("EXTERNAL", external.getPath())
                .putExtra("QUITFOCUS", "true")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    static String lastPlayed(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LAST_PLAYED, null);
    }

    static void remember(Context ctx, String rom) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(LAST_PLAYED, rom).apply();
    }

    static void forget(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(LAST_PLAYED).apply();
    }

    /** RetroArch's own menu, for its settings. RetroArch closes a game it had open to show it. */
    static Intent menu(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        return i == null ? null : i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
