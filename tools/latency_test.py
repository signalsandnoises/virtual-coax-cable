#!/usr/bin/env python3
"""Measure end-to-end streaming latency and dropouts through a Focusrite loopback rig.

Rig:   Windows default output (Focusrite) --hardware loop--> Focusrite input 1  (reference)
       Windows default output --loopback capture--> streaming app --WiFi--> phone
       phone audio out --cable--> Focusrite input 2                              (device under test)

The script plays a jittered train of clicks (one every 4-10 ms) over a band-limited
noise bed, and records inputs 1+2 on one clock.

Latency: every click is timestamped on both inputs with a matched filter. Latency of a
click = time on input 2 minus time on input 1, i.e. how much later the phone plays it
than the hardware loop does: what you would hear as the offset between PC speakers and
phone. A step in that latency is a buffer slip / resync.

Dropouts: the noise bed is compared in 2 ms blocks, input 2 against input 1 at the lag
the clicks give. Any block that stops matching (silence, glitch, repeated or dropped
samples) is flagged, including gaps shorter than the click spacing. Missing clicks are
a second, coarser check. Exit code: 0 clean, 3 issues found, 2 measurement failed.

    python tools/latency_test.py                 # play + record 30 s, analyze, save
    python tools/latency_test.py --analyze X.wav # re-analyze a saved recording
    python tools/latency_test.py --selftest      # verify the analysis on synthetic defects

Output is played through the Focusrite outputs; --level is the click peak in dBFS.
"""
import argparse
import datetime
import json
import sys
from pathlib import Path

import numpy as np
from scipy import signal
from scipy.io import wavfile

SR = 48000
PRE_S = 2.0            # silence before the test signal
POST_S = 2.0           # silence after, lets late audio finish arriving
CLICK_N = 97           # click length in samples (~2 ms)
CLICK_HZ = 3000.0      # Ricker wavelet centre frequency
GAP_MS = (4.0, 10.0)   # spacing between clicks, uniform random
VOTE_K = 24            # clicks per lag-voting window
VOTE_STEP = 4          # voting windows start every this many clicks
BIN = 10               # lag histogram bin, samples (0.2 ms)
TOL = 48               # a click counts as present within +-1 ms of the voted lag
STEP_MS = 1.0          # latency change between neighbouring clicks counted as a buffer slip
WEAK_FRAC = 0.3        # click level below this fraction of normal gain counts as missing
GOOD_RUN = 10          # clicks in a row that must arrive before the stream counts as started
MIN_RMS_DB = -70.0     # an input quieter than this means nothing is arriving
BAND = (100.0, 6000.0) # noise bed band; wider and sub-sample clock offsets decorrelate it
BLK = 96               # noise-bed dropout block, 2 ms
FINE = 3               # per-block lag refinement, +-3 samples
CORR_BAD = 0.6         # block correlation below this is a defect (clean is >0.95)
SILENT_FRAC = 0.25     # block level below this fraction of normal gain counts as silence


def click():
    n = np.arange(CLICK_N) - CLICK_N // 2
    u = (np.pi * CLICK_HZ * n / SR) ** 2
    return (1 - 2 * u) * np.exp(-u)


def make_signal(duration, level_db, seed, bed_db):
    """Click train; returns (samples, click centre positions)."""
    rng = np.random.default_rng(seed)
    n = int(duration * SR)
    x = np.zeros(n)
    c = click() * 10 ** (level_db / 20)
    h = CLICK_N // 2
    pos, sched = int(0.05 * SR), []
    while pos + h + 1 < n - int(0.05 * SR):
        x[pos - h:pos + h + 1] += c
        sched.append(pos)
        pos += int(rng.integers(int(GAP_MS[0] * SR / 1000), int(GAP_MS[1] * SR / 1000) + 1))
    if bed_db is not None:
        b = rng.standard_normal(n + SR)
        sos = signal.butter(4, BAND, btype="band", fs=SR, output="sos")
        b = signal.sosfiltfilt(sos, b)[SR // 2:SR // 2 + n]
        x += b * 10 ** (bed_db / 20) / np.sqrt(np.mean(b ** 2))
    return x.astype(np.float32), np.array(sched)


def padded(sig, sched):
    pre = int(PRE_S * SR)
    out = np.concatenate([np.zeros(pre, np.float32), sig, np.zeros(int(POST_S * SR), np.float32)])
    return out, sched + pre


def db(x):
    return 20 * np.log10(max(float(x), 1e-12))


def detect(x, lo, hi, tmpl):
    """Click times (fractional samples) and levels in x[lo:hi] by matched filter."""
    env = np.abs(signal.correlate(x[lo:hi], tmpl, mode="same", method="fft"))
    top = np.percentile(env, 99.5)
    pk, _ = signal.find_peaks(env, height=0.2 * top, distance=int(0.0015 * SR))
    pk = pk[(pk > 0) & (pk < len(env) - 1)]
    y0, y1, y2 = env[pk - 1], env[pk], env[pk + 1]
    den = y0 - 2 * y1 + y2
    off = np.where(den != 0, 0.5 * (y0 - y2) / np.where(den != 0, den, 1), 0.0)
    return lo + pk + off, y1


def vote_lags(ref_t, test_t, lo, hi):
    """Lag of test vs ref clicks by histogram vote over sliding windows of VOTE_K clicks.

    Stateless, so a lag that moves (stream start-up, buffer slips) cannot lose the lock.
    The jittered click spacing makes a wrong lag collect few votes.
    """
    n = len(ref_t)
    centres = np.arange(0, n, VOTE_STEP)
    lags = np.full(len(centres), np.nan)
    for ci, c in enumerate(centres):
        a = max(0, min(c - VOTE_K // 2, n - VOTE_K))
        r = ref_t[a:a + VOTE_K]
        l = np.searchsorted(test_t, r + lo)
        h = np.searchsorted(test_t, r + hi)
        cands = np.concatenate([test_t[x:y] - t for x, y, t in zip(l, h, r)])
        if len(cands) < 5:
            continue
        cnt = np.bincount(np.floor((cands - lo) / BIN).astype(int))
        pair = cnt + np.append(cnt[1:], 0)
        m = int(np.argmax(pair))
        inl = cands[np.abs(cands - (lo + (m + 1) * BIN)) <= BIN]
        if len(inl) >= max(8, 0.4 * len(r)):
            lags[ci] = np.median(inl)
    return centres, lags


def match(ref_t, test_t, test_amp, lo, hi):
    """Per ref click: lag to its counterpart in test (nan if absent) and that click's level."""
    centres, lags = vote_lags(ref_t, test_t, lo, hi)
    ok = np.isfinite(lags)
    if ok.sum() < 3:
        return None
    expect = ref_t + np.interp(np.arange(len(ref_t)), centres[ok], lags[ok])
    idx = np.searchsorted(test_t, expect)
    left = np.clip(idx - 1, 0, len(test_t) - 1)
    right = np.clip(idx, 0, len(test_t) - 1)
    pick = np.where(np.abs(test_t[left] - expect) <= np.abs(test_t[right] - expect), left, right)
    err = test_t[pick] - expect
    hit = np.abs(err) <= TOL
    lat = np.where(hit, test_t[pick] - ref_t, np.nan)
    amp = np.where(hit, test_amp[pick], 0.0)
    return lat, amp


def group(mask, gap=1):
    """Runs of True in mask, merging runs separated by <= gap False entries."""
    runs, i, n = [], 0, len(mask)
    while i < n:
        if mask[i]:
            j = i
            while j + 1 < n and mask[j + 1]:
                j += 1
            if runs and i - runs[-1][1] - 1 <= gap:
                runs[-1][1] = j
            else:
                runs.append([i, j])
            i = j + 1
        else:
            i += 1
    return runs


def analyze(rec, sched):
    ref = rec[:, 0].astype(np.float64)
    sig = rec[:, 1].astype(np.float64)
    out = {"rms_dbfs": {"in1": db(np.sqrt(np.mean(ref ** 2))), "in2": db(np.sqrt(np.mean(sig ** 2)))},
           "peak": {"in1": float(np.abs(ref).max()), "in2": float(np.abs(sig).max())},
           "warnings": []}
    if out["rms_dbfs"]["in1"] < MIN_RMS_DB:
        out["fatal"] = "input 1 (hardware loop) is silent: check the loop cable and that the script played"
        return out
    if out["rms_dbfs"]["in2"] < MIN_RMS_DB:
        out["fatal"] = "input 2 (phone) is silent: is the phone connected to the stream and its output plugged in?"
        return out
    for k in ("in1", "in2"):
        if out["peak"][k] >= 0.99:
            out["warnings"].append(f"{k} clipped (peak {out['peak'][k]:.3f}); lower a gain knob")

    lo = max(0, int(sched[0]) - SR // 20)
    hi = min(len(rec), int(sched[-1]) + int(1.5 * SR))
    tm = click()
    t1, a1 = detect(ref, lo, hi, tm)
    t2, a2 = detect(sig, lo, hi, tm)
    out["clicks"] = {"expected": len(sched), "in1": len(t1), "in2": len(t2)}
    if len(t1) < 0.5 * len(sched):
        out["fatal"] = f"input 1 shows only {len(t1)} of {len(sched)} clicks: loop level too low, or wrong input"
        return out
    if len(t2) < 0.1 * len(sched):
        out["fatal"] = f"input 2 shows only {len(t2)} of {len(sched)} clicks: is the phone playing this stream?"
        return out
    m1 = match(sched.astype(float), t1, a1, -SR // 50, SR)
    m2 = match(sched.astype(float), t2, a2, -SR // 50, SR)
    if m1 is None or m2 is None:
        out["fatal"] = "could not align input " + ("1" if m1 is None else "2") + " with the played click pattern"
        return out
    lat1, amp1 = m1
    lat2, amp2 = m2
    got1, got2 = np.isfinite(lat1), np.isfinite(lat2)
    both = got1 & got2
    out["loop_abs_ms"] = float(np.median(lat1[got1]) * 1000 / SR)
    out["loop_missed"] = int((~got1).sum())
    if out["loop_missed"] > 0.01 * len(sched):
        out["warnings"].append(f"input 1 (hardware loop) is missing {out['loop_missed']} clicks; the reference itself is unreliable")
    sig0 = PRE_S * SR
    t_all = (sched - sig0) / SR

    run, first = 0, None
    for k in range(len(sched)):
        run = run + 1 if got2[k] else 0
        if run >= GOOD_RUN:
            first = k - GOOD_RUN + 1
            break
    if first is None:
        out["fatal"] = "the phone path never delivered a run of clicks"
        return out
    out["startup_clicks_lost"] = int(first)
    out["startup_loss_ms"] = float((t_all[first] - t_all[0]) * 1000)

    ratio = np.full(len(sched), np.nan)
    ratio[both] = amp2[both] / amp1[both]
    gain = float(np.median(ratio[both]))
    out["phone_gain_db"] = db(gain)
    after = np.arange(len(sched)) >= first

    use = both & after
    t_k = t_all[use]
    rel = (lat2[use] - lat1[use]) * 1000 / SR
    out["phone_abs_ms"] = float(np.median(rel) + out["loop_abs_ms"])
    out["initial_ms"] = float(rel[0])
    # a jump between neighbouring clicks is a buffer slip; what is left is drift and jitter
    d = np.diff(rel)
    jump = np.abs(d) > STEP_MS
    # start-up ends the first time the latency reaches its overall median
    reached = np.nonzero(np.abs(rel - np.median(rel)) <= STEP_MS)[0]
    settle_i = int(reached[0]) if len(reached) else 0
    ts, rs = t_k[settle_i:], rel[settle_i:]
    adj = np.concatenate([[0.0], np.cumsum(np.where(jump[settle_i:], 0.0, d[settle_i:]))])
    slope, icpt = np.polyfit(ts, adj, 1)
    resid = adj - (slope * ts + icpt)
    out["settle_s"] = float(ts[0])
    out["drift_ppm"] = float(slope * 1000)   # ms per s -> parts per million
    out["latency_ms"] = {
        "median": float(np.median(rs)), "mean": float(np.mean(rs)),
        "min": float(rs.min()), "max": float(rs.max()), "p95": float(np.percentile(rs, 95)),
        "jitter_std": float(resid.std()), "jitter_p99": float(np.percentile(np.abs(resid), 99)),
        "clicks": int(use.sum()),
    }
    shifts = group(jump, gap=1)
    out["lag_shifts"] = [{"t_s": float(t_k[a + 1]), "change_ms": float(rel[b + 1] - rel[a]),
                          "startup": bool(t_k[a + 1] <= out["settle_s"])} for a, b in shifts]
    # the stretch between the last click before and the first click after a shift belongs to the shift
    spans = [(t_k[a] - 0.03, t_k[b + 1] + 0.03) for a, b in shifts]

    weak = both & (ratio < WEAK_FRAC * gain)
    bad = got1 & (~got2 | weak) & after
    for lo_t, hi_t in spans:
        bad &= ~((t_all >= lo_t) & (t_all <= hi_t))
    click_events = []
    for a, b in group(bad, gap=1):
        prev_t = t_all[a - 1] if a > 0 else t_all[a]
        next_t = t_all[b + 1] if b + 1 < len(t_all) else t_all[b]
        click_events.append({"kind": "clicks", "t_s": float(prev_t), "dur_ms": float((next_t - prev_t) * 1000),
                             "missed": int(bad[a:b + 1].sum())})

    # noise bed: every 2 ms of input 2 must match input 1 at the lag the clicks give
    x_ck = sched[use] + lat1[use]
    y_ck = lat2[use] - lat1[use]
    bs = np.arange(int(x_ck[0]), int(x_ck[-1]) - BLK, BLK)
    base = np.round(np.interp(bs + BLK / 2, x_ck, y_ck)).astype(int)
    ar = np.arange(BLK)
    rb = ref[bs[:, None] + ar]
    rn = np.linalg.norm(rb, axis=1)
    best = np.full(len(bs), -1.0)
    sn_best = np.zeros(len(bs))
    for dd in range(-FINE, FINE + 1):
        idx = bs[:, None] + base[:, None] + dd + ar
        ok = (idx.min(axis=1) >= 0) & (idx.max(axis=1) < len(sig))
        sb = sig[np.clip(idx, 0, len(sig) - 1)]
        sn = np.linalg.norm(sb, axis=1)
        c = np.sum(rb * sb, axis=1) / (rn * sn + 1e-20)
        c[~ok] = -1.0
        better = c > best
        best[better] = c[better]
        sn_best[better] = sn[better]
    nratio = sn_best / (rn + 1e-20)
    active = rn > 0.3 * np.median(rn)
    noise_events = []
    out["noise_blocks"] = {"checked": int(active.sum()), "bad": 0}
    if np.mean(best[active] >= CORR_BAD) < 0.5:
        out["warnings"].append("noise bed does not match between inputs (is the bed level too low, or the phone path "
                               "distorting?); dropouts below are from clicks only")
    else:
        ngain = float(np.median(nratio[best >= CORR_BAD]))
        silent = (nratio < SILENT_FRAC * ngain) & active
        nbad = ((best < CORR_BAD) | silent) & active
        t_b = (bs - out["loop_abs_ms"] * SR / 1000 - sig0) / SR
        for lo_t, hi_t in spans:
            nbad &= ~((t_b >= lo_t) & (t_b <= hi_t))
        out["noise_blocks"]["bad"] = int(nbad.sum())
        for a, b in group(nbad, gap=1):
            noise_events.append({"kind": "silence" if np.mean(silent[a:b + 1]) > 0.5 else "glitch",
                                 "t_s": float(t_b[a]), "dur_ms": float((b - a + 1) * BLK * 1000 / SR),
                                 "min_corr": float(best[a:b + 1].min())})
    events = list(noise_events)
    # alignment-free check: the bed never stops, so input 2 must never go quiet. A stall
    # (latency jumps up) is exactly this, and the lag logic above would otherwise hide it.
    s2 = int(sched[first] + lat2[first])
    e2 = int(sched[use][-1] + lat2[use][-1])
    W = SR // 1000
    nw = (e2 - s2) // W
    env = np.sqrt(np.mean(sig[s2:s2 + nw * W].reshape(nw, W) ** 2, axis=1))
    med_env = float(np.median(env))
    c2 = sched[use] + lat2[use]
    for a, b in group(env < 0.1 * med_env, gap=1):
        t0 = float(np.interp(s2 + a * W, c2, t_k))
        dur = (b - a + 1) * 1000 * W / SR
        if not any(e["t_s"] - 0.005 < t0 + dur / 1000 and t0 < e["t_s"] + e["dur_ms"] / 1000 + 0.005 for e in events):
            events.append({"kind": "silence", "t_s": t0, "dur_ms": float(dur),
                           "depth_db": float(db(med_env) - db(env[a:b + 1].min()))})
    # a click gap that overlaps a noise event is the same dropout seen twice
    base = list(events)
    for ce in click_events:
        end = ce["t_s"] + ce["dur_ms"] / 1000
        if not any(ne["t_s"] < end and ce["t_s"] < ne["t_s"] + ne["dur_ms"] / 1000 for ne in base):
            events.append(ce)
    events.sort(key=lambda e: e["t_s"])
    out["dropouts"] = events
    out["dropout_total_ms"] = float(sum(e["dur_ms"] for e in events))
    out["clicks_checked"] = int((got1 & after).sum())
    # a shift that coincides with a dropout is the same event; count it once
    for sft, (lo_t, hi_t) in zip(out["lag_shifts"], spans):
        sft["silence"] = any(e["t_s"] <= hi_t and e["t_s"] + e["dur_ms"] / 1000 >= lo_t for e in events)
    out["issues"] = len(events) + sum(1 for s in out["lag_shifts"] if not s["silence"])
    return out


def report(res):
    print()
    if "fatal" in res:
        print("FAILED:", res["fatal"])
        print(f"  levels: in1 {res['rms_dbfs']['in1']:.1f} dBFS rms, in2 {res['rms_dbfs']['in2']:.1f} dBFS rms")
        if "clicks" in res:
            print(f"  clicks found: expected {res['clicks']['expected']}, in1 {res['clicks']['in1']}, in2 {res['clicks']['in2']}")
        return
    L = res["latency_ms"]
    print("=== Latency (phone path vs hardware loop), once settled ===")
    print(f"  stream began at {res['initial_ms']:.1f} ms and settled after {res['settle_s']:.1f} s")
    print(f"  median {L['median']:.1f} ms   mean {L['mean']:.1f}   p95 {L['p95']:.1f}   "
          f"min {L['min']:.1f}   max {L['max']:.1f}")
    print(f"  jitter std {L['jitter_std']:.2f} ms   p99 {L['jitter_p99']:.2f} ms   drift {res['drift_ppm']:+.1f} ppm   ({L['clicks']} clicks)")
    print(f"  absolute (approx, includes the output->ADC path): loop {res['loop_abs_ms']:.1f} ms, phone {res['phone_abs_ms']:.1f} ms")
    print(f"  stream start: first {res['startup_clicks_lost']} click(s) never arrived ({res['startup_loss_ms']:.0f} ms of signal)")
    print(f"  levels: in1 {res['rms_dbfs']['in1']:.1f} dBFS, in2 {res['rms_dbfs']['in2']:.1f} dBFS (phone click gain {res['phone_gain_db']:+.1f} dB)")
    print("=== Dropouts (noise bed: any 2 ms block of input 2 not matching input 1; clicks: missing clicks) ===")
    ev = res["dropouts"]
    nb = res["noise_blocks"]
    print(f"  {len(ev)} event(s), ~{res['dropout_total_ms']:.0f} ms total; "
          f"{nb['bad']} of {nb['checked']} noise blocks bad, over {res['clicks_checked']} clicks")
    for e in ev[:25]:
        if e["kind"] == "clicks":
            what = f"{e['missed']} click(s) lost, gap upper bound"
        elif "min_corr" in e:
            what = f"{e['kind']}, min corr {e['min_corr']:.2f}"
        else:
            what = f"silence, {e['depth_db']:.0f} dB below the bed"
        print(f"    t={e['t_s']:7.3f}s  {e['dur_ms']:6.1f} ms  {what}")
    if len(ev) > 25:
        print(f"    ... {len(ev) - 25} more")
    sh = res["lag_shifts"]
    late = [s for s in sh if not s["startup"]]
    skipped = -sum(s["change_ms"] for s in late if s["change_ms"] < 0)
    stalled = sum(s["change_ms"] for s in late if s["change_ms"] > 0)
    print(f"  {len(sh)} latency shift(s) (buffer slip / resync): {len(sh) - len(late)} during start-up, {len(late)} after")
    print(f"  after start-up: {skipped:.1f} ms of audio skipped (latency drops), {stalled:.1f} ms stalled (latency jumps up)")
    for s in sh[:40]:
        what = "skipped" if s["change_ms"] < 0 else "stalled"
        print(f"    t={s['t_s']:7.3f}s  {s['change_ms']:+7.1f} ms  {what}"
              + ("  (start-up)" if s["startup"] else "") + ("  [dropout, listed above]" if s["silence"] else ""))
    if len(sh) > 40:
        print(f"    ... {len(sh) - 40} more")
    for w in res["warnings"]:
        print("  WARNING:", w)
    print()
    print("RESULT: CLEAN (no dropouts, no latency slips)" if res["issues"] == 0
          else f"RESULT: {res['issues']} issue(s), {len(ev) + sum(1 for s in late if not s['silence'])} after start-up: "
               f"{len(ev)} dropout(s) + {sum(1 for s in sh if not s['silence'])} other latency shift(s)")


def synth(played, n_sig):
    """Synthetic rig recording with known defects, for --selftest."""
    rng = np.random.default_rng(7)
    d1, d2 = int(0.012 * SR), int(0.097 * SR)
    p = played.astype(np.float64)
    ch1 = np.concatenate([np.zeros(d1), p])[:len(p)] * 0.8
    ch2 = np.concatenate([np.zeros(d2), p])[:len(p)] * 0.25
    t0 = int(PRE_S * SR) + d2
    ch2[t0:t0 + int(0.3 * SR)] = 0                              # stream startup: first 300 ms missing
    ch2[t0 + int(5.0 * SR):t0 + int(5.0 * SR) + int(0.012 * SR)] = 0   # 12 ms dropout at t=5 s
    ch2[t0 + int(8.0 * SR):t0 + int(8.0 * SR) + int(0.003 * SR)] = 0    # 3 ms dropout at t=8 s, shorter than click spacing
    ch2[t0 + int(12.0 * SR):t0 + int(12.0 * SR) + int(0.030 * SR)] = 0  # 30 ms dropout at t=12 s
    cut = t0 + int(20.0 * SR)
    ch2 = np.concatenate([ch2[:cut], ch2[cut + 240:], np.zeros(240)])  # 5 ms lag step at t=20 s
    stall = t0 + int(25.0 * SR)
    ch2 = np.concatenate([ch2[:stall], np.zeros(1920), ch2[stall:]])[:len(p)]  # 40 ms stall at t=25 s
    ch1 += rng.standard_normal(len(ch1)) * 1e-4
    ch2 += rng.standard_normal(len(ch2)) * 1e-4
    return np.stack([ch1, ch2], axis=1).astype(np.float32)


def find_devices(hint, in_idx, out_idx):
    import sounddevice as sd
    wasapi = next(i for i, a in enumerate(sd.query_hostapis()) if "WASAPI" in a["name"])
    devs = sd.query_devices()

    def pick(key):
        for i, d in enumerate(devs):
            if d["hostapi"] == wasapi and hint.lower() in d["name"].lower() and d[key] >= 2:
                return i
        sys.exit(f"no WASAPI device matching '{hint}' with >=2 channels; use --in-device/--out-device")
    i = in_idx if in_idx is not None else pick("max_input_channels")
    o = out_idx if out_idx is not None else pick("max_output_channels")
    print(f"input : [{i}] {devs[i]['name']}\noutput: [{o}] {devs[o]['name']}")
    return i, o


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--duration", type=float, default=30.0)
    ap.add_argument("--level", type=float, default=-9.0, help="click peak in dBFS")
    ap.add_argument("--bed", type=float, default=-30.0,
                    help="band-limited noise bed RMS in dBFS under the clicks; the dropout detector, "
                         "and keeps the stream from going digitally silent")
    ap.add_argument("--seed", type=int, default=1234)
    ap.add_argument("--device-name", default="Focusrite")
    ap.add_argument("--in-device", type=int)
    ap.add_argument("--out-device", type=int)
    ap.add_argument("--label", default="", help="short tag stored in the result, e.g. 'baseline'")
    ap.add_argument("--out-dir", default=str(Path(__file__).parent / "results"))
    ap.add_argument("--analyze", metavar="WAV", help="analyze an existing recording instead of playing")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()

    sig, sched = make_signal(a.duration, a.level, a.seed, a.bed)
    played, sched = padded(sig, sched)

    if a.selftest:
        res = analyze(synth(played, len(sig)), sched)
        report(res)
        ok = False
        if "fatal" not in res:
            d = res["dropouts"]
            ok = (abs(res["startup_loss_ms"] - 255) < 25 and res["startup_clicks_lost"] > 20
                  and 79.5 <= res["latency_ms"]["median"] <= 85.5
                  and len(res["lag_shifts"]) == 2
                  and abs(res["lag_shifts"][0]["t_s"] - 20.0) < 0.3 and abs(res["lag_shifts"][0]["change_ms"] + 5) < 0.5
                  and not res["lag_shifts"][0]["silence"]
                  and abs(res["lag_shifts"][1]["t_s"] - 25.0) < 0.3 and abs(res["lag_shifts"][1]["change_ms"] - 40) < 1
                  and res["lag_shifts"][1]["silence"]
                  and len(d) == 4
                  and abs(d[0]["t_s"] - 5.0) < 0.012 and 8 <= d[0]["dur_ms"] <= 16
                  and abs(d[1]["t_s"] - 8.0) < 0.012 and 2 <= d[1]["dur_ms"] <= 6
                  and abs(d[2]["t_s"] - 12.0) < 0.012 and 26 <= d[2]["dur_ms"] <= 36
                  and abs(d[3]["t_s"] - 25.0) < 0.03 and 34 <= d[3]["dur_ms"] <= 46
                  and res["issues"] == 5)
        print("\nSELFTEST", "PASS" if ok else "FAIL")
        sys.exit(0 if ok else 1)

    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    if a.analyze:
        sr, rec = wavfile.read(a.analyze)
        if sr != SR or rec.ndim != 2 or rec.shape[1] < 2:
            sys.exit(f"{a.analyze}: need a {SR} Hz recording with >=2 channels")
        rec = rec[:, :2].astype(np.float32)
        run_dir = Path(a.out_dir) / (stamp + "-reanalysis")
    else:
        import sounddevice as sd
        i, o = find_devices(a.device_name, a.in_device, a.out_device)
        out_buf = np.stack([played, played], axis=1)
        print(f"playing {a.duration:.0f} s of clicks at {a.level:.0f} dBFS peak (+{PRE_S:.0f}s pre, +{POST_S:.0f}s post)...")
        try:
            rec = sd.playrec(out_buf, samplerate=SR, channels=2, device=(i, o), dtype="float32")
            sd.wait()
        except sd.PortAudioError as e:
            sys.exit(f"audio error: {e}\nSet the Focusrite to {SR} Hz in its control panel / Windows sound settings.")
        run_dir = Path(a.out_dir) / stamp
    run_dir.mkdir(parents=True, exist_ok=True)
    if not a.analyze:
        wavfile.write(run_dir / "recording.wav", SR, rec)

    res = analyze(rec, sched)
    res["meta"] = {"label": a.label, "timestamp": stamp, "duration_s": a.duration, "level_db": a.level,
                   "bed_db": a.bed, "seed": a.seed, "source": a.analyze or "live"}
    report(res)
    (run_dir / "result.json").write_text(json.dumps(res, indent=2))
    print(f"\nsaved: {run_dir}")
    sys.exit(2 if "fatal" in res else 3 if res["issues"] else 0)


if __name__ == "__main__":
    main()
