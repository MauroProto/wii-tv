#!/usr/bin/env python3
"""Background music for the channel menu: converts your files and copies them to the TV.

    TV_HOST=192.168.1.50 tools/venv/bin/python tools/music/add.py music.flac [--start jingle.flac] [--loop START END]
    TV_HOST=192.168.1.50 tools/venv/bin/python tools/music/add.py --remove
    tools/venv/bin/python tools/music/add.py music.flac --loop 12.5 48 --out folder   (only writes the files)

The menu (MenuMusic.java) plays menu.wav from the top and then repeats the part from START to END seconds,
or the whole file without --loop. The last --crossfade seconds before END fade into the audio just before
START, so the jump back is not heard. menu_start.wav, from --start, is mixed over the first seconds when the
menu appears after the welcome screen. Reads anything ffmpeg plays (mp3, flac, ogg, m4a, wav); ffmpeg has to
be installed. With adb installed (or ADB=/path/to/adb) the copy takes a second instead of minutes.

Your music plays instead of the bundled track, which comes back with --remove. Use files you are allowed to use.
"""
import argparse
import array
import hashlib
import math
import os
import shlex
import shutil
import struct
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.dirname(HERE)
PACKAGE = "com.gathin.tvhome"
REMOTE = f"/storage/emulated/0/Android/data/{PACKAGE}/files/music"
# MenuMusic.LOOP and MenuMusic.START in the launcher.
LOOP_FILE, START_FILE = "menu.wav", "menu_start.wav"
RATE, CHANNELS = 44100, 2


# ---- the files ---------------------------------------------------------------------------------------

def decode(path, rate=RATE, channels=CHANNELS):
    """The file's samples as interleaved 16-bit integers, through ffmpeg."""
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        sys.exit("ffmpeg is needed to read the music: brew install ffmpeg, apt install ffmpeg, or ffmpeg.org.")
    r = subprocess.run([ffmpeg, "-v", "error", "-i", path, "-f", "s16le", "-acodec", "pcm_s16le",
                        "-ac", str(channels), "-ar", str(rate), "-"], capture_output=True)
    if r.returncode != 0 or not r.stdout:
        sys.exit(f"{path}: ffmpeg could not read it.\n{r.stderr.decode(errors='replace')}")
    samples = array.array("h")
    samples.frombytes(r.stdout[:len(r.stdout) // 2 * 2])
    if sys.byteorder == "big":
        samples.byteswap()
    return samples


def make_loop(samples, channels, rate, loop=None, crossfade=1.5):
    """(samples, start, end): the music cut at the loop's end, its last frames faded into those before its start.

    start and end are frames; the menu plays up to end and carries on from start."""
    frames = len(samples) // channels
    if loop is None:
        return samples[:frames * channels], 0, frames
    start, end = (int(round(t * rate)) for t in loop)
    if not 0 <= start < end <= frames:
        raise ValueError(f"the loop has to start before it ends, within the music's {frames / rate:.2f} seconds")
    out = samples[:end * channels]
    n = min(int(round(max(0.0, crossfade) * rate)), start, end - start)
    for i in range(n):
        w = 0.5 - 0.5 * math.cos(math.pi * (i + 0.5) / n)
        a, b = (end - n + i) * channels, (start - n + i) * channels
        for c in range(channels):
            v = out[a + c] * (1.0 - w) + samples[b + c] * w
            out[a + c] = max(-32768, min(32767, int(round(v))))
    return out, start, end


def wav_bytes(samples, channels, rate, loop=None):
    """A 16-bit PCM WAV file. loop (start, end) in frames goes in a smpl chunk, whose end sample is inclusive."""
    pcm = array.array("h", samples)
    if sys.byteorder == "big":
        pcm.byteswap()
    data = pcm.tobytes()
    fmt = struct.pack("<HHIIHH", 1, channels, rate, rate * channels * 2, channels * 2, 16)
    chunks = [b"fmt " + struct.pack("<I", len(fmt)) + fmt]
    if loop:
        start, end = loop
        smpl = struct.pack("<9I", 0, 0, round(1e9 / rate), 60, 0, 0, 0, 1, 0)
        smpl += struct.pack("<6I", 0, 0, start, end - 1, 0, 0)
        chunks.append(b"smpl" + struct.pack("<I", len(smpl)) + smpl)
    chunks.append(b"data" + struct.pack("<I", len(data)) + data)
    body = b"WAVE" + b"".join(chunks)
    return b"RIFF" + struct.pack("<I", len(body)) + body


# ---- the TV --------------------------------------------------------------------------------------------

def adb_binary():
    """Google's adb, when installed: it copies big files in a second where the Python client can stall."""
    found = [os.environ.get("ADB"), shutil.which("adb")]
    found += [os.path.join(os.environ[v], "platform-tools", "adb")
              for v in ("ANDROID_HOME", "ANDROID_SDK_ROOT") if os.environ.get(v)]
    return next((p for p in found if p and os.path.isfile(p) and os.access(p, os.X_OK)), None)


class TV:
    def __init__(self):
        sys.path.insert(0, TOOLS)
        import tv  # noqa: E402 - reads TV_HOST when imported
        self.tv = tv
        self.dev = tv.connect()

    def sh(self, cmd, timeout=120.0):
        return self.dev.shell(cmd, read_timeout_s=timeout).replace("\r\n", "\n")

    def md5(self, remote):
        out = self.sh(f"md5sum {shlex.quote(remote)} 2>/dev/null").split()
        return out[0] if out else ""

    def push(self, data, remote):
        """Copies data to remote: adb if there is one, else the Python client, else base64 through the shell."""
        want = hashlib.md5(data).hexdigest()
        with tempfile.TemporaryDirectory() as d:
            local = os.path.join(d, os.path.basename(remote))
            with open(local, "wb") as f:
                f.write(data)
            adb = adb_binary()
            if adb:
                env = dict(os.environ, ADB_VENDOR_KEYS=self.tv.KEY)
                target = f"{self.tv.HOST}:{self.tv.PORT}"
                subprocess.run([adb, "connect", target], env=env, capture_output=True, timeout=60)
                subprocess.run([adb, "-s", target, "push", local, remote], env=env, capture_output=True,
                               timeout=600)
                if self.md5(remote) == want:
                    return True
            if len(data) < 4 << 20:
                try:
                    self.dev.push(local, remote, transport_timeout_s=120.0, read_timeout_s=120.0)
                    if self.md5(remote) == want:
                        return True
                except Exception as e:  # noqa: BLE001 - any failure falls through to the shell route
                    print(f"push failed ({e}); sending it through the shell instead")
            subprocess.run([sys.executable, os.path.join(TOOLS, "b64push.py"), local, remote])
        return self.md5(remote) == want

    def close(self):
        self.dev.close()


# ---- commands ------------------------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description="Background music for the channel menu.")
    ap.add_argument("music", nargs="?", help="the menu's music, any file ffmpeg reads")
    ap.add_argument("--start", metavar="FILE", help="a jingle played over the music's first seconds at power-on")
    ap.add_argument("--loop", nargs=2, type=float, metavar=("START", "END"),
                    help="seconds: after END the music carries on from START (default: the whole file)")
    ap.add_argument("--crossfade", type=float, default=1.5, metavar="SECONDS",
                    help="how long the loop's end fades into its start (default 1.5)")
    ap.add_argument("--out", metavar="FOLDER", help="write menu.wav and menu_start.wav here instead of to the TV")
    ap.add_argument("--remove", action="store_true", help="take the music off the TV")
    args = ap.parse_args()
    if args.remove == bool(args.music):
        ap.error("name the music file, or --remove")
    for path in (args.music, args.start):
        if path and not os.path.isfile(path):
            ap.error(f"{path}: no such file")

    if args.remove:
        tv = TV()
        try:
            tv.sh(f"rm -f {REMOTE}/{LOOP_FILE} {REMOTE}/{START_FILE}")
        finally:
            tv.close()
        print("removed your menu music; the bundled track plays from the next time the menu comes up")
        return

    try:
        samples, start, end = make_loop(decode(args.music), CHANNELS, RATE, args.loop, args.crossfade)
    except ValueError as e:
        ap.error(str(e))
    files = {LOOP_FILE: wav_bytes(samples, CHANNELS, RATE, (start, end) if args.loop else None)}
    if args.start:
        files[START_FILE] = wav_bytes(decode(args.start), CHANNELS, RATE)
    if args.loop:
        plan = f"plays {end / RATE:.2f} s, then from {start / RATE:.2f} s to {end / RATE:.2f} s over and over"
    else:
        plan = f"repeats its {end / RATE:.2f} s"

    if args.out:
        os.makedirs(args.out, exist_ok=True)
        for name, data in files.items():
            with open(os.path.join(args.out, name), "wb") as f:
                f.write(data)
        print(f"wrote {', '.join(files)} to {args.out}: the music {plan}")
        return

    tv = TV()
    try:
        tv.sh(f"mkdir -p {REMOTE}")
        if not args.start:
            tv.sh(f"rm -f {REMOTE}/{START_FILE}")
        for name, data in files.items():
            print(f"copying {name} ({len(data) // 1024} KB)")
            if not tv.push(data, f"{REMOTE}/{name}"):
                sys.exit(f"The copy of {name} on the TV does not match; try again.")
    finally:
        tv.close()
    print(f"menu music added: it {plan}. Go back to the menu to hear it.")


if __name__ == "__main__":
    main()
