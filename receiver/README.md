# VCX receiver (Android)

Receives raw PCM over UDP (see `../PROTOCOL.md`) and plays it on the phone's aux output. A
foreground service (`ReceiverService`, type `mediaPlayback`) does all the work, including with the
screen off. The activity is a bare test UI.

## Build

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
cd receiver
.\gradlew.bat test assembleDebug
# APK: receiver\app\build\outputs\apk\debug\app-debug.apk
```

The core logic (packet codec, jitter buffer, stats) is in `app/src/main/kotlin/com/vcx/receiver/core/`
and has no Android dependencies, so it is covered by the JVM tests in `app/src/test/`.

## Install, permissions, start and stop

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$s = '<serial>'   # e.g. 10.0.0.202:43635 (see: & $adb mdns services)

& $adb -s $s install -r receiver\app\build\outputs\apk\debug\app-debug.apk
& $adb -s $s shell pm grant com.vcx.receiver android.permission.POST_NOTIFICATIONS   # or tap Allow on first launch

# Start (or restart with new settings). Extras are optional; defaults shown.
& $adb -s $s shell am start-foreground-service -n com.vcx.receiver/.ReceiverService -a com.vcx.receiver.START --ei target_ms 40 --es wifi_mode low_latency --ei track_bursts 2 --ei steer 1 --ei align 1 --ei siltrim 1

# Stop (stops playout, releases the Wi-Fi/wake locks, stops the service)
& $adb -s $s shell am start-foreground-service -n com.vcx.receiver/.ReceiverService -a com.vcx.receiver.STOP
```

| Extra | Values | Meaning |
|---|---|---|
| `target_ms` | 5..1000, default 40 | Jitter buffer target depth (rounded up to whole packets) |
| `wifi_mode` | `low_latency` (default), `high_perf`, `none` | WifiLock mode held while running |
| `track_bursts` | 1..32, default 2 | AudioTrack buffer = this many HAL bursts (`PROPERTY_OUTPUT_FRAMES_PER_BUFFER`) |
| `steer` | 0/1, default 1 | Depth steering by resampling (0 = ratio fixed at 0 ppm, for A/B tests) |
| `align` | 0/1, default 1 | One-time start-up alignment (drop/insert whole packets ~300 ms after priming) |
| `siltrim` | 0/1, default 1 | Drop/insert whole silent packets when lead is > 5 ms off target |

## How latency is held (milestone 2)

Playout chain: `JitterBuffer` -> `CubicResampler` (Catmull-Rom, fractional phase kept across packets)
-> AudioTrack, driven by `PlayoutChain`.

- Depth = frames queued in the jitter buffer (gaps and pending inserts included) + frames held in the
  resampler, sampled after every write (`buf_ms`).
- Steering variable = lead: the depth the buffer would have if packets arrived at the best delay seen
  in the last 10 s (`depth + now - bestCaseArrival(newest seq)`), minus the normal arrival spread (p30
  of lead - depth over 10 s). Stalls, bursts and slow/power-save phases lower depth but not lead, so
  the buffer absorbs them instead of the controller chasing them. Filter: interquartile mean over 1 s.
  (M2 steered raw depth, which steps by tens of ms whenever the arrival delay floor moves, and
  saturated at +1000 ppm.)
- Silence trim: when lead is > 5 ms above/below target, silent packets (flag bit 0, or all samples
  within +-2 LSB) are dropped / silence is inserted in front of them (`VCX-SILTRIM`, max 1 line/s).
- Start-up: prime to target, play ~300 ms, then drop/insert whole packets once so depth is on target
  (`VCX-ALIGN`). The only other whole-packet drop is the hard cap at target + 500 ms (`VCX-CAP`).
- Steering: PI controller every 100 ms, kp = 150 ppm/ms, ki = 3 ppm/(ms*s), clamp +-1000 ppm,
  integrator only active within +-2.5 ms of target and when not clamped (anti-windup).
- `rate_ppm` sign: positive = the phone consumes input faster than nominal (the PC clock runs fast
  relative to the phone's output clock; pitch is raised by that many ppm).
- Missing packet with no redundant copy: the last good packet repeated with a fade-out, silence for
  further missing packets, fade-in on the first packet after a gap. Empty buffer: conceal and advance;
  after 1 s with no data, re-prime (`VCX-REBUFFER`).

## Logs

```powershell
& $adb -s $s logcat -s VCX
```

- `VCX-START target_ms=.. wifi_mode=.. track_frames=.. burst_frames=.. sample_rate=.. ...` once per START
  (also shows the AudioTrack capacity, whether the fast/low-latency track was granted, and the socket buffer).
- `VCX-STATS buf_ms=.. target_ms=.. rx=.. lost=.. recovered=.. concealed=.. late=.. underruns=.. rate_ppm=.. track_frames=.. delay_p99_ms=.. delay_max_ms=.. gap_max_ms=.. depth_min_ms=..`
  once a second, counters cumulative since START (format as in PROTOCOL.md). The same numbers go to the PC as a STATS packet.
  The last four are per-second windows: arrival delay above the 10 s minimum (p99, max), the largest
  inter-arrival gap, and the minimum queued depth.
- `VCX-EXTRA dup=.. caps=.. capped_packets=.. align_dropped=.. align_inserted=.. rebuffers=.. resyncs=.. bad=.. max_k=.. sil_dropped=.. sil_inserted=..`
  when any of these change.
- `VCX-JUMP lead_ms=a->b depth_ms=a->b seq=lo..hi arrived=N expected=200 concealed=C late=L dmin_shift_ms=X sil_edits=E`
  when smoothed lead (before silence edits) rises > 5 ms within 500 ms (max 1 line/s). A negative
  `dmin_shift_ms` of the same size means packets started arriving earlier (sender left silence fill,
  or Wi-Fi left a slow phase), not that audio was added.
- Events: `VCX-PRIMED`, `VCX-ALIGN`, `VCX-CAP` (depth > target + 500 ms, trimmed to target),
  `VCX-REBUFFER` (no data for 1 s, re-priming), `VCX-RESYNC` (seq jump, sender restarted),
  `VCX-DISCOVER`, `VCX-STOP`.
