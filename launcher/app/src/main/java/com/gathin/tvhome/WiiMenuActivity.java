package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/** Home screen in the style of the old console menu; all of the drawing is in WiiMenuView. */
public class WiiMenuActivity extends Activity implements WiiMenuView.Listener {

    private static final String MEDIA_PLAYER = "com.mediatek.wwtv.mediaplayer";
    /** Debug: freeze the menu in the reference capture's state, for comparing screenshots against it. */
    static final String EXTRA_REF_POSE = "ref_pose";

    private WiiMenuView menu;
    private WiiSounds sounds;
    private WiiMusic music;
    private MenuMusic fileMusic;
    /** The welcome screen plays once per boot and whenever the screen comes back on, as at power-on. */
    private boolean introPending;
    private boolean appsDirty = true;
    private boolean resumed;
    private String liveTvPkg;
    private MiiStore miiStore;
    private int miiVersion = -1;
    private Typeface miiFace;
    private GamesBanner gamesBanner;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            music.pause();
            fileMusic.stop();
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) return;
            if (resumed) {
                introPending = false;
                menu.playIntro();
                return;
            }
            introPending = true;
            // Power-on always lands here, even where the firmware would return to the last app. Android
            // lets the current home app start activities from the background.
            // A HOME intent, so the system brings back its home task instead of starting a second copy.
            try {
                startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .setPackage(getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                // Not allowed on this build; the menu still shows the next time Home is pressed.
            }
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            appsDirty = true;
            if (resumed) reloadApps();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        menu = new WiiMenuView(this, this);
        menu.setIntroText(getString(R.string.wii_intro_title), getString(R.string.wii_intro_body),
                getString(R.string.wii_intro_continue));
        sounds = new WiiSounds(this, getResources().getBoolean(R.bool.wii_sounds));
        music = new WiiMusic(this, getResources().getBoolean(R.bool.wii_music),
                getResources().getInteger(R.integer.wii_music_volume));
        music.setTrack(R.raw.wii_menu_music);
        fileMusic = new MenuMusic(this, getResources().getBoolean(R.bool.wii_music),
                getResources().getInteger(R.integer.wii_music_volume), sounds);
        menu.setSounds(sounds);
        menu.setSettingsLabel(getString(R.string.wii_settings_label));
        setContentView(menu);
        menu.requestFocus();
        introPending = firstStartThisBoot() || getIntent().getBooleanExtra("show_intro", false);
        if (getIntent().getBooleanExtra(EXTRA_REF_POSE, false)) {
            introPending = false;
            menu.setRefPose(true);
        }
        liveTvPkg = getString(R.string.live_tv_package);
        miiStore = new MiiStore(this);

        IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_SCREEN_OFF);
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, screen, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(packageReceiver, packages, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, screen);
            registerReceiver(packageReceiver, packages);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (appsDirty || miiStore.version() != miiVersion) reloadApps();
        menu.start();
        if (introPending) {
            introPending = false;
            music.pause();
            menu.playIntro();
        } else if (!menu.isIntroShowing()) {
            playMenuMusic(false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        music.pause();
        fileMusic.stop();
        menu.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(screenReceiver);
        unregisterReceiver(packageReceiver);
        sounds.release();
        music.release();
        fileMusic.stop();
    }

    @Override
    public void onIntroDismissed() {
        if (resumed) playMenuMusic(true);
    }

    /** User-installed menu music takes precedence over the bundled track. */
    private void playMenuMusic(boolean withStart) {
        if (!getResources().getBoolean(R.bool.wii_music)
                || getResources().getInteger(R.integer.wii_music_volume) <= 0) {
            if (withStart) sounds.play(WiiSounds.START);
            return;
        }
        if (fileMusic.hasMusic()) {
            music.pause();
            if (withStart && !fileMusic.hasStart()) sounds.play(WiiSounds.START);
            fileMusic.play(withStart);
        } else {
            fileMusic.stop();
            if (withStart) music.startWithJingle(
                    getResources().getBoolean(R.bool.wii_sounds) ? R.raw.wii_start : 0, 1000L);
            else music.resume();
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) menu.trimMemory();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        menu.closeMenu();
        if (intent.getBooleanExtra("show_intro", false)) introPending = true;
        if (intent.getBooleanExtra(EXTRA_REF_POSE, false)) {
            introPending = false;
            menu.setRefPose(true);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (menu.handleKey(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        // Home has nowhere to go back to; Back only closes what is open.
        menu.back();
    }

    /**
     * True on the first start since the TV booted. Android may kill the home process while a big app runs,
     * and coming back from it must not replay the welcome screen.
     */
    private boolean firstStartThisBoot() {
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (boot < 0) return true;
        SharedPreferences prefs = getSharedPreferences("wii_menu", MODE_PRIVATE);
        if (prefs.getInt("intro_boot", -1) == boot) return false;
        prefs.edit().putInt("intro_boot", boot).apply();
        return true;
    }

    private void reloadApps() {
        appsDirty = false;
        PackageManager pm = getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(query, 0);
        Set<String> hidden = new HashSet<>(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        boolean games = getResources().getBoolean(R.bool.games_channel);
        // RetroArch's own menu is a button in the Games channel, so its tile would only be a second way in.
        if (games) hidden.addAll(Arrays.asList(RetroArch.PACKAGES));
        final List<String> dock = Arrays.asList(getResources().getStringArray(R.array.dock_apps));
        Set<String> seen = new HashSet<>();
        List<WiiMenuView.Channel> channels = new ArrayList<>();
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || hidden.contains(pkg) || !seen.add(pkg)) continue;
            CharSequence label = ri.loadLabel(pm);
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            Drawable icon = banner == null ? ri.loadIcon(pm) : null;
            Intent launch = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            channels.add(new WiiMenuView.Channel(pkg, label == null ? pkg : label.toString(), banner, icon, launch));
        }
        Collections.sort(channels, (a, b) -> {
            int da = dock.indexOf(a.key), db = dock.indexOf(b.key);
            if (da >= 0 || db >= 0) {
                if (da < 0) return 1;
                if (db < 0) return -1;
                return Integer.compare(da, db);
            }
            return a.label.compareToIgnoreCase(b.label);
        });
        int at = 0;
        while (at < channels.size() && dock.contains(channels.get(at).key)) at++;
        channels.add(at, miiChannel());
        if (games) channels.add(at + 1, gamesChannel());
        menu.setChannels(channels);
    }

    /** The built-in Games channel, after the Mii channel; its tile is a pixel meadow with a runner. */
    private WiiMenuView.Channel gamesChannel() {
        String name = getString(R.string.games_channel_name);
        if (gamesBanner == null) gamesBanner = new GamesBanner(name, miiFace);
        return new WiiMenuView.Channel(getPackageName() + ".games", name, gamesBanner, null,
                new Intent(this, GamesActivity.class));
    }

    /** The built-in Mii channel, right after the dock apps; its tile shows the plaza's residents. */
    private WiiMenuView.Channel miiChannel() {
        if (miiFace == null) {
            Typeface regular;
            try {
                regular = getResources().getFont(R.font.outfit_medium);
            } catch (RuntimeException e) {
                regular = Typeface.SANS_SERIF;
            }
            miiFace = WiiMenuView.weight(this, regular, 620);
        }
        String name = getString(R.string.mii_channel_name);
        MiiPack.get(this);
        List<Mii> miis = miiStore.loadOrSeed(new Random(), MiiChannelView.SEED_COUNT);
        miiVersion = miiStore.version();
        return new WiiMenuView.Channel(getPackageName() + ".mii", name, new MiiBanner(miis, name, miiFace), null,
                new Intent(this, MiiChannelActivity.class));
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
            overridePendingTransition(R.anim.app_open_enter, R.anim.app_open_exit);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onChannel(WiiMenuView.Channel channel) {
        open(channel.intent);
    }

    @Override
    public void onSettingsButton() {
        open(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override
    public void onSdButton() {
        Intent media = getPackageManager().getLeanbackLaunchIntentForPackage(MEDIA_PLAYER);
        if (media == null) media = getPackageManager().getLaunchIntentForPackage(MEDIA_PLAYER);
        if (media == null) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        open(media.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override
    public void onInputsButton() {
        List<WiiMenuView.MenuItem> items = new ArrayList<>();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            Set<String> ids = new HashSet<>();
            for (TvInputInfo info : inputs) ids.add(info.getId());
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.isHidden(this)) continue;
                if (info.getParentId() != null && ids.contains(info.getParentId())) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                CharSequence custom = info.loadCustomLabel(this);
                if (custom != null && custom.length() > 0 && !custom.toString().equals(label)) {
                    label = custom + " \u00B7 " + label;
                }
                final String id = info.getId();
                items.add(new WiiMenuView.MenuItem(label, () -> openInput(id)));
            }
        }
        Intent liveTv = liveTvPkg.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (liveTv != null) {
            final Intent tv = liveTv.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            items.add(new WiiMenuView.MenuItem(getString(R.string.live_tv), () -> open(tv)));
        }
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }
        menu.showMenu(getString(R.string.inputs), items);
    }

    private void openInput(String inputId) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        open(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
