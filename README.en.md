# wii-tv

The Wii channel menu as the home screen of your Android TV.

![wiiOS on a Philips 65PUD7906](docs/wii-menu.jpg)

wii-tv replaces the home screen of an Android TV with a menu in the style of the Wii Menu: twelve channels to a page, the segment clock, the date, the round buttons, a pointing hand that follows your selection and sounds as you move. Each channel is one of your apps, with its own banner. When the TV turns on, a white notice screen comes up, as on the console, and OK takes you to the menu.

Shown on X: [I put the Wii interface on my TV](https://x.com/ProtoMauro/status/2112776289312555195) (in Spanish).

Español: [README.md](README.md)

## Disclaimer

wii-tv is a fan project. It is not affiliated with or endorsed by Nintendo. "Wii" is a trademark of Nintendo.

The repository includes no files extracted from Nintendo. The interface is drawn in code (`WiiMenuView`, `WiiBase`, `WiiPalette`). The 7 bundled effects and 3 music tracks are original remakes contributed by Soffizlly; see [audio and credits](tools/sounds/README.md).

## Features

- **Channels**: 4 columns by 3 rows per page. The first column of the next page shows at the right edge, and the arrows turn the page. Your apps fill the channels; the rest stay empty. Opening a channel grows the app out of its frame, as on the console. To move a channel, hold OK on it and carry it with the arrows; OK puts it down and the TV keeps the order.
- **Clock and date**: a segment clock, 12-hour with AM/PM or 24-hour to match the TV's time format, and the short date in the TV's language.
- **Bottom buttons**:
  - **wiiOS** (left) opens Android TV Settings.
  - **SD** opens the TV's media player (USB drives).
  - **Envelope** (right) lists the HDMI inputs and live TV.
- **Pointing hand** that follows the selected channel or button. Back from an app, it is where you left it, even if Android closed the menu to free memory.
- **Mii Channel**: the first channel. The plaza with your Miis walking around on the squared floor, the side buttons (edit, new, delete, help, parade, mail), the whistle that lines them up by name, favourite, colour or gender, and the editor to make and change them. Miis are saved on the TV. On its own everything is drawn in code; with the channel pack it uses the original faces, hair, icons and sounds. The repository does not include those files: [`tools/miipack`](tools/miipack/README.md) (in Spanish) fetches them from their public pages and builds the pack on your computer.
- **Games channel**: next to the Mii Channel. Its tile is a pixel-art meadow with a little blue runner hopping over rocks for gems. Inside, your games are cartridges on a shelf: pick one, press **Play** and it starts in [RetroArch](https://www.retroarch.com) (Game Boy, Game Boy Color, Game Boy Advance, NES, Super Nintendo, Neo Geo, Mega Drive, Master System and Game Gear). The **RetroArch** button opens its settings. The repository ships no games; see [Games](#games).
- **Wiimote channel**: turns your phone into the Wii Remote, nothing to install: scan the QR code with the camera, tap **Start** on the page that opens, and the hand follows the phone as you move it (or as you drag on its pad). A opens, B goes back, the D-pad works as the arrows, + and − turn the page and HOME goes back to the menu. Up to 4 phones at once, each with its player light. The QR code works only while the channel is open; after that the phone remembers the TV. They meet through a small Cloudflare server ([`wiimote/`](wiimote)), so both need internet.
- **Sounds** for moving, opening, turning the page, opening the inputs list and leaving the notice screen. They follow the TV volume.
- **Bundled audio**: 7 effects and 3 music tracks for Home, Mii Plaza and the editor. The jingle starts first; menu music enters one second later. No separate audio installation is needed.
- **Notice screen** at power-on. OK or the arrows close it, or it leaves by itself after 12 seconds. It does not come back when you leave an app.
- **Always starts on the menu**: turning the TV on brings the menu back even if another app was open.
- Clock screensaver (optional).

Tested on a Philips 65PUD7906/77 (Android TV 11, 1920 x 1080). It should work on any Android TV 8.0 or later with a 16:9 screen.

## Requirements

- A macOS or Linux computer on the same network as the TV. Windows works for the install too (see step 5).
- Python 3.10 or later.
- JDK 17 and the Android SDK with `platforms;android-34` and `build-tools;34.0.0`. Android Studio ships both; on macOS, `brew install openjdk@17 android-commandlinetools` also works.
- Debugging turned on on the TV (step 1).

## Install

Run every command from the repository folder, and replace `192.168.1.50` with your TV's IP address.

1. **Turn on debugging.** Settings → Device Preferences → About: select **Build** seven times. Then Settings → Device Preferences → Developer options: turn on **USB debugging**, and **Network debugging** or **ADB over network** if your TV has it. Note the TV's IP address under Settings → Network & Internet.
2. **Set up the tools.**
   ```sh
   git clone https://github.com/MauroProto/wii-tv.git
   cd wii-tv
   python3 -m venv tools/venv
   tools/venv/bin/pip install -r tools/requirements.txt
   TV_HOST=192.168.1.50 tools/venv/bin/python tools/tv.py --probe
   ```
   The first time, the TV asks whether to allow debugging. Tick "Always allow from this computer" and accept. The key that authorises your computer is kept in `tools/adbkey/`; git ignores it. Do not share it.
3. **Back up.** `TV_HOST=192.168.1.50 tools/backup.sh` saves the app lists, the current home screen, the settings and a screenshot to `backups/<model>/<date>/`. Git ignores that folder because it holds the TV's serial number and network names.
4. **(Optional) Disable vendor apps.** `TV_HOST=192.168.1.50 tools/disable.sh examples/philips-65pud7906/debloat.txt`. Apps are disabled, not uninstalled. Review the list first and put `#` in front of anything you want to keep. For other brands, [cleanyourtv](https://github.com/Gathin23/cleanyourtv/tree/main/examples) has more than a hundred tested lists.
5. **Install the menu.** `TV_HOST=192.168.1.50 launcher/build-and-install.sh` builds the app, installs it, checks that it starts, disables the stock launcher and makes **wiiOS** the home screen. If anything goes wrong, it turns the stock launcher back on. On Windows: `powershell -ExecutionPolicy Bypass -File launcher\build-and-install.ps1 -TvHost 192.168.1.50`.
6. **Close debugging when you are done.** Turn USB and network debugging off in Developer options (optionally "Revoke USB debugging authorisations"), restart the TV, and check with `tools/venv/bin/python tools/adb-closed.py 192.168.1.50`.

## Remote

| Key | Action |
| --- | --- |
| Arrows | Move the hand. Right from the last column turns the page; down from the bottom row goes to the buttons. |
| CH+ / CH- | Turn the page (fast-forward and rewind too). |
| OK | Opens the selected channel or button. |
| Hold OK | Picks the channel up to move it: the arrows swap it with the next one, and OK or Back put it down. |
| Back | Closes the inputs list or the notice screen. |
| Home | Back to the menu. On the menu, back to the first page. |

## Games

The Games channel starts your games in RetroArch. Only use games you own: dump your own cartridges with a cartridge reader (GB Operator, GBxCart RW or the Open Source Cartridge Reader, for example).

| Console | Files | RetroArch core |
| --- | --- | --- |
| Game Boy, Game Boy Color and Game Boy Advance | `.gb`, `.gbc`, `.gba` | Nintendo - Game Boy Advance (mGBA) |
| NES | `.nes` | Nintendo - NES / Famicom (FCEUmm) |
| Super Nintendo | `.sfc`, `.smc` | Nintendo - SNES / SFC (Snes9x 2010) |
| Neo Geo | `.zip` | Arcade (FinalBurn Neo) |
| Mega Drive / Genesis | `.md`, `.gen`, `.smd` | Sega - MS/GG/MD/CD (Genesis Plus GX) |
| Master System | `.sms` | Sega - MS/GG/MD/CD (Genesis Plus GX) |
| Game Gear | `.gg` | Sega - MS/GG/MD/CD (Genesis Plus GX) |

1. **Install RetroArch on the TV.** Most Android TVs are 32-bit and need `RetroArch_ra32.apk` from the [RetroArch downloads page](https://www.retroarch.com/?page=platforms). Open it once and get the cores for your consoles in Online Updater → Core Downloader. Leave with Home.
2. **Set it up for the TV remote** (once): `TV_HOST=192.168.1.50 tools/venv/bin/python tools/games/add.py --setup`. It adds the button maps in `tools/games/retroarch/` and changes a few RetroArch settings: OK chooses and Back goes back, holding red opens its menu, and games save every 10 seconds. The previous settings are kept in `retroarch.cfg.before-wii-tv` in RetroArch's folder on the TV. For a Bluetooth gamepad, also run Online Updater → Update Controller Profiles in RetroArch. If you set it up with an earlier wii-tv, run it again: the button map changed.
3. **Add your games**: `TV_HOST=192.168.1.50 tools/venv/bin/python tools/games/add.py "My game.gba" --title "My game"`. It copies the file to `RetroArch/roms/` on the TV and puts it on the shelf. `--color "#E8432E"` sets the label colour, `--list` shows the games, and `--remove "My game"` takes one off (`--delete` also deletes its file from the TV).

**Neo Geo.** Each game is a FinalBurn Neo `.zip` set (`mslug.zip`, for example) and needs the Neo Geo BIOS, `neogeo.zip`, which wii-tv does not ship. The sets must match the FinalBurn Neo version in your RetroArch, or the game will not start: `TV_HOST=192.168.1.50 tools/venv/bin/python tools/games/add.py --bios neogeo.zip`, then `TV_HOST=192.168.1.50 tools/venv/bin/python tools/games/add.py mslug.zip --system neogeo --title "Metal Slug"`.

**Sega cartridges.** Add these files with the same command, for example `TV_HOST=192.168.1.50 tools/venv/bin/python tools/games/add.py "My game.md" --title "My game"`. Genesis Plus GX is chosen automatically; cartridge games do not require a BIOS. The channel keeps the core's standard button layout: on Mega Drive, OK is C, Back is B, yellow is A, red is Start, green is Mode and blue is Y. Mode and Y apply to the six-button controller; games that need X and Z require a gamepad. On Master System and Game Gear, OK is 2 and Back is 1; red is Pause on Master System and Start on Game Gear. The shelf shows these labels for the selected console. `.bin` files are ambiguous and still need an explicit core; automatic detection covers the cartridge extensions in the table, not Sega CD or 32X.

In a game, the TV remote's arrows are the D-pad. Outside the Sega consoles described above, OK is A, Back is B, and red, green, yellow and blue are Start, Select, Y and X (on the Game Boy Advance yellow and blue are L and R; on the Neo Geo green is the coin and yellow and blue are C and D). Super Nintendo games that use L and R need a gamepad. Hold red for two seconds for RetroArch's menu (save, load or close the game). Home goes back to the Wii Menu with the game paused; choose it again to carry on. Choosing another game, or the channel's RetroArch button, closes the open one, and in-game saves reach the file within 10 seconds, so wait a moment before switching. The button map is for Philips TV remotes ("MStar Smart TV IR Receiver"); for another remote, map it in RetroArch → Settings → Input.

## Bundled audio

The APK includes the remakes contributed by Soffizlly: 7 effects and 3 music tracks. They work immediately after installation, without downloading files or running extra tools.

After the notice screen closes, the jingle plays first and looping menu music starts one second later. Mii Plaza and the editor have separate tracks. Music pauses when its screen leaves the foreground and respects audio focus.

The wiiOS, SD and envelope buttons and the Mii Channel panels share `wii_big_buttons.ogg`. `wii_woop.ogg` plays once after the cursor stays on a channel for 500 ms. Navigation, opening a panel or leaving Home cancels the countdown. Change `wii_hover_delay_ms` in `config.xml` to adjust it; `0` disables it.

Assets are in `launcher/app/src/main/res/raw/`: effects and menu music in OGG Vorbis, Plaza and editor music in Opus. See the [audio map and credits](tools/sounds/README.md). To use your own music, see [Your own music](#your-own-music).

### Your own music

The menu can play your own music instead of the bundled track. Add it with `tools/music/add.py`, which needs [ffmpeg](https://ffmpeg.org) on your computer:

```sh
TV_HOST=192.168.1.50 tools/venv/bin/python tools/music/add.py music.flac --start jingle.flac --loop 12.5 48
```

`--loop START END` is in seconds: the music plays to END and carries on from START, over and over. The second and a half before END fades into what plays just before START, so the jump is not heard (`--crossfade` sets how long). Without `--loop` the whole file repeats. `--start` is optional: a jingle that takes the place of the menu's own start sound; the music comes in a second later, as with the bundled track. It reads what ffmpeg reads (mp3, flac, ogg, m4a, wav) and stores it on the TV as WAV, in wiiOS's folder; with `adb` installed (or `ADB=/path/to/adb`) the copy takes a second. `--remove` takes your music off and brings the bundled track back, and `--out folder` only writes the files.

## Customise

Change what you like and run `launcher/build-and-install.sh` again.

| What | Where |
| --- | --- |
| Which apps come first, in what order | `dock_apps` in `launcher/app/src/main/res/values/config.xml` (package names; the rest follow alphabetically) |
| Apps that never get a channel | `hidden_apps`, same file |
| Left button text | `wii_settings_label` (default `wiiOS`) |
| Sounds on or off | `wii_sounds` |
| Music on or off (menu, Mii Plaza and editor) | `wii_music` |
| Music volume | `wii_music_volume`, in percent of the file's own level (default 100); the Plaza and the editor play at 40 % of it |
| Live TV app in the inputs list | `live_tv_package` |
| Notice screen text | `wii_intro_title`, `wii_intro_body`, `wii_intro_continue` in `res/values*/strings.xml` |
| Bundled audio | `launcher/app/src/main/res/raw/`; see `tools/sounds/README.md`. The legacy generator writes alternatives to `tools/sounds/build/`. |
| Colours, sizes, positions | Constants at the top of `WiiMenuView.java`; backdrop and panels in `WiiBase.java` and `WiiPalette.java` |
| App behind the SD button | `MEDIA_PLAYER` in `WiiMenuActivity.java` |
| Games channel on or off | `games_channel` in `config.xml`; with the channel, RetroArch gets no channel of its own |
| Wiimote channel on or off, and its server | `wiimote_channel` and `wiimote_url` in `config.xml`. Deploy the server with `cd wiimote && npx wrangler deploy`, your domain in `wiimote/wrangler.jsonc`. An empty `wiimote_url` turns the phone remote off everywhere. |

## Undo

`TV_HOST=192.168.1.50 tools/restore.sh examples/philips-65pud7906/debloat.txt com.google.android.tvlauncher` turns the disabled apps back on and returns Home to the stock launcher (on Google TV the last argument is `com.google.android.apps.tv.launcherx`). Then `tools/venv/bin/python tools/tv.py 'pm uninstall com.gathin.tvhome'` removes wiiOS.

## Credits and licence

- Built on [cleanyourtv](https://github.com/Gathin23/cleanyourtv) by Gathin (MIT): the ADB tools, the disable and restore scripts, and the base of the launcher.
- Outfit typeface, SIL Open Font License 1.1 (`launcher/FONT-LICENSE-OFL.txt`).
- Audio remakes: Soffizlly (2026), included with permission under the repository MIT licence.
- MIT licence: see [LICENSE](LICENSE).
