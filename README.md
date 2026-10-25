# virtual-coax-cable

A wireless stand-in for a 40-foot S/PDIF coax cable.

The studio is next to the home gym. The drummer listens to Spotify on Windows while
playing; the gym speaker is fed by an Android phone over aux. If the drummer's Windows
audio reaches the gym speaker with **under 40 ms of latency** (ideally under 25 ms), the
drums and the song line up for the gym-goers. The streaming path is the cheap option next
to running a 40 ft digital coax cable (physical work) or buying a ~$300 wireless link.

## Goals

- Windows sender captures system audio by **loopback only**, never touching or changing the
  Focusrite driver. Target studio PC has a Focusrite 18i20; this dev PC has a 2i2. Loopback
  sees what Windows sends to the "Playback 1-2" output (before the driver), not ASIO/DAW
  outputs or hardware-mixer routing.
- Android receiver plays through the phone's aux path (Galaxy S23 here; it needs a USB-C to
  3.5 mm adapter, which takes the USB port, so debugging is over wireless adb).
- Latency under 40 ms end to end; **no dropouts or packet loss**, at any buffer size we try.
  A 40 ms target is soft: hits 30-40 ms off the beat sound "flammy", under ~25 ms sounds locked in.
- Local network only. Desktop on Ethernet, phone on 5 GHz Wi-Fi.
- Plan in the notes: (0) measure WiFiAudioStreaming as-is, (1) our own lean Windows sender
  (C#/.NET 8 tray app, Spotify-only capture, ~5 ms uncompressed UDP packets), (2) our own
  Android receiver (Kotlin + Oboe/AAudio, adaptive or fixed buffer shown in samples and ms,
  Wi-Fi low-latency lock, foreground service, giant play/pause button), (3) tune against
  the measured numbers. **Only step 0 has been done**; nothing of ours beyond the test tool is built.

## Target product design (our own app, not built yet)

From the notes; "(proposed)" marks details suggested in the agent responses that you have
not necessarily signed off on.

**Windows sender**
- Minimal. Runs in the **background by default, in the system tray**, with a simple interface
  for configuring the server. The tray menu in the notes has start/stop, source (Spotify only
  or all audio), a device list, and quit; any further settings are open.
- Captures with Windows loopback only; **never opens the Focusrite exclusively or touches its
  driver or ASIO interface**.
- Source (proposed): **Spotify only** (per-process loopback, Windows 10 2004+) so notification
  sounds and metronomes are not piped to the gym, with a fallback to the whole Windows mix on
  Playback 1-2. Which output the fallback captures is a dropdown (the 18i20 vs 2i2 difference
  is only this choice).
- Sends uncompressed 48 kHz 16-bit stereo UDP, about 5 ms (240 samples) per packet with
  sequence numbers and timestamps, only to the phone's address (proposed). No encryption,
  no compression, no extras. (The drummer always listens to Spotify on Windows.)
- Discovery: the phone announces itself, the PC answers (proposed), with a manual IP as a fallback.

**Android receiver**
- **One screen: a giant play/pause button, plus a small openable settings menu.**
- Listens on the computer's local network and that network only: Wi-Fi only, ignores packets
  that are not from a private address on the same network.
- Settings (proposed):
  - **Buffer mode: Adaptive or Fixed.** Adaptive grows the buffer after dropouts and slowly
    shrinks it on a clean connection, within a min and max. Fixed has a slider in samples
    (96-4800, i.e. 2-100 ms at 48 kHz).
  - **Always visible, in both modes:** current buffer in samples and ms; output latency as
    reported by the phone (Oboe), not a guess; estimated total = buffer + output latency +
    network; a dropout counter.
  - Manual PC IP for when automatic discovery is blocked.
- Playback path (proposed): Kotlin + Oboe/AAudio in low-latency exclusive mode at the phone's
  native sample rate (no resampling), clock-drift correction by dropping or duplicating single
  samples rather than letting delay creep, a foreground service so playback continues with the
  screen off, and the Wi-Fi low-latency lock (Android 10+), since Wi-Fi power saving alone
  causes 100 ms+ dropouts.
- While developing, over wireless adb the app should log its own buffer numbers so they can be
  matched against the measured latency from `tools/latency_test.py`.

**Why not just use WiFiAudioStreaming:** its Android player uses the general-purpose
`AudioTrack` (no Oboe/AAudio), no Wi-Fi low-latency lock, optional encryption, a JVM on the
desktop, and many features we do not need. It is the Experiment 1 baseline; ours should
beat it on the measured numbers.

## Layout

| Path | What it is |
|---|---|
| `marco-desktop-client/` | Submodule: [WiFiAudioStreaming-Desktop](https://github.com/marcomorosi06/WiFiAudioStreaming-Desktop). Protocol spec in `WFAS_PROTOCOL.md` |
| `marco-android-client/` | Submodule: [WiFiAudioStreaming-Android](https://github.com/marcomorosi06/WiFiAudioStreaming-Android) |
| `tools/latency_test.py` | The latency and dropout test (below) |
| `tools/results/` | Test output: recordings and JSON. Git-ignored, local only |
| `audio-streaming-over-wifi.md` | Symlink to the project notes in the Obsidian vault (below) |

**Project notes.** `audio-streaming-over-wifi.md` is a Windows PowerShell symbolic link to a file in
the Obsidian vault holding notes, thoughts and chats-with-an-agent on this project. Git
ignores symbolically linked files. The note is now at
`C:\Users\Rishabh\Documents\everything-rishabh\signals and noises\audio-streaming-over-wifi.md`
(lowercase, hyphenated). The symlink still points at the old name `Audio streaming over wifi.md`,
so it is currently broken; recreate it or open the file directly. The note holds the original
design discussion, the phase plan, and "Experiment 1" results.

## The installed apps being tested

- Desktop: WiFi Audio Streaming 1.2.0, from `C:\Users\Rishabh\Downloads\WiFi-Audio-Streaming-1.2.0-windows-x86_64\`.
  Server mode, unicast, UDP port 9090. Discovery beacon is multicast `239.255.0.1:9091`.
- Phone: Samsung Galaxy S23 (`SM-S911U1`), package `com.cuscus.wifiaudiostreaming`.
- The phone has a latency/buffer setting. **A change only applies after the stream is
  reconnected** (verify it, see "Phone log" below).
- The desktop only streams while something is playing; with a silent PC the phone connects
  and receives `pkts=0`. The test tool plays audio, which starts the flow (first clicks of a
  run are lost to this).

## Network gotchas found so far

- With **both Ethernet and Wi-Fi up**, the desktop app showed its Ethernet address
  (`10.0.0.223`, it scores higher), the phone could not reach it at the link level, and
  multicast discovery from the wired side never reached the phone. Typing the desktop's Wi-Fi
  address (`10.0.0.198:9090`) worked. With **Ethernet only** (Wi-Fi off on the desktop), the phone
  discovers the server automatically at `10.0.0.223`. The app has a network-interface setting
  (`SettingsManager.kt`, default "Auto"); address choice is in `Main.kt` `getLocalIpAddress()` -> `NetAddr.bestLocalAddress()`.
- Phone Wi-Fi: SSID `Verma2`, 5 GHz.

## Wireless adb to the phone

On the phone: Developer options > Wireless debugging > "Pair device with pairing code". Then:

```
adb pair <ip>:<pairing-port> <code>
adb mdns services              # shows the connect port (_adb-tls-connect)
adb connect <ip>:<connect-port>
```

The connect port changes whenever wireless debugging is toggled or Wi-Fi reconnects; re-run
`adb mdns services`. adb is at `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` and is not on PATH.
If both a USB and a wireless entry exist for the phone, pass `-s <serial>`.

### Phone log

```
adb -s <serial> shell pidof com.cuscus.wifiaudiostreaming
adb -s <serial> logcat -d --pid=<pid> -v time | findstr "PLAYOUT WFAS-METRICS underrun"
```

- `[PLAYOUT] buffered=NNms target=NNms rate=NNNNN`: current buffer and the configured target.
- `WFAS-METRICS ... loss=x% jitter=y ms peak=z ms interval=...`: network stats every 5 s.
- `AudioTrack ... disabled due to previous underrun, restarting`: an underrun.

## Latency and dropout test: `tools/latency_test.py`

### Rig (what we actually use)

```
Windows default output (Focusrite "Speakers")
   â”œâ”€â”€ hardware cable â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â–º Focusrite input 1   (reference)
   â””â”€â”€ loopback capture â–º desktop app â–º Wi-Fi â–º phone â–º aux â–º Focusrite input 2   (device under test)
```

Windows' default playback device must be the Focusrite, and it must run at **48 kHz**. (The
Turn 2 diagram in the notes has inputs 1 and 2 the other way round; this README is the
one that matches the script.) Reported latency = how much later the phone path plays a sound
than the hardware loop does.

### Setup

- Python 3.13 with `numpy`, `scipy`, `sounddevice` (`pip install numpy scipy sounddevice`).
- Desktop server running, phone connected to it (or the first seconds will be lost, see above).
- Optional: wireless adb connected, for the phone log.

### Running

```
python tools/latency_test.py                                     # one 30 s run
python tools/latency_test.py --repeat 5 --adb <serial> --label 40ms   # 5 runs + summary + phone log
python tools/latency_test.py --analyze path/to/recording.wav     # re-analyze a saved recording
python tools/latency_test.py --selftest                          # check the analysis on synthetic defects
```

Useful flags: `--duration`, `--level` (click peak dBFS, default -9), `--bed` (noise bed RMS dBFS,
default -30), `--pause` (seconds between repeats), `--package`, `--in-device/--out-device`.
It plays noise through the Focusrite outputs, so mind the monitors.
Exit code: 0 clean, 3 issues found, 2 measurement failed.

### What it does

It plays a jittered click train (one click every 4-10 ms) over a band-limited (100 Hz-6 kHz)
noise bed, and records inputs 1 and 2 on one clock.

- **Latency:** every click is timestamped on both inputs (matched filter); latency = time on
  input 2 minus time on input 1, per click.
- **Dropouts:** the noise bed must never stop. Any 2 ms block of input 2 that stops matching
  input 1 is flagged, and so is any 1 ms stretch of input 2 that falls 20 dB below the bed.
  Missing clicks are a third, coarser check.
- **Buffer slips:** a step in latency between neighbouring clicks. Latency dropping means the
  phone **skipped** audio; latency jumping up means it **stalled**. A stall is always a real
  silence, so it is also reported as a dropout and counted once.
- Start-up shifts (before latency first reaches its median) are labelled `(start-up)`.

### Reading the report

- Latency: median, p95, min, max, jitter, drift once settled. The "absolute" numbers include
  the test harness's own buffering and are not the app's latency; **use the relative one**.
  It can be **negative**: the app's loopback tap is earlier in the pipeline than the hardware
  loop, so the phone path can play before the PC's analog output reaches the Focusrite.
- `RESULT: CLEAN` means no dropouts and no latency slips of 1 ms or more. Anything else is
  counted as an issue; the line also says how many came after start-up.

### Known limits

- Dropouts shorter than ~1-2 ms are not detected. Reported dropout lengths are quantised to ~1-2 ms.
- Results from before the stall-as-dropout fix (commit `7aba19e`) say "0 dropouts" in their stored
  `result.json` when there were stalls. Re-analyze with `--analyze` to get corrected numbers.
- `--repeat` and the `--adb` phone-log parsing were written but **not yet run on hardware**.
- Run-to-run variation is large (see below); never trust one run.

## Findings so far (2026-10-24, WFAS 1.2.0 as-is, Experiment 1)

All four runs are in `tools/results/` locally (not in git). Numbers are re-analyzed with the current tool.

| Run folder | Phone playout target | Settled latency (phone vs loop) | Dropouts | Slips after start-up |
|---|---|---|---|---|
| `20261024-224813` | 120 ms | +66.6 ms, flat | 0 | 0 (8 skips at start-up) |
| `20261024-225055` | 120 ms (a 40 ms setting had **not** applied) | ~63 ms median, 3-130 ms swings | 2 (81 ms, 66 ms) | 20 |
| `20261024-225630` | 40 ms (confirmed in log) | -19.2 ms, flat | 1 (31 ms at 22.47 s) | 6: that stall, then 5 skips (15 skips at start-up) |
| `20261024-224159` | n/a | n/a | n/a | an abandoned noise-only version of the test, not comparable |

- Changing the target from 120 to 40 ms moved latency by about 86 ms, so the tool is sensitive to it.
- Every run starts with the receiver draining a backlog in steps of about 7.2 ms (8 to 15 steps);
  packets are 5.00 ms apart in the app's metrics, so the 7.2 ms unit is unexplained.
- At target 40 ms the app reports `buffered=84-90 ms`, well above target, and `rate=48239`; why it never reaches
  target is unknown.
- The app reported 0.03-0.06% WiFi packet loss and ~8 ms jitter (peak 13-23 ms) in the 120 ms runs.
- Not yet done: repeat runs per setting, Experiment 2 (our own sender/receiver).

## Experiment 2: our own sender and receiver (see `PLAN-own-streaming-apps.md`)

### Milestone 0: setup (2026-10-24), done

- .NET 8 SDK 8.0.426 per-user at `%LOCALAPPDATA%\Microsoft\dotnet` (not on PATH; the
  `C:\Program Files\dotnet` one is runtime only).
- Gradle 9.8.2 per-user at `%LOCALAPPDATA%\gradle-dist\gradle-9.8.2`, used to generate the
  receiver's wrapper. Latest AGP is 9.4.1. Run with `JAVA_HOME` set to the Android Studio `jbr` (JDK 25).
- WFAS is closed on the PC and not running on the phone.
- Rig check with no phone stream (`20261024-232858`): in1 -33 dBFS, in2 silent, exit 2, as expected.

### Milestone 1: minimal pipeline (2026-10-24), done

`sender/` (C# console, WASAPI loopback, silence fill) and `receiver/` (Kotlin foreground
service, fixed jitter buffer, AudioTrack fast track, 384-frame track buffer), protocol in `PROTOCOL.md`.

- Broadcast discovery, audio and STATS feedback all work with **no inbound firewall rule**.
  No `vcx` firewall rules were created. Nobody was at the PC, so a firewall prompt may or may not have appeared.
- 400 packets/s, sender max gap 3 ms when idle and 11-20 ms when capturing (WASAPI delivers about every 10 ms).
- **Every "lost" packet was late, not missing** (lost = late in all runs). So Wi-Fi jitter spikes exceed the
  buffer, and redundancy alone won't fix this.
- With no depth control, the buffer settles wherever start-up leaves it (70 ms at target 40, 57-80 ms at
  target 20), so latency doesn't follow the target. The trim at target + 100 ms fired after Wi-Fi stalls and
  caused the 207 ms skip in the first run.

| Run folder | Build / setting | Settled latency (phone vs loop) | Dropouts | Slips after start-up |
|---|---|---|---|---|
| `20261024-234502` | M1, target 40, K=0, low_latency lock, screen on | -22.7 ms (p95 -11.0) | 19 (688 ms) | 2 (incl. 207 ms trim skip) |
| `20261024-234603` | M1, target 30 (restarted service) | +25.4 ms | 5 (141 ms) | 0 |
| `20261024-234643` | M1, target 20 (restarted service) | +15.8 ms (buffer sat at 57-80 ms) | 5 (172 ms) | 0 |

### Milestone 2: Wi-Fi stalls on Verma2 (2026-10-25)

- Phone-side arrival gaps of 40-190 ms (once 600 ms) happened in 35-70% of seconds, while idle-streaming
  silence with the screen on. The PC sender's max gap was 3 ms, and PCâ†’router pings were 0-2 ms.
- The Wi-Fi lock mode made no difference: low_latency, high_perf and none were all bad. Background scan time
  was about 0, Bluetooth is off, and having the app in the foreground didn't help.
- Phoneâ†’router ping (0.2 s interval, 150 pings): median 4.5 ms, 23 above 20 ms, max 74 ms. The link is
  11ax at 5220 MHz, RSSI -53, 720/864 Mbps.
- **Idea to try (from Rishabh):** a PC hotspot. Windows Mobile Hotspot on the PC's Realtek 8922AE Wi-Fi 7
  card, with the phone joined to it, gives a direct PCâ†’phone link that skips the router. The phone still
  gets internet through the PC.
- Retest on Verma2 (`20261025-001246`, M2, target 40): +26.7 ms, 2 dropouts. Phone net delay max 160 ms.
  Steering sat at +1000 ppm with depth 52-108 ms, a receiver bug (depth creeps up after stalls). A fix
  was in progress when we paused.
- **Verma** (Google Nest at 192.168.86.x, double-NATed behind Verma2): the PC can only reach the phone over
  its own Wi-Fi, so there are 2 Wi-Fi hops. Stalls of 130-240 ms happened in 45 of 85 one-second samples,
  in a periodic pattern (about 3 s of stalls every ~11 s). Worse than Verma2.
- Stalls of ~130 ms happen on both networks, so the phone is the main suspect. Not yet checked: Samsung's
  "Wi-Fi power saving mode" (Intelligent Wi-Fi) and "Nearby device scanning".

- **PC hotspot** (Windows Mobile Hotspot on the dev desktop, PC wired to Verma2, phone at 192.168.137.135,
  shown as Wi-Fi 6): max stall 86 ms while the PC's Wi-Fi was also on Verma, and still 135 ms with it
  disconnected. Stalls of about 80 ms in roughly 40% of seconds, with the same ~11 s periodic pattern as
  on Verma. The pattern shows up on every network, so **the phone is the prime suspect**. Next step:
  Samsung "Wi-Fi power saving mode" / "Nearby device scanning".
  The hotspot also died once when the PC's Wi-Fi was disconnected from Verma. Turn hotspot power saving off.

- **Samsung Wi-Fi settings turned off** (Wi-Fi power saving mode, Nearby device scanning) on the hotspot:
  the periodic stalls are **gone**. Over 5 min, 1 second had a stall over 40 ms (147 ms), and typical gaps
  were 4-10 ms. An earlier 2 min run had one ~30 s bad episode (150-330 ms) of unknown cause.
- `20261025-005255-x5` (hotspot, steering fix, target 40, K=0, screen on): latency **-22.4 ms** median
  (range -31.9 to -20.2). Phone lost/late = **0** in all runs, delay max 22-30 ms. There were still 2-5
  short dropouts per run (1-19 ms) and 1-2 AudioTrack underruns per run. Cause: the sender inserts
  extra audio when WASAPI delivery is ~20 ms late (it hits the 20 ms silence-fill threshold); about 402.5
  packets/s while capturing. Sender fix in progress.

- `20261025-010616-x5` (sender fix, track_bursts 2): send ratio 1.0000 and no fill frames. 2 of 5 runs clean,
  the rest had 1 short dropout each, matching AudioTrack underruns. -30.0 ms.
- `track_bursts 3` (576 frames): no better, 1-2 underruns per run. -28.0 ms.
- `20261025-011349-x5` **track_bursts 6 (1152 frames): 5/5 CLEAN**, 0 underruns, 0 lost, **-13.8 ms**
  (range -15.6 to -12.7).
- Soak `20261025-011725` (tb6, 10 min, screen on): clean for 400 s with 0 underruns all run. Then a
  ~30 s Wi-Fi episode (400-431 s) had 53 dropouts, stalls up to 400 ms about every 1.2 s, and 2123 late
  packets. This is the same kind of episode seen once in a 2-min jitter check. Suspect: the PC's
  hotspot radio (background scans by the disconnected "Wi-Fi" station interface?) or the phone. Not
  yet investigated.
- Instrumented, allocation-free build (`VCX-PLAY`/`VCX-UNDERRUN`), tb2, write 192: still 1-3 underruns
  per run (-27.5 ms). GC = 0 at every underrun, and the jitter buffer was 40-53 ms. At underruns,
  write() blocked 12-20 ms, and the queue measured from timestamps is ~1100-1570 frames (23-33 ms)
  beyond the 384-frame track. So the stall is **below AudioTrack** (mixer/HAL). The phone's "aux" is
  probably a USB-C dongle (the S23 has no jack), i.e. USB audio. A bigger track buffer absorbs it.
  Playout thread runs on little cores (cpu 0-2).
- **Next:** screen-off acceptance on tb6; try tb4 and a lower jitter target (net delay p99 is about
  20-30 ms) to win back latency; investigate the 30 s Wi-Fi episodes.

### Screen-off acceptance and milestone 3 tuning (2026-10-25, 05:00, PC hotspot, phone locked)

| Run folder | Setting | Latency | Clean runs | Notes |
|---|---|---|---|---|
| `20261025-045748-x5` | t40, tb6, screen off | -11.1 ms | 4/5 | run 1 had a scan meltdown (564 ms stalls); 0 underruns |
| `20261025-050125` (10 min soak) | t40, tb6, screen off | -11.1 ms | 2 dropouts (92 + 7 ms) | 0 underruns, 61 late |
| `20261025-051158-x5` | t30, tb6, screen off | -17.9 ms | 2/5 | delay spikes of 34-100 ms beat the 30 ms buffer |
| `20261025-051533-x5` | t40, tb4, screen off | -15.5 ms | 3/5 | tb4 still underruns (2 in run 1); run 3 had a scan meltdown |

- **Cause of the ~30 s Wi-Fi meltdowns: the phone's own Wi-Fi scans.** Logcat shows Samsung location
  (`SLocation GeopointGeofenceManger - refresh wifi to receive loc`) starting full 2.4 + 5 GHz scans every
  ~6-8 s in bursts, plus a PNO scheduled scan when the screen locks. During streaming, **12/12 scan
  results coincided with a stall second.** Fixing it needs a phone setting change (Location off while in
  use, or Location → Wi-Fi scanning off, or finding which app holds the geofence). Waiting on Rishabh.
- **Best setting so far: target 40, track_bursts 6.** That is about -11 to -14 ms vs the wired path, clean
  except for scan meltdowns. tb4 underruns; t30 is too tight while scans happen.

**Paused 2026-10-25.** Options: (1) PC hotspot direct link, (2) check the Samsung Wi-Fi settings above,
(3) accept roughly 150 ms+ latency, with our app or WFAS. To resume: the phone is on Verma, wireless
debugging is likely off, and the PC's Wi-Fi is on Verma while its Ethernet stays on Verma2.

## If you are resuming this work after a context reset

1. Read this README, then the project notes file (path above).
2. `python tools/latency_test.py --selftest` should print `SELFTEST PASS`.
3. Check the rig: Focusrite at 48 kHz and default output, desktop server running, phone connected,
   wireless adb connected (`adb devices`; re-run `adb mdns services` if the port changed).
4. Confirm the phone's target in the log before trusting a run, then run the test with `--repeat`.
