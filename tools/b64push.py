#!/usr/bin/env python3
"""Fallback file transfer: send a file to the TV as base64 chunks through plain shell commands.

Usage: b64push.py <local file> <remote path>
"""
import base64
import hashlib
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tv  # noqa: E402

CHUNK = 48 * 1024  # raw bytes per shell command (base64 grows it by 4/3); capped per TV in main()
# adbd before Android 7 aborts the connection on an ADB "open" payload over 4096 bytes, which
# Android 5.x TVs announce as their max data size. Newer adbd takes the full 48 KB.


def main():
    local, remote = sys.argv[1], sys.argv[2]
    data = open(local, "rb").read()
    want = hashlib.md5(data).hexdigest()
    dev = tv.connect()
    if isinstance(dev, tv.AdbCliDevice):
        dev.push(local, remote)
        print(f'pushed {local} -> {remote} directly via adb')
        dev.close()
        sys.exit(0)
    chunk = min(CHUNK, dev.max_chunk_size * 3 // 4 - 64)   # base64 of the chunk plus the echo must fit one payload
    chunk -= chunk % 3   # a multiple of 3, or each chunk ends in '=' padding mid-stream and base64 -d corrupts it
    tmp = remote + ".b64"
    dev.shell(f"rm -f {remote} {tmp}")
    n = (len(data) + chunk - 1) // chunk
    for i in range(n):
        part = base64.b64encode(data[i * chunk:(i + 1) * chunk]).decode()
        out = dev.shell(f"echo '{part}' >> {tmp} && echo ok{i}", read_timeout_s=60.0)
        if f"ok{i}" not in out:
            print(f"chunk {i} failed: {out}")
            sys.exit(1)
        print(f"\rchunk {i + 1}/{n}", end="", flush=True)
    print()
    out = dev.shell(f"base64 -d {tmp} > {remote} && rm {tmp} && md5sum {remote}", read_timeout_s=120.0)
    got = out.split()[0] if out.strip() else ""
    print("md5 remote:", got, "local:", want, "MATCH" if got == want else "MISMATCH")
    dev.close()
    sys.exit(0 if got == want else 1)


if __name__ == "__main__":
    main()
