package com.vcx.receiver.core

import java.util.Locale

/** What the engine publishes once a second (and once at START). Counters are cumulative. */
data class EngineSnapshot(
    val running: Boolean,
    val primed: Boolean,
    val received: Long,
    val lost: Long,
    val late: Long,
    val concealed: Long,
    val bufferMs: Double,
    val targetMs: Int,
    /** Sender IP we are receiving from, if any. */
    val sender: String? = null,
    /** PC name from its DISCOVER, if known. */
    val pcName: String? = null,
    val underruns: Long = 0,
)

/** The last ~minute of snapshots (one per second) for "last minute" numbers and recent activity. */
class StatsHistory(private val capacity: Int = 61) {
    private val ring = arrayOfNulls<EngineSnapshot>(capacity)
    private var idx = 0
    var count = 0
        private set

    fun add(s: EngineSnapshot) {
        ring[idx] = s
        idx = (idx + 1) % capacity
        if (count < capacity) count++
    }

    fun clear() {
        java.util.Arrays.fill(ring, null)
        idx = 0
        count = 0
    }

    /** Snapshot [back] entries before the newest (0 = newest), clamped to the oldest available. */
    fun at(back: Int): EngineSnapshot? {
        if (count == 0) return null
        val b = back.coerceIn(0, count - 1)
        return ring[(idx - 1 - b + capacity * 2) % capacity]
    }

    /** Increase of a counter over the last [seconds] (0 if fewer than two snapshots). */
    fun delta(seconds: Int, f: (EngineSnapshot) -> Long): Long {
        val newest = at(0) ?: return 0
        val old = at(seconds) ?: return 0
        return (f(newest) - f(old)).coerceAtLeast(0)
    }
}

enum class PlayStatus { IDLE, WAITING, PLAYING, HICCUP, NO_WIFI }

data class UiState(
    val status: PlayStatus,
    val title: String,
    val stats: String,
    /** "from NAME (ip)" or empty. */
    val source: String,
    /** The big button shows pause (a stream is running) or play. */
    val showPause: Boolean,
)

/** Pure mapping from engine snapshots to what the main screen shows. */
object UiStateMapper {
    fun map(current: EngineSnapshot?, history: StatsHistory, wifiConnected: Boolean, idleTargetMs: Int): UiState {
        if (current == null || !current.running) {
            return UiState(PlayStatus.IDLE, "Idle", "Target $idleTargetMs ms", "", showPause = false)
        }
        val source = when {
            current.sender != null && current.pcName != null -> "from ${current.pcName} (${current.sender})"
            current.sender != null -> "from ${current.sender}"
            else -> ""
        }
        val receiving = if (history.count >= 2) history.delta(2) { it.received } > 0 else current.received > 0
        val lostMin = history.delta(60) { it.lost }
        val lateMin = history.delta(60) { it.late }
        val playingStats = String.format(
            Locale.US, "Buffer %.0f ms · target %d ms · lost %d · late %d /min",
            current.bufferMs, current.targetMs, lostMin, lateMin,
        )
        return when {
            !wifiConnected -> UiState(PlayStatus.NO_WIFI, "No Wi-Fi", "Waiting for Wi-Fi · target ${current.targetMs} ms", source, true)
            !current.primed || !receiving ->
                UiState(PlayStatus.WAITING, "Waiting for PC…", "Target ${current.targetMs} ms · port ${Vcx.AUDIO_PORT}", source, true)
            history.delta(3) { it.concealed } > 0 -> UiState(PlayStatus.HICCUP, "Wi-Fi hiccup", playingStats, source, true)
            else -> UiState(PlayStatus.PLAYING, "Playing", playingStats, source, true)
        }
    }
}
