# VCX protocol v1

UDP, all integers little-endian. Shared by `sender/` (Windows, C#) and `receiver/` (Android, Kotlin).

## Ports and flows

| Port | Who listens | Used for |
|---|---|---|
| 47800 | phone | AUDIO from the PC; the phone sends STATS back from this socket to the packet's source address |
| 47801 | phone | DISCOVER (broadcast) from the PC; the phone replies DISCOVER_REPLY unicast from this socket to the source address |

The PC never listens on a fixed port. It uses **one** UDP socket bound to an ephemeral port for
everything (discover, audio, receiving replies), so every reply is a response on a flow the PC
opened and needs no inbound firewall rule.

## Common header (24 bytes)

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | magic, ASCII `VCX1` |
| 4 | 1 | version = 1 |
| 5 | 1 | type: 1 AUDIO, 2 DISCOVER, 3 DISCOVER_REPLY, 4 STATS |
| 6 | 2 | flags (AUDIO: bit 0 = payload is sender-generated silence fill) |
| 8 | 4 | seq u32 (AUDIO: +1 per packet, wraps; others: 0 or a counter) |
| 12 | 8 | sender timestamp u64, microseconds (PC: QPC-based; phone: elapsedRealtimeNanos/1000) |
| 20 | 2 | frames per payload u16 (AUDIO; default 120 = 2.5 ms) |
| 22 | 1 | format id (1 = 48 kHz, stereo, s16le, interleaved) |
| 23 | 1 | K = number of redundant previous payloads that follow (AUDIO; 0 in milestone 1) |

Receivers ignore packets with the wrong magic or version.

## AUDIO (type 1), PC → phone:47800

Body = current payload (`frames * 4` bytes), then K previous payloads, most recent first
(payload for seq-1, then seq-2, ...). With 120 frames and K = 1: 24 + 480 + 480 = 984 bytes.

## DISCOVER (type 2), PC → 255.255.255.255:47801 and the subnet broadcast

Body: UTF-8 PC name (may be empty).

## DISCOVER_REPLY (type 3), phone:47801 → PC source address

Body: u16 audio port (47800), then UTF-8 device name (e.g. `SM-S911U1`).

## STATS (type 4), phone:47800 → PC source address of the last AUDIO packet, about once a second

Body, 40 bytes:

| Offset | Type | Field |
|---|---|---|
| 0 | i32 | jitter buffer depth now, µs (queued audio not yet written to AudioTrack) |
| 4 | i32 | target depth, µs |
| 8 | u32 | packets received (total, including duplicates/late) |
| 12 | u32 | packets lost (seq gaps never filled) |
| 16 | u32 | packets recovered from redundancy |
| 20 | u32 | packets concealed (played as fill because missing) |
| 24 | u32 | late packets (arrived after their playout slot, discarded) |
| 28 | u32 | output underruns (AudioTrack starved / buffer ran empty during playout) |
| 32 | f32 | resampling ratio offset, ppm (0 when no drift control) |
| 36 | u32 | AudioTrack buffer size, frames |

The phone also logs the same numbers once a second to logcat, tag `VCX`, as one line:

```
VCX-STATS buf_ms=41.2 target_ms=40 rx=400 lost=0 recovered=0 concealed=0 late=0 underruns=0 rate_ppm=0.0 track_frames=192
```

Counters in the log line and STATS are cumulative since the stream started.
