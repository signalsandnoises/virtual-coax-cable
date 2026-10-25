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
& $adb -s $s shell am start-foreground-service -n com.vcx.receiver/.ReceiverService -a com.vcx.receiver.START --ei target_ms 40 --es wifi_mode low_latency --ei track_bursts 2

# Stop (stops playout, releases the Wi-Fi/wake locks, stops the service)
& $adb -s $s shell am start-foreground-service -n com.vcx.receiver/.ReceiverService -a com.vcx.receiver.STOP
```

| Extra | Values | Meaning |
|---|---|---|
| `target_ms` | 5..1000, default 40 | Jitter buffer target depth (rounded up to whole packets) |
| `wifi_mode` | `low_latency` (default), `high_perf`, `none` | WifiLock mode held while running |
| `track_bursts` | 1..32, default 2 | AudioTrack buffer = this many HAL bursts (`PROPERTY_OUTPUT_FRAMES_PER_BUFFER`) |

## Logs

```powershell
& $adb -s $s logcat -s VCX
```

- `VCX-START target_ms=.. wifi_mode=.. track_frames=.. burst_frames=.. sample_rate=.. ...` once per START
  (also shows the AudioTrack capacity, whether the fast/low-latency track was granted, and the socket buffer).
- `VCX-STATS buf_ms=.. target_ms=.. rx=.. lost=.. recovered=.. concealed=.. late=.. underruns=.. rate_ppm=.. track_frames=..`
  once a second, cumulative since START (format as in PROTOCOL.md). The same numbers go to the PC as a STATS packet.
- `VCX-EXTRA dup=.. trims=.. trimmed_packets=.. rebuffers=.. resyncs=.. bad=..` when any of these change.
- Events: `VCX-PRIMED`, `VCX-TRIM` (buffer > target + 100 ms, oldest dropped down to target),
  `VCX-REBUFFER` (no data for max(target, 200 ms), re-priming), `VCX-RESYNC` (seq jump, sender restarted),
  `VCX-DISCOVER`, `VCX-STOP`.
