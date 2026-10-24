#!/usr/bin/env python3
"""Tiny ADB-over-Wi-Fi client (no adb binary needed; avoids macOS Local Network blocking).

    tv.py --probe                            (TV_HOST env or tools/.tv_host)
    tv.py 'pm list packages'                 one shell command
    tv.py --batch < cmds.txt                 one command per line, same connection; stops at the first failure
    tv.py --batch --keep-going < cmds.txt    run every line, report failures at the end
    tv.py --pull /sdcard/x.png x.png
    tv.py --push-b64 local.apk /data/local/tmp/x.apk   chunked base64 upload with md5 check
    tv.py --shot out.png                     screenshot from the TV
"""
import base64
import hashlib
import os
import sys

from adb_shell.adb_device import AdbDeviceTcp
from adb_shell.auth.keygen import keygen
from adb_shell.auth.sign_pythonrsa import PythonRSASigner

HERE = os.path.dirname(os.path.abspath(__file__))
HOST_FILE = os.path.join(HERE, ".tv_host")  # written by find-tv.sh, git-ignored
HOST = os.environ.get("TV_HOST") or (open(HOST_FILE).read().strip() if os.path.exists(HOST_FILE) else "")
if not HOST:
    sys.exit("Don't know your TV's IP. Run tools/find-tv.sh, or set TV_HOST=192.168.x.y")
PORT = int(os.environ.get("TV_PORT", "5555"))
KEY = os.path.join(HERE, "adbkey", "adbkey")
# pm/cmd/am sometimes exit 0 but print one of these; the shell exit code alone isn't enough.
FAIL_MARKERS = ("Failure", "Error:", "Exception", "Unknown package")


def signer():
    os.makedirs(os.path.dirname(KEY), exist_ok=True)
    if not os.path.exists(KEY):
        keygen(KEY)
    return PythonRSASigner(open(KEY + ".pub").read(), open(KEY).read())


def connect():
    dev = AdbDeviceTcp(HOST, PORT, default_transport_timeout_s=120.0)
    dev.connect(rsa_keys=[signer()], auth_timeout_s=120)
    return dev


def run(dev, cmd):
    """Run one shell command; return (output, ok). ok needs exit code 0 and no failure text."""
    out = dev.shell(f"{cmd}; echo __rc=$?", read_timeout_s=120).rstrip()
    body, sep, rc = out.rpartition("__rc=")
    if not sep:  # the echo never ran, so we can't tell; treat as failed
        return out, False
    body = body.rstrip()
    return body, rc.strip() == "0" and not any(m in body for m in FAIL_MARKERS)


def push_b64(dev, local, remote, chunk=48_000):
    data = open(local, "rb").read()
    enc = base64.b64encode(data).decode()
    tmp = remote + ".b64"
    dev.shell(f"rm -f {tmp} {remote}")
    for i in range(0, len(enc), chunk):
        dev.shell(f"echo -n '{enc[i:i + chunk]}' >> {tmp}")
    dev.shell(f"base64 -d {tmp} > {remote} && rm -f {tmp}")
    remote_md5 = dev.shell(f"md5sum {remote}").split()[0]
    local_md5 = hashlib.md5(data).hexdigest()
    if remote_md5 != local_md5:
        sys.exit(f"md5 mismatch: {remote_md5} != {local_md5}")
    print(f"pushed {local} -> {remote} ({len(data)} bytes, md5 ok)")


def main():
    a = sys.argv[1:]
    dev = connect()
    failed = []
    if not a or a == ["--probe"]:
        for p in ("ro.product.manufacturer", "ro.product.brand", "ro.product.model", "ro.product.device",
                  "ro.build.version.release", "ro.build.version.sdk", "ro.build.display.id"):
            print(f"{p} = {dev.shell(f'getprop {p}').strip()}")
    elif a[0] == "--batch":
        keep_going = "--keep-going" in a
        for line in sys.stdin:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            out, ok = run(dev, line)
            print(f"$ {line}\n{out}")
            if not ok:
                failed.append(line)
                if not keep_going:
                    print(f"!! failed: {line}\n!! stopped here; nothing after it was run", file=sys.stderr)
                    break
    elif a[0] == "--pull":
        dev.pull(a[1], a[2])
    elif a[0] == "--push-b64":
        push_b64(dev, a[1], a[2])
    elif a[0] == "--shot":
        dev.shell("screencap -p /sdcard/_shot.png")
        dev.pull("/sdcard/_shot.png", a[1])
        dev.shell("rm /sdcard/_shot.png")
        print(f"saved {a[1]}")
    else:
        cmd = " ".join(a)
        out, ok = run(dev, cmd)
        if out:
            print(out)
        if not ok:
            failed.append(cmd)
    dev.close()
    if failed:
        sys.exit(f"!! {len(failed)} command(s) failed: " + "; ".join(failed))


if __name__ == "__main__":
    main()
