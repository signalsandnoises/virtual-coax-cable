#!/usr/bin/env python3
"""Loopback test for vcx-sender (no audio hardware, no phone).

Acts as a fake phone on 127.0.0.1: binds 127.0.0.1:47800 (AUDIO) and, with --discover, 127.0.0.1:47801
(answers DISCOVER with a DISCOVER_REPLY). Runs vcx-sender against it and checks:
  - packet rate ~ 48000/frames per second (+-1 %) averaged over the run
  - no seq gaps / duplicates / reordering
  - correct magic, version, type, frames, format id and packet size for the K in the header
  - max inter-arrival gap (reported)
  - fake STATS packets sent back to the sender's source address are printed by the sender

Everything binds to loopback only. Exit code 0 = pass, 1 = fail.

  python sender/tools/loopback_test.py                      # --phone 127.0.0.1 --duration 10
  python sender/tools/loopback_test.py --discover           # discovery via --discover-addr 127.0.0.1
  python sender/tools/loopback_test.py --redundancy 1
"""
import argparse
import os
import socket
import struct
import subprocess
import sys
import threading
import time

HDR = struct.Struct("<4sBBHIQHBB")  # 24 bytes
assert HDR.size == 24
HERE = os.path.dirname(os.path.abspath(__file__))
SENDER_DIR = os.path.dirname(HERE)
DEFAULT_EXES = [
    os.path.join(SENDER_DIR, "VcxSender", "bin", "Release", "net8.0-windows", "win-x64", "publish", "vcx-sender.exe"),
    os.path.join(SENDER_DIR, "VcxSender", "bin", "Release", "net8.0-windows", "win-x64", "vcx-sender.exe"),
    os.path.join(SENDER_DIR, "VcxSender", "bin", "Debug", "net8.0-windows", "win-x64", "vcx-sender.exe"),
]


def stats_packet(counter, buf_us, target_us, rx, lost):
    hdr = HDR.pack(b"VCX1", 1, 4, 0, counter, int(time.monotonic() * 1e6), 0, 0, 0)
    body = struct.pack("<iiIIIIIIfI", buf_us, target_us, rx, lost, 0, 0, 0, 0, 1.5, 192)
    return hdr + body


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--exe", default=None)
    ap.add_argument("--duration", type=float, default=10.0)
    ap.add_argument("--frames", type=int, default=120)
    ap.add_argument("--redundancy", type=int, default=0)
    ap.add_argument("--discover", action="store_true", help="use discovery (--discover-addr 127.0.0.1) instead of --phone")
    args = ap.parse_args()

    exe = args.exe or next((p for p in DEFAULT_EXES if os.path.exists(p)), None)
    if not exe or not os.path.exists(exe):
        print("vcx-sender.exe not found; build it or pass --exe")
        return 1

    audio = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    audio.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 << 20)
    audio.bind(("127.0.0.1", 47800))
    audio.settimeout(0.2)

    disc = None
    disc_seen = []
    if args.discover:
        disc = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        disc.bind(("127.0.0.1", 47801))
        disc.settimeout(0.2)

    cmd = [exe, "--duration", str(args.duration), "--frames", str(args.frames), "--redundancy", str(args.redundancy)]
    cmd += ["--discover-addr", "127.0.0.1"] if args.discover else ["--phone", "127.0.0.1"]
    print("running:", " ".join(cmd))
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    out_lines = []

    def pump():
        for line in proc.stdout:
            line = line.rstrip()
            out_lines.append(line)
            print("  [sender]", line)

    threading.Thread(target=pump, daemon=True).start()

    stop = threading.Event()

    def disc_loop():
        while not stop.is_set():
            try:
                data, src = disc.recvfrom(2048)
            except socket.timeout:
                continue
            except OSError:
                return
            if len(data) >= 24 and data[:4] == b"VCX1" and data[4] == 1 and data[5] == 2:
                disc_seen.append((src, data[24:].decode("utf-8", "replace")))
                reply = HDR.pack(b"VCX1", 1, 3, 0, 0, 0, 0, 0, 0) + struct.pack("<H", 47800) + "fake-phone".encode()
                disc.sendto(reply, src)

    if disc:
        threading.Thread(target=disc_loop, daemon=True).start()

    arrivals = []  # (t_ns, seq, size, flags, k, frames, fmt, ts)
    bad = []
    src_addr = None
    stats_sent = 0
    stats_times = [3.0, 6.0]
    t_first = None
    deadline = time.monotonic() + args.duration + 15
    while time.monotonic() < deadline:
        try:
            data, src = audio.recvfrom(65536)
        except socket.timeout:
            if proc.poll() is not None:
                break
            continue
        except ConnectionResetError:
            continue
        t = time.perf_counter_ns()
        if t_first is None:
            t_first = t
        src_addr = src
        if len(data) < 24:
            bad.append(f"short packet {len(data)} B")
            continue
        magic, ver, typ, flags, seq, ts, frames, fmt, k = HDR.unpack_from(data)
        if magic != b"VCX1" or ver != 1 or typ != 1:
            bad.append(f"bad header magic={magic} ver={ver} type={typ}")
            continue
        arrivals.append((t, seq, len(data), flags, k, frames, fmt, ts))
        el = (t - t_first) / 1e9
        if stats_sent < len(stats_times) and el >= stats_times[stats_sent]:
            audio.sendto(stats_packet(stats_sent, 12345 + stats_sent * 1000, 40000, len(arrivals), 0), src_addr)
            stats_sent += 1

    try:
        rc = proc.wait(timeout=10)
    except subprocess.TimeoutExpired:
        proc.kill()
        rc = "killed"
    stop.set()
    time.sleep(0.3)

    fails = []
    n = len(arrivals)
    print()
    print(f"sender exit code: {rc}")
    if rc != 0:
        fails.append(f"sender exit code {rc}")
    if n < 10:
        print(f"only {n} packets received")
        return 1

    # sizes / header fields
    for (_, seq, size, flags, k, frames, fmt, _) in arrivals:
        exp = 24 + (1 + k) * frames * 4
        if frames != args.frames or fmt != 1 or size != exp or k > args.redundancy or (flags & ~1):
            bad.append(f"seq {seq}: size={size} exp={exp} frames={frames} fmt={fmt} k={k} flags={flags}")
    full_k = sum(1 for a in arrivals if a[4] == args.redundancy)

    # seq continuity
    gaps = dups = reorder = 0
    for a, b in zip(arrivals, arrivals[1:]):
        d = (b[1] - a[1]) & 0xFFFFFFFF
        if d == 1:
            continue
        if d == 0:
            dups += 1
        elif d < 0x80000000:
            gaps += d - 1
        else:
            reorder += 1

    # timestamps monotonic
    ts_back = sum(1 for a, b in zip(arrivals, arrivals[1:]) if b[7] < a[7])

    # rate and inter-arrival gaps
    span_s = (arrivals[-1][0] - arrivals[0][0]) / 1e9
    rate = (n - 1) / span_s
    expected_rate = 48000 / args.frames
    ia = sorted((b[0] - a[0]) / 1e6 for a, b in zip(arrivals, arrivals[1:]))
    p50, p99, p999 = ia[len(ia) // 2], ia[int(len(ia) * 0.99)], ia[int(len(ia) * 0.999)]
    # sender-side clock rate (timestamps vs packets): independent of receive jitter
    ts_span_s = (arrivals[-1][7] - arrivals[0][7]) / 1e6
    ts_rate = (n - 1) / ts_span_s if ts_span_s > 0 else 0
    silence = sum(1 for a in arrivals if a[3] & 1)

    print(f"packets: {n} over {span_s:.3f} s, silence-flagged {silence}, full-K packets {full_k}")
    print(f"rate: {rate:.2f}/s (expected {expected_rate:.1f}, error {100 * (rate / expected_rate - 1):+.3f} %); "
          f"by sender timestamps {ts_rate:.2f}/s")
    print(f"seq: first={arrivals[0][1]} last={arrivals[-1][1]} gaps={gaps} dups={dups} reordered={reorder}; "
          f"timestamp regressions={ts_back}")
    print(f"inter-arrival ms: p50={p50:.3f} p99={p99:.3f} p99.9={p999:.3f} max={ia[-1]:.3f}")
    print(f"bad packets: {len(bad)}")
    for b in bad[:5]:
        print("   ", b)

    if abs(rate / expected_rate - 1) > 0.01:
        fails.append(f"rate {rate:.2f} outside +-1 % of {expected_rate}")
    if gaps or dups or reorder:
        fails.append(f"seq problems gaps={gaps} dups={dups} reordered={reorder}")
    if bad:
        fails.append(f"{len(bad)} bad packets")
    if ts_back:
        fails.append(f"{ts_back} timestamp regressions")
    if args.duration >= 8:
        phone_lines = [l for l in out_lines if l.startswith("phone: ")]
        exp_lines = [f"phone: buf={(12345 + i * 1000) / 1000:.1f}ms tgt=40" for i in range(stats_sent)]
        ok = all(any(l.startswith(e) for l in phone_lines) for e in exp_lines) and stats_sent == len(stats_times)
        print(f"STATS: sent {stats_sent} to {src_addr}, sender printed {len(phone_lines)}: {phone_lines}")
        if not ok:
            fails.append("sender did not print the fake STATS packets")
    if args.discover:
        found = [l for l in out_lines if l.startswith("found fake-phone at 127.0.0.1:47800")]
        print(f"DISCOVER: received {len(disc_seen)} (pc name {disc_seen[0][1] if disc_seen else None!r}); "
              f"sender printed found-line: {bool(found)}")
        if not disc_seen or not found:
            fails.append("discovery round trip failed")

    print()
    print("PASS" if not fails else "FAIL: " + "; ".join(fails))
    return 0 if not fails else 1


if __name__ == "__main__":
    sys.exit(main())
