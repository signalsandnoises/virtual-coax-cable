# VCX sender

Captures Windows system audio (WASAPI shared-mode loopback of the default playback device) and streams
it to the Android receiver over UDP, per `../PROTOCOL.md`. Two front ends share one core:

| Project | Output | What |
|---|---|---|
| `VcxCore/` | `VcxCore.dll` (bundled) | protocol, capture, stream logic, config, reconnect, health, logging, `SenderService` |
| `VcxSender/` | `vcx-sender.exe` | console app for test scripts (unchanged CLI) |
| `VcxTray/` | `vcx-tray.exe` | tray app (WinForms NotifyIcon, no console) |
| `sender.Tests/` | | xUnit tests |

## Build

The .NET 8 SDK is per-user and not on PATH:

```powershell
$dotnet = "$env:LOCALAPPDATA\Microsoft\dotnet\dotnet.exe"
& $dotnet test  sender\vcx-sender.sln
& $dotnet publish sender\VcxSender\VcxSender.csproj -c Release -r win-x64 --self-contained false -p:PublishSingleFile=true
& $dotnet publish sender\VcxTray\VcxTray.csproj     -c Release -r win-x64 --self-contained false -p:PublishSingleFile=true
```

Outputs (need the .NET 8 runtime; the tray also needs the .NET 8 Windows Desktop runtime, both installed):
- `sender\VcxSender\bin\Release\net8.0-windows\win-x64\publish\vcx-sender.exe`
- `sender\VcxTray\bin\Release\net8.0-windows10.0.19041.0\win-x64\publish\vcx-tray.exe` (~26 MB: it bundles the
  WinRT projection used for the Mobile Hotspot API)

## Tray app (`vcx-tray.exe`)

Double-click the exe: an icon appears in the tray and (by default) streaming starts. Only one instance runs.

- **Icon:** grey square = stopped; green waves = streaming and the phone is answering (STATS within 3 s);
  amber waves = streaming but the phone isn't answering or hasn't been found yet; red "!" = error (e.g. the
  playback device's mix format is not 48 kHz stereo float, or sends failing). Double-click toggles streaming.
- **Tooltip:** phone name, buffer/target ms, lost/late packets in the last minute.
- **Menu:** status line, Start/Stop streaming, Phone ▸ (Automatic, discovered phones, Manual IP…, Search
  now), Mobile hotspot status (+ "Start mobile hotspot" when it is off, + "Keep hotspot on when no devices
  are connected" when Windows' hotspot power saving is on), Settings…, Open log folder, Exit.
- **Settings:** phone (Automatic / a discovered phone / manual IP), frames per packet (120), redundancy K (0),
  start with Windows (HKCU `Run` value `vcx-tray`, no admin), start streaming on launch (on), notifications (on).
- **Files:** config `%APPDATA%\vcx\config.json` (versioned JSON, migrated on load, a corrupt file is kept as
  `config.json.bad`); logs `%LOCALAPPDATA%\vcx\logs\vcx-tray-*.log` (2 MB per file, newest 10 kept). The log
  has a `phone:` line every 10 s or when lost/late/underrun counts change, a `sender:` line every 30 s, and
  every connect / lose / switch / device / network / power event.

Automatic behaviour:
- **Phone selection:** Automatic streams to the first phone that answers DISCOVER. Picking a phone remembers it
  by name (its IP can change on the hotspot). The last IP is saved, so the next launch starts sending at once.
- **Reconnect:** no STATS for 5 s → DISCOVER every 3 s (broadcast + every subnet broadcast, e.g.
  192.168.137.255) while still sending to the last IP; the first matching reply becomes the new target.
- **Sleep/wake:** on resume the socket is rebound and capture restarted.
- **Network change:** the socket is rebound 2 s after the addresses settle.
- **Default playback device change:** loopback re-opens on the new device (logged). If its format is
  unsupported the icon turns red and the stream continues with silence fill; capture is retried every 2 s.
- **Notifications** (Windows toasts): phone connected / lost / switched, errors, and once at launch if the
  Mobile Hotspot is off or will switch itself off when idle.

Debug flags: `--phone IP` (fixed target, not saved), `--exit-after SECONDS`, `--config PATH`, `--log-dir DIR`,
`--hotspot-status` (logs the hotspot state and exits).

## Console app (`vcx-sender.exe`)

```powershell
$exe = "sender\VcxSender\bin\Release\net8.0-windows\win-x64\publish\vcx-sender.exe"
& $exe --phone 10.0.0.202            # stream to a known phone IP
& $exe                               # discover the phone (broadcast), then stream
& $exe --discover-only               # list phones that reply within 3 s (exit 0 found, 1 none)
```

| Flag | Default | Meaning |
|---|---|---|
| `--phone IP` | (discover) | phone IP; skips discovery |
| `--port N` | 47800 | phone audio port when `--phone` is given (discovery uses the port in the reply) |
| `--frames N` | 120 | frames per packet (120 = 2.5 ms) |
| `--redundancy K` | 0 | previous payloads appended to each packet (seq-1, seq-2, ...) |
| `--duration S` | 0 | stop after S seconds; 0 = until Ctrl+C |
| `--discover-only` | | discovery only, then exit |
| `--discover-addr IP` | | send DISCOVER only to this address (testing) |
| `--verbose` | | socket, discovery and silence-fill transitions |

Output: one `phone: buf=..ms tgt=.. rx=.. lost=.. rec=.. conc=.. late=.. und=.. ppm=.. track=..` line
per STATS packet from the phone (plus `dly99 dlymax gap dmin` for 56-byte STATS), and every 5 s a
`sender:` line for that window: packets sent, `ratio` (packets/s ÷ 400), silence packets, WASAPI
captures, `cap_frames`, `fill_frames`, `discont_fill`, `trimmed`, max gap between sends, send errors.

Exit codes: 0 ok, 1 no phone found, 2 bad arguments or capture could not start (e.g. the mix format is
not 48 kHz stereo float).

## How the stream works

- One capture thread (MMCSS "Pro Audio", highest priority) waits on the WASAPI event, a
  high-resolution waitable timer and a 2 ms poll fallback. Each captured chunk is converted
  float32 → s16 (clipped) and every full packet is sent immediately. WASAPI delivers in ~10 ms engine
  periods, so real audio leaves in groups of 4 packets per period.
- While capture is active the stream is exactly the captured frames; WASAPI delivery jitter changes
  nothing. Zeros are inserted only when the WASAPI device position jumps between chunks (`discont_fill`).
- Silence fill: after 100 ms with no capture data (render idle) the partial packet is zero-padded, then
  silence packets (flag bit 0) keep the stream on the QPC real-time clock (one catch-up burst, then exactly
  48 kHz). On resume, captured frames that silence already covered are trimmed from the start of the
  batch (`capture resumed: trimmed=N frames`). Seq numbers never jump. Logic: `VcxCore/StreamCore.cs`.
- Before K packets exist, the first packets carry fewer redundant payloads (the header K says how many).
- If capture fails (device removed/changed, unsupported format), silence fill continues and capture is
  reopened every 2 s; a default-device change re-opens it immediately.

## Tests without a phone or audio

```powershell
python sender\tools\loopback_test.py                 # console: fake phone on 127.0.0.1, 10 s
python sender\tools\loopback_test.py --discover --redundancy 1
python sender\tools\tray_smoke.py                    # tray: 18 s run, STATS pause → lost → reconnect, clean exit
```

Both check rate (±1 %), seq continuity and packet sizes; the console test also checks a STATS and a
DISCOVER round trip, and the tray test checks the log (capture, streaming, connected, not answering,
reconnected, exited cleanly). With a loopback `--phone` the socket binds to 127.0.0.1 only, and the tray
smoke test uses a temp config (notifications off) and a temp log folder.
