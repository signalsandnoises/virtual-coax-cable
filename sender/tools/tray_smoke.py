#!/usr/bin/env python3
"""Smoke test for vcx-tray.exe (no audio, no phone, loopback only).

Fake phone on 127.0.0.1:47800 (AUDIO in, STATS back) and 127.0.0.1:47801 (answers DISCOVER).
Runs: vcx-tray --phone 127.0.0.1 --exit-after 18 --config <tmp>\\config.json --log-dir <tmp>\\logs
with notifications off in the temp config. STATS are sent for t=1..5 s, paused until t=12 s (the tray
should report the phone lost and rediscover), then resumed (connected again).

Checks: exit code 0, AUDIO rate ~400/s with no seq gaps, DISCOVER received while the phone was silent,
and the log shows capture, streaming, connected, not answering, reconnect and a clean exit.
"""
import json
import os
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time

HDR = struct.Struct("<4sBBHIQHBB")
HERE = os.path.dirname(os.path.abspath(__file__))
EXE = os.path.join(os.path.dirname(HERE), "VcxTray", "bin", "Release", "net8.0-windows10.0.19041.0", "win-x64",
                   "publish", "vcx-tray.exe")


def stats(counter, rx):
    return HDR.pack(b"VCX1", 1, 4, 0, counter, 0, 0, 0, 0) + struct.pack(
        "<iiIIIIIIfIffff", 30500, 30000, rx, 0, 0, 0, 0, 0, 0.0, 1152, 5.0, 9.0, 4.0, 22.0)


def main():
    exe = sys.argv[1] if len(sys.argv) > 1 else EXE
    tmp = tempfile.mkdtemp(prefix="vcx-tray-smoke-")
    cfg_path = os.path.join(tmp, "config.json")
    log_dir = os.path.join(tmp, "logs")
    with open(cfg_path, "w") as f:
        json.dump({"version": 1, "notifications": False, "startStreamingOnLaunch": True}, f)

    audio = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    audio.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 << 20)
    audio.bind(("127.0.0.1", 47800))
    audio.settimeout(0.2)
    disc = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    disc.bind(("127.0.0.1", 47801))
    disc.settimeout(0.2)
    discovers = []
    stop = threading.Event()

    def disc_loop():
        while not stop.is_set():
            try:
                data, src = disc.recvfrom(2048)
            except (socket.timeout, ConnectionResetError):
                continue
            except OSError:
                return
            if data[:4] == b"VCX1" and data[5] == 2:
                discovers.append(time.monotonic())
                disc.sendto(HDR.pack(b"VCX1", 1, 3, 0, 0, 0, 0, 0, 0) + struct.pack("<H", 47800) + b"fake-phone", src)

    threading.Thread(target=disc_loop, daemon=True).start()

    cmd = [exe, "--phone", "127.0.0.1", "--exit-after", "18", "--config", cfg_path, "--log-dir", log_dir]
    print("running:", " ".join(cmd))
    t0 = time.monotonic()
    proc = subprocess.Popen(cmd)
    arrivals = []
    src = None
    sent_stats = 0
    next_stats = 1.0
    while proc.poll() is None and time.monotonic() - t0 < 40:
        try:
            data, src = audio.recvfrom(65536)
        except (socket.timeout, ConnectionResetError):
            continue
        el = time.monotonic() - t0
        if data[:4] == b"VCX1" and data[5] == 1:
            arrivals.append((time.perf_counter(), HDR.unpack_from(data)[4], len(data)))
        if el >= next_stats and src is not None:
            next_stats += 1.0
            if el < 5 or el > 12:
                audio.sendto(stats(sent_stats, len(arrivals)), src)
                sent_stats += 1
    rc = proc.wait(timeout=20)
    stop.set()

    fails = []
    n = len(arrivals)
    print(f"exit code {rc}; audio packets {n}; STATS sent {sent_stats}; DISCOVERs received {len(discovers)}")
    if rc != 0:
        fails.append(f"exit code {rc}")
    if n > 100:
        span = arrivals[-1][0] - arrivals[0][0]
        rate = (n - 1) / span
        gaps = sum(1 for a, b in zip(arrivals, arrivals[1:]) if (b[1] - a[1]) & 0xFFFFFFFF != 1)
        ia = max(b[0] - a[0] for a, b in zip(arrivals, arrivals[1:])) * 1000
        print(f"rate {rate:.2f}/s over {span:.1f} s, seq discontinuities {gaps}, max inter-arrival {ia:.2f} ms, "
              f"sizes {sorted(set(a[2] for a in arrivals))}")
        if abs(rate / 400 - 1) > 0.01:
            fails.append(f"rate {rate:.2f}")
        if gaps:
            fails.append(f"{gaps} seq discontinuities")
    else:
        fails.append("too few audio packets")
    silent_discovers = [d - t0 for d in discovers if 9 < d - t0 < 13]
    if not silent_discovers:
        fails.append("no DISCOVER while the phone was silent")

    logs = sorted(os.listdir(log_dir)) if os.path.isdir(log_dir) else []
    text = "".join(open(os.path.join(log_dir, f), encoding="utf-8").read() for f in logs)
    print(f"--- log ({', '.join(logs)}) ---")
    print(text)
    for needle in ["streaming started", "ui self-check ok", "phone connected: fake-phone", "socket bound to 127.0.0.1:", "phone connected", "phone: buf=30.5ms tgt=30",
                   "dly99=5.0", "not answering", "hotspot:", "exited cleanly"]:
        if needle not in text:
            fails.append(f"log lacks '{needle}'")
    if text.count("phone connected") < 2:
        fails.append("no reconnect after STATS resumed")
    if "capture" not in text:
        fails.append("log lacks capture info")
    print("PASS" if not fails else "FAIL: " + "; ".join(fails))
    return 0 if not fails else 1


if __name__ == "__main__":
    sys.exit(main())
