# vcx-sender (milestone 1)

Console app that captures Windows system audio (WASAPI shared-mode loopback of the default render
device) and streams it to the Android receiver over UDP, per `../PROTOCOL.md`.

## Build

The .NET 8 SDK is per-user and not on PATH:

```powershell
$dotnet = "$env:LOCALAPPDATA\Microsoft\dotnet\dotnet.exe"
& $dotnet test  sender\vcx-sender.sln
& $dotnet publish sender\VcxSender\VcxSender.csproj -c Release -r win-x64 --self-contained false -p:PublishSingleFile=true
```

Output: `sender\VcxSender\bin\Release\net8.0-windows\win-x64\publish\vcx-sender.exe` (needs the .NET 8 runtime).

## Run

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
per STATS packet from the phone, and every 5 s a `sender:` line (packets sent, silence-fill packets,
WASAPI capture packets, max gap between sends in that window, send errors).

Exit codes: 0 ok, 1 no phone found, 2 bad arguments or capture could not start (e.g. the mix format is
not 48 kHz stereo float).

## How it works

- One capture thread (MMCSS "Pro Audio", highest priority) waits on the WASAPI event, a
  high-resolution waitable timer and a 2 ms poll fallback. Each captured chunk is converted
  float32 → s16 (clipped) and every full packet is sent immediately. WASAPI delivers in ~10 ms engine
  periods, so real audio leaves in groups of 4 packets per period.
- Silence fill: if no capture data arrives for 20 ms, the partial packet is zero-padded and sent, then
  silence packets (flag bit 0) are emitted at exactly 48 kHz on the QPC clock, trailing real time by
  20 ms. When capture resumes, the gap between the end of the silence and the first captured frame
  (from the WASAPI QPC position) is filled with zeros, so the phone's buffer depth is unchanged. Seq
  numbers come from one counter and never jump.
- Before K packets exist, the first packets carry fewer redundant payloads (the header K says how many).
- If capture fails mid-stream (device removed/changed), silence fill continues and capture is reopened
  every 2 s.

## Loopback test (no phone, no audio)

```powershell
python sender\tools\loopback_test.py                 # fake phone on 127.0.0.1, --phone 127.0.0.1 --duration 10
python sender\tools\loopback_test.py --discover --redundancy 1
```

Checks rate (±1 %), seq continuity, packet sizes, max inter-arrival gap, a STATS round trip and
(with `--discover`) a DISCOVER / DISCOVER_REPLY round trip. When `--phone`/`--discover-addr` is a
loopback address the sender binds its socket to 127.0.0.1 only.
