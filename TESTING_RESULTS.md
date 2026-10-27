# Testing results: settings and config changes

Every setting or config change made while tuning the Wi-Fi audio link, with its estimated impact
(5 words or less). Add a row for each new change. Run-by-run numbers are in `README.md`
("Experiment 2"), and raw runs are in `tools/results/`.

| # | Change | Where | Estimated impact |
|---|---|---|---|
| 1 | Phone on Verma (Nest), not Verma2 | Network | Worse: two Wi-Fi hops |
| 2 | PC on Ethernet, hosting Mobile Hotspot | PC | Max stall 240 → 135 ms |
| 3 | PC Wi-Fi disconnected from Verma | PC | No clear change |
| 4 | Hotspot power saving off | PC | Stops hotspot shutting off |
| 5 | **Samsung Wi-Fi power saving off** | Phone | **Killed periodic stalls (biggest win)** |
| 6 | Nearby device scanning off | Phone | Changed with #5, unknown |
| 7 | Location → Wi-Fi scanning off | Phone | Scans continued anyway |
| 8 | Switch to better Wi-Fi off | Phone | Alone: 75 dropouts/10 min |
| 9 | **Location off while streaming (app auto)** | Phone app | **75 → 3 dropouts/10 min** |
| 10 | Wi-Fi lock mode (low latency / high perf / none) | Phone app | No difference |
| 11 | **24 ms phone audio buffer** (track_bursts 6) | Phone app | **Underruns gone, +14 ms latency** |
| 12 | 16 ms phone audio buffer | Phone app | Still underruns |
| 13 | 40 ms buffer target (30 tried) | Phone app | 30 too tight |
| 14 | Drift control rework + silence trim | Phone app | Fixed buffer creep |
| 15 | Allocation-free audio path, 192-frame writes | Phone app | No change in underruns |
| 16 | **Sender sends exactly the captured audio** | PC sender | **Removed sender-caused dropouts** |
| 17 | Redundancy kept off | Both | Unneeded: no true losses |
| 18 | Stay awake while charging (on, off for screen-off tests) | Phone | Test setup only |
| 19 | Wireless debugging on hotspot network | Phone | Test setup only |
| 20 | Tray app sender + app's new defaults | Both | 5/5 clean, −10.8 ms |
| 21 | Local network permission (Android 17) | Phone app | Fixes S26 not connecting |
| 22 | Play claims the stream (unicast discover + claim flag) | Both | Phone switch ~1 min → <0.5 s |
| 23 | Notifications debounced (10 s) and silent | PC sender | No toast on phone restart |
| 24 | Fast cut: lead > target+40 ms for 0.5 s → one crossfaded cut | Phone app | 120→80 ms in ~1 s |
