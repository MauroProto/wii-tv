#!/usr/bin/env python3
"""Minimal ADB-over-TCP client for an Android TV using adb_shell (no adb binary needed).

Set TV_HOST to the TV's IP. Usage:
    tv.py --probe                      connect and print model / Android version
    tv.py <shell command...>           run one shell command
    tv.py --batch < commands.txt       one command per line, same connection
    tv.py --push <local> <remote>      small files only (see b64push.py for larger ones)
    tv.py --pull <remote> <local>
"""
import os
import shutil
import subprocess
import sys

from adb_shell.adb_device import AdbDeviceTcp
from adb_shell.auth.keygen import keygen
from adb_shell.auth.sign_pythonrsa import PythonRSASigner

# Windows consoles often use an old code page (cp1251, cp1252 and others) that cannot print every character
# in dumpsys output. Python then stops with an error in the middle. disable.ps1 reads this output to see if
# setupwraith owns the Wi-Fi network, so print a replacement character instead of stopping.
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(errors="replace")
    except (AttributeError, ValueError):
        pass

raw_host = os.environ.get("TV_HOST")
if raw_host and ":" in raw_host:
    HOST, port_str = raw_host.rsplit(":", 1)
    PORT = int(port_str)
else:
    HOST = raw_host
    PORT = int(os.environ.get("TV_PORT", "5555"))
if not HOST:
    sys.exit("Set TV_HOST to your TV's IP address, e.g.  TV_HOST=192.168.1.50 tools/tv.py --probe\n"
             "(find it under Settings → Network, or look for the TV in `arp -a`)")
KEYDIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "adbkey")
KEY = os.path.join(KEYDIR, "adbkey")
# Reuse the stock adb key when the TV already trusts it (platform-tools / Android Studio users), so the
# "Allow USB debugging?" prompt doesn't come back a second time for this client. From #105.
ADB_KEY = os.path.join(os.path.expanduser("~"), ".android", "adbkey")
if not os.path.exists(KEY) and os.path.exists(ADB_KEY) and os.path.exists(ADB_KEY + ".pub"):
    KEY = ADB_KEY


def restrict(path):
    """Keep a private key to this account. Reported, never fatal: the key still works either way.
    Windows has no POSIX mode bits and ignores this."""
    try:
        os.chmod(path, 0o600)
    except OSError as e:
        print(f"warning: could not restrict permissions on {path}: {e}", file=sys.stderr)


def get_signer():
    # This key is an ADB credential: anyone who can read it gets a shell on every TV that has
    # accepted it, with no "Allow USB debugging?" prompt. adb_shell's keygen writes it with a plain
    # open(), so it lands at 0666 & ~umask, which is 0644 under the usual umask: readable by every
    # other account on the machine. Google's own adb keeps ~/.android/adbkey at 0600, so match that,
    # on every run, so a key written by an earlier version of this script is repaired too.
    os.makedirs(KEYDIR, mode=0o700, exist_ok=True)
    if not os.path.exists(KEY):
        keygen(KEY)
    if KEY != ADB_KEY:   # the stock key belongs to the user's own adb; don't touch its permissions
        restrict(KEY)
    with open(KEY) as f:
        priv = f.read()
    with open(KEY + ".pub") as f:
        pub = f.read()
    return PythonRSASigner(pub, priv)


def find_adb():
    found = [os.environ.get("ADB"), shutil.which("adb")]
    for v in ("ANDROID_HOME", "ANDROID_SDK_ROOT", "LOCALAPPDATA"):
        val = os.environ.get(v)
        if val:
            found.append(os.path.join(val, "platform-tools", "adb"))
            found.append(os.path.join(val, "platform-tools", "adb.exe"))
            found.append(os.path.join(val, "Android", "Sdk", "platform-tools", "adb.exe"))
    return next((p for p in found if p and os.path.isfile(p)), None)


class AdbCliDevice:
    def __init__(self, host, port, adb_path):
        self.host = host
        self.port = port
        self.target = f"{host}:{port}"
        self.adb = adb_path
        self.max_chunk_size = 64 * 1024
        subprocess.run([self.adb, "connect", self.target], capture_output=True, timeout=30)

    def shell(self, cmd, read_timeout_s=120.0):
        r = subprocess.run([self.adb, "-s", self.target, "shell", cmd], capture_output=True, timeout=read_timeout_s)
        if r.returncode != 0 and (b"device offline" in r.stderr or b"not found" in r.stderr):
            subprocess.run([self.adb, "connect", self.target], capture_output=True, timeout=10)
            r = subprocess.run([self.adb, "-s", self.target, "shell", cmd], capture_output=True, timeout=read_timeout_s)
        return r.stdout.decode("utf-8", errors="replace")

    def push(self, local, remote, transport_timeout_s=300.0, read_timeout_s=300.0):
        timeout = max(transport_timeout_s, read_timeout_s)
        r = subprocess.run([self.adb, "-s", self.target, "push", local, remote], capture_output=True, timeout=timeout)
        if r.returncode != 0:
            raise RuntimeError(r.stderr.decode("utf-8", errors="replace"))

    def pull(self, remote, local, transport_timeout_s=300.0, read_timeout_s=300.0):
        timeout = max(transport_timeout_s, read_timeout_s)
        r = subprocess.run([self.adb, "-s", self.target, "pull", remote, local], capture_output=True, timeout=timeout)
        if r.returncode != 0:
            raise RuntimeError(r.stderr.decode("utf-8", errors="replace"))

    def close(self):
        pass


def connect(timeout=120):
    try:
        dev = AdbDeviceTcp(HOST, PORT, default_transport_timeout_s=float(timeout))
        dev.connect(rsa_keys=[get_signer()], transport_timeout_s=float(timeout), auth_timeout_s=timeout)
        return dev
    except Exception:
        adb = find_adb()
        if adb:
            return AdbCliDevice(HOST, PORT, adb)
        raise


def main():
    args = sys.argv[1:]
    dev = connect()
    if not args or args == ["--probe"]:
        for p in ("ro.product.model", "ro.product.device", "ro.build.version.release",
                  "ro.build.version.sdk", "ro.build.display.id", "ro.product.manufacturer",
                  "ro.adb.secure", "service.adb.tcp.port"):
            print(f"{p} = {dev.shell(f'getprop {p}').strip()}")
    elif args[0] == "--push" and len(args) == 3:
        dev.push(args[1], args[2], transport_timeout_s=300.0, read_timeout_s=300.0)
        print(f"pushed {args[1]} -> {args[2]}")
    elif args[0] == "--pull" and len(args) == 3:
        dev.pull(args[1], args[2], transport_timeout_s=300.0, read_timeout_s=300.0)
        print(f"pulled {args[1]} -> {args[2]}")
    elif args[0] == "--batch":
        # one shell command per stdin line, each run separately on the same connection.
        # utf-8-sig drops the BOM Windows PowerShell may prepend to piped stdin.
        sys.stdin.reconfigure(encoding="utf-8-sig")
        for line in sys.stdin:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            out = dev.shell(line, read_timeout_s=120.0)
            sys.stdout.write(f"$ {line}\n{out}")
            if out and not out.endswith("\n"):
                sys.stdout.write("\n")
            sys.stdout.flush()
    else:
        out = dev.shell(" ".join(args), read_timeout_s=120.0)
        sys.stdout.write(out)
    dev.close()


if __name__ == "__main__":
    main()
