#!/usr/bin/env python3
"""Put your games on the TV for the wiiOS Games channel, and set RetroArch up for the TV remote.

    TV_HOST=192.168.1.50 tools/venv/bin/python tools/games/add.py game.gba [--title T] [--color "#E8432E"] [--core mgba]
    TV_HOST=192.168.1.50 tools/venv/bin/python tools/games/add.py mslug.zip --system neogeo --title "Metal Slug"
    TV_HOST=192.168.1.50 tools/venv/bin/python tools/games/add.py --bios neogeo.zip
    TV_HOST=192.168.1.50 tools/venv/bin/python tools/games/add.py --list
    TV_HOST=192.168.1.50 tools/venv/bin/python tools/games/add.py --remove "Title or file name" [--delete]
    TV_HOST=192.168.1.50 tools/venv/bin/python tools/games/add.py --setup

A game is copied to /storage/emulated/0/RetroArch/roms/<system>/ and gets a line in the launcher's games.txt
(see GameList.java), written through its ThemeProvider. --setup changes a few RetroArch settings and adds the
button maps in tools/games/retroarch/: OK and Back are RetroPad A and B, and red/green/yellow/blue are
Start/Select/Y/X. The channel labels each console's buttons; hold red for RetroArch's menu.

Only use games you own: dump your own cartridges.
"""
import argparse
import base64
import hashlib
import os
import re
import shlex
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PROFILES = os.path.join(HERE, "retroarch")
ROMS = "/storage/emulated/0/RetroArch/roms"
GAMES_URI = "content://com.gathin.tvhome.theme/games.txt"
TMP = "/data/local/tmp"
# Same order as RetroArch.PACKAGES in the launcher.
PACKAGES = ("com.retroarch.aarch64", "com.retroarch", "com.retroarch.ra32")
# The consoles the launcher starts without a core named (GameList.defaultCore), and their roms/ subfolder.
FOLDERS = {".gba": "gba", ".gb": "gb", ".gbc": "gb", ".sgb": "gb", ".nes": "nes", ".sfc": "snes", ".smc": "snes",
           ".md": "md", ".gen": "md", ".smd": "md", ".sms": "sms", ".gg": "gg"}
# Consoles whose files are plain .zip sets: --system picks the folder and the core.
SYSTEMS = {"neogeo": ("neogeo", "fbneo")}
HEADER = "# title | ROM path | label colour | libretro core"
CHUNK = 48 * 1024
SETTINGS = {
    # The remote is a second device next to a gamepad; let any of them drive the menu.
    "all_users_control_menu": "true",
    # Hold Start (the red button) for two seconds to open the menu: the remote has no spare button for it.
    "input_menu_toggle_gamepad_combo": "7",
    # OK chooses and Back goes back, as everywhere else on the TV.
    "menu_swap_ok_cancel_buttons": "false",
    "rgui_browser_directory": ROMS,
    # In-game saves reach the file within ten seconds, even when RetroArch is closed without asking.
    "autosave_interval": "10",
    "auto_remaps_enable": "true",
}
# Whatever port the remote lands on, it plays as player 1.
REMAP = 'input_remap_port_p2 = "0"\ninput_remap_port_p3 = "0"\ninput_remap_port_p4 = "0"\n'
# The remote's yellow and blue are Y and X, which the GBA lacks: there they become L and R (RetroPad 10 and 11).
# A gamepad's own L and R are untouched.
GBA_LR = "".join(f'input_player{n}_btn_y = "10"\ninput_player{n}_btn_x = "11"\n' for n in range(1, 5))
# FinalBurn Neo reads the Neo Geo's A and B from RetroPad B and A (C and D from Y and X): swapped, OK is A as
# on the other consoles.
NEOGEO_AB = "".join(f'input_player{n}_btn_a = "0"\ninput_player{n}_btn_b = "8"\n' for n in range(1, 5))
# Each core's remap file, under the folder of its library name, as RetroArch looks for it.
CORE_REMAPS = {
    "mGBA": REMAP + GBA_LR,
    "FCEUmm": REMAP,
    "Snes9x 2010": REMAP,
    "FinalBurn Neo": REMAP + NEOGEO_AB,
    "Genesis Plus GX": REMAP,
}
CORES = ("Nintendo - Game Boy Advance (mGBA), Nintendo - NES / Famicom (FCEUmm), "
         "Nintendo - SNES / SFC (Snes9x 2010), Arcade (FinalBurn Neo), Sega - MS/GG/MD/CD (Genesis Plus GX)")


# ---- games.txt -----------------------------------------------------------------------------------------

def folder(name, core=None, system=None):
    """The roms/ subfolder for a file, or None when the launcher could not start it without a core."""
    if system:
        return SYSTEMS[system][0]
    ext = os.path.splitext(name)[1].lower()
    if ext in FOLDERS:
        return FOLDERS[ext]
    if core:
        return ext.lstrip(".") or "other"
    return None


def safe_name(name):
    """The file name on the TV: letters, digits and a few marks, nothing the shell would read."""
    return re.sub(r"[^A-Za-z0-9 ._()+-]", "_", os.path.basename(name)).strip(" .") or "game"


def default_title(name):
    """The file name without its extension and the region and dump tags, e.g. "(USA)" or "[!]"."""
    stem = os.path.splitext(os.path.basename(name))[0]
    title = re.sub(r"\s*[(\[][^)\]]*[)\]]", "", stem).replace("_", " ").strip()
    return title or stem


def entry(line):
    """(title, rom) for a game line, None for blank lines and comments."""
    line = line.strip()
    if not line or line.startswith("#"):
        return None
    parts = [p.strip() for p in line.split("|")]
    if len(parts) < 2 or not parts[1].startswith("/"):
        return None
    return parts[0], parts[1]


def entries(text):
    return [e for e in (entry(line) for line in text.splitlines()) if e]


def upsert(text, title, rom, color=None, core=None):
    """games.txt with this game's line at the end, replacing an older line for the same ROM."""
    lines = [line for line in text.splitlines() if (entry(line) or ("", ""))[1] != rom]
    while lines and not lines[-1].strip():
        lines.pop()
    if not lines:
        lines = [HEADER]
    fields = [title.replace("|", "-").strip(), rom]
    if color or core:
        fields.append(color or "")
    if core:
        fields.append(core)
    lines.append(" | ".join(fields))
    return "\n".join(lines) + "\n"


def remove(text, key):
    """games.txt without the games whose title, file name or path is key, and the (title, rom) removed."""
    k = key.strip().lower()
    keep, gone = [], []
    for line in text.splitlines():
        e = entry(line)
        if e and k in (e[0].lower(), os.path.basename(e[1]).lower(), e[1].lower()):
            gone.append(e)
        else:
            keep.append(line)
    return ("\n".join(keep) + "\n") if keep else "", gone


def set_cfg(text, values):
    """retroarch.cfg with each key in values set, added at the end when it is missing."""
    out, seen = [], set()
    for line in text.splitlines():
        m = re.match(r"\s*([A-Za-z0-9_]+)\s*=", line)
        if m and m.group(1) in values:
            out.append(f'{m.group(1)} = "{values[m.group(1)]}"')
            seen.add(m.group(1))
        else:
            out.append(line)
    out += [f'{k} = "{v}"' for k, v in values.items() if k not in seen]
    return "\n".join(out) + "\n"


def cfg_value(text, key):
    m = re.search(r'^\s*' + re.escape(key) + r'\s*=\s*"([^"]*)"', text, re.M)
    return m.group(1) if m else None


# ---- the TV --------------------------------------------------------------------------------------------

def connect():
    sys.path.insert(0, os.path.dirname(HERE))
    import tv  # noqa: E402 - reads TV_HOST when imported
    return tv.connect()


def sh(dev, cmd, timeout=120.0):
    return dev.shell(cmd, read_timeout_s=timeout).replace("\r\n", "\n")


def md5_on_tv(dev, remote):
    out = sh(dev, f"md5sum {shlex.quote(remote)} 2>/dev/null").split()
    return out[0] if out else ""


def put_bytes(dev, data, remote, label=None):
    """Writes data to remote as base64 through plain shell commands; True when the md5 matches."""
    chunk = min(CHUNK, dev.max_chunk_size * 3 // 4 - 64)
    chunk -= chunk % 3   # whole base64 groups, or each chunk would end in '=' padding and corrupt the stream
    q, tmp = shlex.quote(remote), shlex.quote(remote + ".b64")
    sh(dev, f"rm -f {q} {tmp}")
    n = max(1, (len(data) + chunk - 1) // chunk)
    for i in range(n):
        part = base64.b64encode(data[i * chunk:(i + 1) * chunk]).decode()
        if f"ok{i}" not in sh(dev, f"echo '{part}' >> {tmp} && echo ok{i}", 60.0):
            return False
        if label and n > 4:
            print(f"\r{label}: {100 * (i + 1) // n}%", end="", flush=True)
    if label and n > 4:
        print()
    sh(dev, f"base64 -d {tmp} > {q} && rm {tmp}")
    return md5_on_tv(dev, remote) == hashlib.md5(data).hexdigest()


def push_file(dev, local, remote):
    data = open(local, "rb").read()
    want = hashlib.md5(data).hexdigest()
    try:
        dev.push(local, remote, transport_timeout_s=600.0, read_timeout_s=600.0)
        if md5_on_tv(dev, remote) == want:
            return True
    except Exception as e:  # some TVs drop large sync transfers; the shell route is slower but works
        print(f"push failed ({e}); sending it through the shell instead")
    return put_bytes(dev, data, remote, label=os.path.basename(local))


def read_games(dev):
    out = sh(dev, f"content read --uri {GAMES_URI}")
    if "FileNotFoundException" in out:
        return ""
    if "Error while accessing provider" in out or "Unknown authority" in out or "Exception" in out:
        sys.exit("Could not read the launcher's games list. Is wiiOS (com.gathin.tvhome) installed?\n" + out)
    return out


def write_games(dev, text):
    tmp = f"{TMP}/games.txt"
    if not put_bytes(dev, text.encode("utf-8"), tmp):
        sys.exit("Could not copy the games list to the TV.")
    out = sh(dev, f"content write --uri {GAMES_URI} < {tmp}; rm -f {tmp}")
    if out.strip():
        sys.exit("The launcher did not take the games list:\n" + out)


def retroarch(dev):
    out = sh(dev, "pm list packages com.retroarch")
    found = {line.split(":", 1)[1].strip() for line in out.splitlines() if line.startswith("package:")}
    return next((p for p in PACKAGES if p in found), None)


# ---- commands ------------------------------------------------------------------------------------------

def add(dev, local, title, color, core, system=None):
    if system == "neogeo" and not local.lower().endswith(".zip"):
        sys.exit(f"{local}: Neo Geo games are FinalBurn Neo .zip sets, named as the set (for example mslug.zip).")
    if system:
        core = core or SYSTEMS[system][1]
    sub = folder(local, core, system)
    if sub is None:
        sys.exit(f"{local}: the channel starts {', '.join(FOLDERS)} files on its own; for a Neo Geo "
                 "set add --system neogeo, and for anything else name a core with --core.")
    remote_dir = f"{ROMS}/{sub}"
    remote = f"{remote_dir}/{safe_name(local)}"
    sh(dev, f"mkdir -p {shlex.quote(remote_dir)}")
    print(f"copying {os.path.basename(local)} ({os.path.getsize(local) // 1024} KB) to {remote}")
    if not push_file(dev, local, remote):
        sys.exit("The copy on the TV does not match the file; nothing was added.")
    text = upsert(read_games(dev), title or default_title(local), remote, color, core)
    write_games(dev, text)
    print(f"added \"{title or default_title(local)}\" to the Games channel")
    if retroarch(dev) is None:
        print("RetroArch is not installed yet: the channel will ask for it.")


def bios(dev, local):
    """Copies the Neo Geo BIOS next to the Neo Geo games, where FinalBurn Neo looks for it."""
    if os.path.basename(local).lower() != "neogeo.zip":
        sys.exit(f"{local}: FinalBurn Neo's Neo Geo BIOS is the set called neogeo.zip.")
    remote_dir = f"{ROMS}/neogeo"
    sh(dev, f"mkdir -p {remote_dir}")
    if not push_file(dev, local, f"{remote_dir}/neogeo.zip"):
        sys.exit("The copy on the TV does not match the file.")
    print(f"copied the Neo Geo BIOS to {remote_dir}/neogeo.zip")


def setup(dev):
    pkg = retroarch(dev)
    if pkg is None:
        sys.exit("RetroArch is not installed. Install it on the TV first (retroarch.com, Android build).")
    ext = f"/storage/emulated/0/Android/data/{pkg}/files"
    cfg = f"{ext}/retroarch.cfg"
    sh(dev, f"am force-stop {pkg}")   # it writes its settings back when it closes
    text = sh(dev, f"cat {cfg} 2>/dev/null")
    if not text.strip():
        sys.exit("RetroArch has not saved its settings yet. Open it once on the TV, leave it with Home, "
                 "and run --setup again.")
    sh(dev, f"[ -f {cfg}.before-wii-tv ] || cp {cfg} {cfg}.before-wii-tv")
    values = dict(SETTINGS, joypad_autoconfig_dir=f"{ext}/autoconfig")
    if not put_bytes(dev, set_cfg(text, values).encode("utf-8"), cfg):
        sys.exit("Could not write RetroArch's settings.")
    sh(dev, f"mkdir -p {ext}/autoconfig/android")
    for name in sorted(os.listdir(PROFILES)):
        if name.endswith(".cfg"):
            put_bytes(dev, open(os.path.join(PROFILES, name), "rb").read(), f"{ext}/autoconfig/android/{name}")
            print(f"button map: {name}")
    remaps = cfg_value(text, "input_remapping_directory") or "/storage/emulated/0/RetroArch/config/remaps"
    if remaps == "default":
        remaps = "/storage/emulated/0/RetroArch/config/remaps"
    for lib, rmp in CORE_REMAPS.items():
        path = shlex.quote(f"{remaps}/{lib}/{lib}.rmp")
        sh(dev, f"mkdir -p {shlex.quote(remaps + '/' + lib)}")
        sh(dev, f"[ -f {path} ] && [ ! -f {path}.before-wii-tv ] && cp {path} {path}.before-wii-tv")
        put_bytes(dev, rmp.encode(), f"{remaps}/{lib}/{lib}.rmp")
    folders = set(FOLDERS.values()) | {s[0] for s in SYSTEMS.values()}
    sh(dev, "mkdir -p " + " ".join(f"{ROMS}/{name}" for name in sorted(folders)))
    print(f"RetroArch ({pkg}) is set up; its old settings are in {cfg}.before-wii-tv")
    print(f"In RetroArch, if you have not yet: Online Updater > Core Downloader > the cores for your consoles ({CORES}), "
          "and Online Updater > Update Controller Profiles for Bluetooth gamepads.")


def main():
    ap = argparse.ArgumentParser(description="Games for the wiiOS Games channel.")
    ap.add_argument("rom", nargs="*", help="game files to copy to the TV and add to the channel")
    ap.add_argument("--title", help="the name on the shelf (one game at a time); default: the file name")
    ap.add_argument("--color", help="label colour, #RRGGBB")
    ap.add_argument("--core", help="libretro core, e.g. mgba (the default for Game Boy and GBA files)")
    ap.add_argument("--system", choices=sorted(SYSTEMS), help="the console of .zip sets: neogeo (FinalBurn Neo)")
    ap.add_argument("--bios", metavar="FILE", help="copy the Neo Geo BIOS (neogeo.zip) next to the Neo Geo games")
    ap.add_argument("--list", action="store_true", help="show the games in the channel")
    ap.add_argument("--remove", metavar="GAME", help="take a game off the channel, by title or file name")
    ap.add_argument("--delete", action="store_true", help="with --remove, also delete its file from the TV")
    ap.add_argument("--setup", action="store_true", help="set RetroArch up for the TV remote")
    args = ap.parse_args()
    if args.color and not re.fullmatch(r"#[0-9A-Fa-f]{6}", args.color):
        ap.error("--color takes #RRGGBB, e.g. #E8432E")
    if args.core and not re.fullmatch(r"[A-Za-z0-9_]+", args.core):
        ap.error("--core takes a core's name, e.g. mgba")
    if args.title and len(args.rom) > 1:
        ap.error("--title names one game; add them one at a time")
    for path in args.rom + ([args.bios] if args.bios else []):
        if not os.path.isfile(path):
            ap.error(f"{path}: no such file")
    if not (args.rom or args.list or args.remove or args.setup or args.bios):
        ap.print_help()
        return

    dev = connect()
    try:
        if args.setup:
            setup(dev)
        if args.bios:
            bios(dev, args.bios)
        for path in args.rom:
            add(dev, path, args.title, args.color, args.core, args.system)
        if args.remove:
            text, gone = remove(read_games(dev), args.remove)
            if not gone:
                sys.exit(f"No game called \"{args.remove}\" in the channel; see --list.")
            write_games(dev, text)
            for title, rom in gone:
                print(f"removed \"{title}\"")
                if args.delete and rom.startswith(ROMS + "/"):
                    sh(dev, f"rm -f {shlex.quote(rom)}")
                    print(f"deleted {rom}")
        if args.list:
            games = entries(read_games(dev))
            for title, rom in games:
                print(f"{title}  ({rom})")
            if not games:
                print("No games yet.")
    finally:
        dev.close()


if __name__ == "__main__":
    main()
