package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/** Opens the Wii menu after the TV finishes booting. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        final PendingResult pending = goAsync();
        final Handler handler = new Handler(Looper.getMainLooper());
        launch(app);
        // Amazon's own home screen also starts at boot, so come back on top once it has settled.
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                launch(app);
                pending.finish();
            }
        }, 7000L);
    }

    private static void launch(Context context) {
        try {
            Intent i = new Intent(context, WiiMenuActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (RuntimeException ignored) {
            // Nothing to do: Amazon's home screen simply stays.
        }
    }
}
